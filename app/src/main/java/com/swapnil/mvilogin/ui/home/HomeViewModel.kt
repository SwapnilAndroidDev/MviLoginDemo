package com.swapnil.mvilogin.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swapnil.mvilogin.domain.model.AppError
import com.swapnil.mvilogin.domain.model.AppException
import com.swapnil.mvilogin.domain.model.ProductQuery
import com.swapnil.mvilogin.domain.model.SessionEvent
import com.swapnil.mvilogin.domain.repository.AuthRepository
import com.swapnil.mvilogin.domain.repository.ProductRepository
import com.swapnil.mvilogin.domain.repository.SessionEvents
import com.swapnil.mvilogin.ui.login.toMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Home = search + category filter + paginated list.
 *
 *   query (debounced) ─┐
 *   category ──────────┼─ combine ─► ProductQuery ─► flatMapLatest { load page 0 } ─► reduce
 *   reloadTrigger ─────┘                               (cancels the previous request!)
 *
 *   LoadMore intent ─► loadMore() ─► append page N ─► reduce
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class) // debounce, flatMapLatest
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val productRepository: ProductRepository,
    private val authRepository: AuthRepository,
    private val sessionEvents: SessionEvents,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    private val _effects = Channel<HomeEffect>(Channel.BUFFERED)
    val effects: Flow<HomeEffect> = _effects.receiveAsFlow()

    /** Bumped by Retry so combine re-emits the SAME filter and flatMapLatest reloads it. */
    private val reloadTrigger = MutableStateFlow(0)

    /** The filter the current list was loaded with (the typed query may be mid-debounce). */
    private var activeQuery: ProductQuery? = null
    private var loadMoreJob: Job? = null

    init {
        observeSession()
        loadCategories()
        observeFilters()
    }

    fun onIntent(intent: HomeIntent) {
        when (intent) {
            // Search and category are mutually exclusive in DummyJSON's API, so the reducer
            // enforces it: typing clears the category, picking a category clears the search.
            is HomeIntent.QueryChanged -> reduce {
                copy(query = intent.value, selectedCategory = if (intent.value.isBlank()) selectedCategory else null)
            }

            is HomeIntent.CategorySelected -> reduce { copy(selectedCategory = intent.slug, query = "") }

            HomeIntent.LoadMore -> loadMore()

            HomeIntent.Retry -> {
                if (_state.value.categories.isEmpty()) loadCategories()
                reloadTrigger.update { it + 1 }
            }

            is HomeIntent.ProductClicked -> sendEffect(HomeEffect.NavigateToDetail(intent.id))

            HomeIntent.Logout -> {
                authRepository.logout()
                sendEffect(HomeEffect.NavigateToLogin(sessionExpired = false))
            }

            HomeIntent.SimulateSessionExpiry ->
                viewModelScope.launch { sessionEvents.publish(SessionEvent.Expired) }
        }
    }

    private fun observeSession() {
        // StateFlow<Session?> from the singleton repository -> greeting + cart badge.
        authRepository.session
            .onEach { s -> reduce { copy(userName = s?.profile?.firstName, cartItemCount = s?.cartItemCount ?: 0) } }
            .launchIn(viewModelScope)

        // SharedFlow broadcast (from AuthInterceptor on a real 401, or the demo button).
        sessionEvents.events
            .filterIsInstance<SessionEvent.Expired>()
            .onEach {
                authRepository.logout()
                sendEffect(HomeEffect.NavigateToLogin(sessionExpired = true))
            }
            .launchIn(viewModelScope)
    }

    private fun loadCategories() {
        viewModelScope.launch {
            // Catch ONLY AppException. `runCatching {}` would also catch CancellationException
            // and break cancellation — a classic coroutine bug.
            try {
                val categories = productRepository.categories()
                reduce { copy(categories = categories) }
            } catch (e: AppException) {
                // Non-fatal: the list still works without chips. Retry reloads them.
            }
        }
    }

    // ───────────── COMBINE + FLATMAPLATEST ─────────────
    private fun observeFilters() {
        val query = _state.map { it.query.trim() }
            .distinctUntilChanged()
            // Clearing the box should reload instantly; typing waits for a pause.
            .debounce { text -> if (text.isEmpty()) 0L else SEARCH_DEBOUNCE_MS }
        val category = _state.map { it.selectedCategory }.distinctUntilChanged()

        combine(query, category, reloadTrigger) { text, slug, _ -> ProductQuery(text, slug) }
            // flatMapLatest: when a NEW filter arrives, the in-flight request for the OLD one is
            // CANCELLED (Retrofit cancels the HTTP call). Without it, a slow response for "pho"
            // could arrive after "phone" and overwrite the newer results (race condition).
            .flatMapLatest { productQuery ->
                flow { emit(productRepository.products(productQuery, skip = 0, limit = PAGE_SIZE)) }
                    .onStart {
                        loadMoreJob?.cancel() // a page-N request for the old filter is now stale
                        reduce { copy(isLoading = true, isLoadingMore = false, error = null) }
                    }
                    .onEach { page ->
                        activeQuery = productQuery
                        reduce { copy(isLoading = false, products = page.items, endReached = page.endReached) }
                    }
                    .catch { e ->
                        reduce { copy(isLoading = false, products = emptyList(), error = e.toAppError()) }
                    }
            }
            .launchIn(viewModelScope)
    }

    // ───────────── PAGINATION ─────────────
    private fun loadMore() {
        val current = _state.value
        if (current.isLoading || current.isLoadingMore || current.endReached || current.error != null) return
        val query = activeQuery ?: return

        loadMoreJob = viewModelScope.launch {
            reduce { copy(isLoadingMore = true) }
            try {
                val page = productRepository.products(query, skip = current.products.size, limit = PAGE_SIZE)
                reduce {
                    copy(
                        isLoadingMore = false,
                        // distinctBy guards LazyColumn keys (duplicate key = crash) if the server's
                        // data shifts between pages. O(n) time/space with a HashSet.
                        products = (products + page.items).distinctBy { it.id },
                        endReached = page.endReached,
                    )
                }
            } catch (e: AppException) {
                reduce { copy(isLoadingMore = false) }
                sendEffect(HomeEffect.ShowMessage(e.error.toMessage()))
            }
        }
    }

    private fun reduce(transform: HomeState.() -> HomeState) = _state.update(transform)

    private fun sendEffect(effect: HomeEffect) {
        _effects.trySend(effect)
    }

    private fun Throwable.toAppError(): AppError = (this as? AppException)?.error ?: AppError.Unknown(message)

    companion object {
        const val PAGE_SIZE = 20
        const val SEARCH_DEBOUNCE_MS = 400L
    }
}
