package com.swapnil.mvilogin.ui.login

import androidx.compose.runtime.Immutable
import com.swapnil.mvilogin.domain.model.AppError
import com.swapnil.mvilogin.domain.model.PasswordStrength
import com.swapnil.mvilogin.domain.model.SessionEvent
import com.swapnil.mvilogin.domain.model.UsernameError

/*
 * ┌──────────────────────────── MVI CONTRACT ────────────────────────────┐
 * │ STATE  : everything the screen needs to draw itself, as ONE immutable │
 * │          object. Always has a value. Survives rotation (lives in VM). │
 * │ INTENT : everything that can happen TO the screen (user or system).   │
 * │ EFFECT : one-time things that are NOT state (navigate, snackbar).     │
 * └───────────────────────────────────────────────────────────────────────┘
 */

/** DummyJSON's public demo account. */
object DemoCredentials {
    const val USERNAME = "emilys"
    const val PASSWORD = "emilyspass"
}

/**
 * ONE immutable state object => impossible combinations like
 * "isLoading = true AND error shown" can't drift apart, because every change
 * produces a complete new snapshot via copy().
 *
 * @Immutable promises Compose that instances never change after construction,
 * so composables taking LoginState can be skipped when an equal instance is passed.
 */
@Immutable
data class LoginState(
    val username: String = "",
    val password: String = "",
    val isPasswordVisible: Boolean = false,
    val usernameError: UsernameError? = null,
    val passwordStrength: PasswordStrength = PasswordStrength.EMPTY,
    val isFormValid: Boolean = false,
    val isOnline: Boolean = true,
    val status: LoginStatus = LoginStatus.Idle,
) {
    // Derived values: computed from state, never stored -> can never be out of sync.
    val isLoading: Boolean get() = status == LoginStatus.Loading
    val canSubmit: Boolean get() = isFormValid && !isLoading
}

/** Sealed: the request lifecycle. Success/Error carry data, Idle/Loading don't. */
sealed interface LoginStatus {
    data object Idle : LoginStatus
    data object Loading : LoginStatus
    data class Success(val userName: String) : LoginStatus
    data class Error(val error: AppError) : LoginStatus
}

/**
 * Sealed interface of intents. The UI can only send what's listed here,
 * and the ViewModel's `when` must handle every one (compile-time exhaustiveness).
 */
sealed interface LoginIntent {
    // --- user intents ---
    data class UsernameChanged(val value: String) : LoginIntent
    data class PasswordChanged(val value: String) : LoginIntent
    data object TogglePasswordVisibility : LoginIntent
    data object FillDemoCredentials : LoginIntent
    data object Submit : LoginIntent

    // --- system intents (produced inside the VM by MERGE-ing system streams) ---
    data class NetworkChanged(val isOnline: Boolean) : LoginIntent
    data class SessionEventReceived(val event: SessionEvent) : LoginIntent
}

/** One-shot side effects: must be handled exactly once, not re-played on rotation. */
sealed interface LoginEffect {
    data object NavigateToHome : LoginEffect
    data class ShowMessage(val message: String) : LoginEffect
}
