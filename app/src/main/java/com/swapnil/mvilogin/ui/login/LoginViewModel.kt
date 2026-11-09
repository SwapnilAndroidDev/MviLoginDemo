package com.swapnil.mvilogin.ui.login

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swapnil.mvilogin.domain.model.AppError
import com.swapnil.mvilogin.domain.model.AppException
import com.swapnil.mvilogin.domain.model.PasswordStrength
import com.swapnil.mvilogin.domain.model.UsernameError
import com.swapnil.mvilogin.domain.repository.NetworkMonitor
import com.swapnil.mvilogin.domain.repository.SessionEvents
import com.swapnil.mvilogin.domain.usecase.LoginUseCase
import com.swapnil.mvilogin.domain.usecase.ValidateUsernameUseCase
import com.swapnil.mvilogin.ui.navigation.LoginDestination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * The MVI "store":   Intent ──► onIntent() ──► reduce{} ──► StateFlow<LoginState> ──► UI
 *                                       └──────────────► Channel<LoginEffect>   ──► UI (once)
 *
 * @HiltViewModel + @Inject constructor: Hilt builds this VM with its dependencies and
 * keeps it in the destination's ViewModelStore (survives rotation).
 */
@OptIn(FlowPreview::class) // debounce is still marked FlowPreview
@HiltViewModel
class LoginViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle, // nav arguments arrive here (and survive process death)
    private val login: LoginUseCase,
    private val validateUsername: ValidateUsernameUseCase,
    sessionEvents: SessionEvents,
    networkMonitor: NetworkMonitor,
) : ViewModel() {

    // ───────────── STATE: StateFlow ─────────────
    // Private mutable, public read-only: only the VM can change state (encapsulation).
    private val _state = MutableStateFlow(LoginState())
    val state: StateFlow<LoginState> = _state.asStateFlow()

    // ───────────── EFFECTS: Channel (not SharedFlow) ─────────────
    // A Channel BUFFERS an effect until the UI collects it and delivers it ONCE to ONE collector.
    // SharedFlow(replay = 0) would DROP "NavigateToHome" if it fired while the app was in the
    // background (no subscriber); replay = 1 would RE-deliver it after rotation (navigate twice).
    private val _effects = Channel<LoginEffect>(Channel.BUFFERED)
    val effects: Flow<LoginEffect> = _effects.receiveAsFlow()

    private var loginJob: Job? = null

    init {
        // Same key as LoginDestination.sessionExpired; equivalent to toRoute<LoginDestination>()
        // but keeps plain-JVM unit tests free of Android Bundle/NavType dependencies.
        if (savedStateHandle.get<Boolean>(LoginDestination.ARG_SESSION_EXPIRED) == true) {
            sendEffect(LoginEffect.ShowMessage(AppError.SessionExpired.toMessage()))
        }
        observeSystemEvents(networkMonitor, sessionEvents)
        observeFormValidation()
    }

    // ───────────── INTENT HANDLER ─────────────
    // The ONLY entry point for changes. Exhaustive `when` over the sealed interface.
    fun onIntent(intent: LoginIntent) {
        when (intent) {
            // Text is reduced SYNCHRONOUSLY. Never debounce/async the TextField value itself —
            // Compose TextField needs the new value immediately or the cursor jumps.
            is LoginIntent.UsernameChanged ->
                reduce { copy(username = intent.value, status = status.clearError()) }

            is LoginIntent.PasswordChanged ->
                reduce { copy(password = intent.value, status = status.clearError()) }

            LoginIntent.TogglePasswordVisibility ->
                reduce { copy(isPasswordVisible = !isPasswordVisible) }

            LoginIntent.FillDemoCredentials ->
                reduce {
                    copy(username = DemoCredentials.USERNAME, password = DemoCredentials.PASSWORD, status = LoginStatus.Idle)
                }

            LoginIntent.Submit -> submit()

            is LoginIntent.NetworkChanged ->
                reduce { copy(isOnline = intent.isOnline) }

            is LoginIntent.SessionEventReceived ->
                sendEffect(LoginEffect.ShowMessage(intent.event.toMessage()))
        }
    }

    // ───────────── MERGE ─────────────
    // Two independent system streams -> ONE stream of intents -> the same onIntent().
    // merge collects all sources concurrently and forwards each value as it arrives.
    // No pairing, no waiting: use it when sources are "the same kind of thing" (events).
    private fun observeSystemEvents(networkMonitor: NetworkMonitor, sessionEvents: SessionEvents) {
        merge(
            networkMonitor.isOnline.map { LoginIntent.NetworkChanged(it) },
            sessionEvents.events.map { LoginIntent.SessionEventReceived(it) },
        )
            .onEach { onIntent(it) }
            .launchIn(viewModelScope) // cancelled automatically in onCleared()
    }

    // ───────────── COMBINE ─────────────
    // Form validity depends on the LATEST username, password AND connectivity.
    // combine re-runs the lambda whenever ANY source emits, using the latest value of the others
    // (it waits until every source has emitted at least once).
    private fun observeFormValidation() {
        val username = _state.map { it.username }
            .distinctUntilChanged()          // ignore state changes that didn't touch username
            .debounce(USERNAME_DEBOUNCE_MS)  // don't show an error on every keystroke
        val password = _state.map { it.password }.distinctUntilChanged()
        val online = _state.map { it.isOnline }.distinctUntilChanged()

        combine(username, password, online) { u, p, isOnline ->
            val usernameError = if (u.isEmpty()) null else validateUsername(u) // no error on untouched field
            val strength = PasswordStrength.of(p)                              // O(n), allocation-free
            FormValidation(
                usernameError = usernameError,
                strength = strength,
                isValid = u.isNotEmpty() && usernameError == null &&
                    strength != PasswordStrength.EMPTY && isOnline,
            )
        }
            .distinctUntilChanged() // skip reduce() when the validation result didn't change
            .onEach { v ->
                reduce {
                    copy(usernameError = v.usernameError, passwordStrength = v.strength, isFormValid = v.isValid)
                }
            }
            .launchIn(viewModelScope)
    }

    // ───────────── SUBMIT: Flow pipeline with operators ─────────────
    private fun submit() {
        val current = _state.value
        if (current.isLoading) return // double-tap guard

        // Re-validate synchronously: the debounced validation may be up to 300 ms stale.
        val usernameError = validateUsername(current.username)
        if (usernameError != null) {
            reduce { copy(usernameError = usernameError) }
            return
        }
        if (current.password.isBlank()) {
            sendEffect(LoginEffect.ShowMessage("Enter your password"))
            return
        }
        if (!current.isOnline) {
            sendEffect(LoginEffect.ShowMessage("You're offline. Check your connection."))
            return
        }

        loginJob?.cancel()
        loginJob = login(current.username, current.password)
            // ORDER MATTERS. retry sits ABOVE onStart, so a retry re-runs only the network part,
            // not onStart (Loading is set once).
            .retry(MAX_RETRIES) { cause ->
                val retryable = (cause as? AppException)?.error == AppError.Network
                if (retryable) delay(RETRY_DELAY_MS) // the predicate is suspend -> backoff is free
                retryable
            }
            .onStart { reduce { copy(status = LoginStatus.Loading) } }
            .onEach { session ->
                // Clear the password from memory once it has done its job.
                reduce { copy(password = "", status = LoginStatus.Success(session.profile.firstName)) }
                _effects.send(LoginEffect.NavigateToHome)
            }
            // catch handles exceptions from everything ABOVE it (including onEach).
            // It does not swallow CancellationException, so cancellation still works.
            .catch { cause ->
                reduce { copy(status = LoginStatus.Error(cause.toAppError())) }
            }
            .launchIn(viewModelScope)
    }

    // ───────────── REDUCER ─────────────
    // update{} is an atomic compare-and-set loop: safe even if two coroutines reduce at once.
    // `_state.value = _state.value.copy(...)` is read-modify-write and can LOSE an update.
    private fun reduce(transform: LoginState.() -> LoginState) = _state.update(transform)

    private fun sendEffect(effect: LoginEffect) {
        _effects.trySend(effect) // BUFFERED (64) channel: non-suspending send is fine here
    }

    private fun LoginStatus.clearError(): LoginStatus =
        if (this is LoginStatus.Error) LoginStatus.Idle else this

    private fun Throwable.toAppError(): AppError =
        (this as? AppException)?.error ?: AppError.Unknown(message)

    private data class FormValidation(
        val usernameError: UsernameError?,
        val strength: PasswordStrength,
        val isValid: Boolean,
    )

    companion object {
        const val USERNAME_DEBOUNCE_MS = 300L
        const val MAX_RETRIES = 2L
        const val RETRY_DELAY_MS = 1_000L
    }
}
