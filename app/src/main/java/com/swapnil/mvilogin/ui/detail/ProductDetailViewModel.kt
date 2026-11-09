package com.swapnil.mvilogin.ui.detail

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swapnil.mvilogin.domain.model.AppError
import com.swapnil.mvilogin.domain.model.AppException
import com.swapnil.mvilogin.domain.model.ProductDetail
import com.swapnil.mvilogin.domain.repository.ProductRepository
import com.swapnil.mvilogin.ui.navigation.ProductDetailDestination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * A DETAIL screen is exactly one of Loading / Error / Content at a time, so here a
 * SEALED state fits better than a data class with flags: the UI's `when` is exhaustive,
 * and `Content` can only exist WITH a product (no nullable product + isLoading combos).
 */
sealed interface ProductDetailState {
    data object Loading : ProductDetailState
    data class Error(val error: AppError) : ProductDetailState

    @Immutable
    data class Content(val product: ProductDetail) : ProductDetailState
}

sealed interface ProductDetailIntent {
    data object Retry : ProductDetailIntent
}

/**
 * Different style from Login/Home on purpose: state is DERIVED with `stateIn` instead of
 * pushed into a MutableStateFlow. Good when the state is a pure function of inputs
 * (here: product id + retry counter) and there are few intents.
 */
@OptIn(ExperimentalCoroutinesApi::class) // flatMapLatest
@HiltViewModel
class ProductDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ProductRepository,
) : ViewModel() {

    // Type-safe nav stores the route's `id` property under the key "id".
    // Equivalent to savedStateHandle.toRoute<ProductDetailDestination>().id, but testable on plain JVM.
    private val productId: Int = checkNotNull(savedStateHandle.get<Int>(ProductDetailDestination.ARG_ID)) {
        "ProductDetailViewModel requires a product id"
    }

    private val retryCount = MutableStateFlow(0)

    val state: StateFlow<ProductDetailState> = retryCount
        .flatMapLatest { attempt ->
            flow {
                // Initial value of stateIn is already Loading; only re-emit it for retries,
                // so re-subscribing after background doesn't flash a spinner over content.
                if (attempt > 0) emit(ProductDetailState.Loading)
                emit(ProductDetailState.Content(repository.product(productId)))
            }.catch { e ->
                emit(ProductDetailState.Error((e as? AppException)?.error ?: AppError.Unknown(e.message)))
            }
        }
        // WhileSubscribed(5_000): survives rotation without refetching; if the user leaves the
        // app for > 5 s the upstream stops, and on return it refetches (fresh stock/price)
        // while still SHOWING the last Content (StateFlow keeps its value).
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProductDetailState.Loading)

    fun onIntent(intent: ProductDetailIntent) {
        when (intent) {
            ProductDetailIntent.Retry -> retryCount.update { it + 1 }
        }
    }
}
