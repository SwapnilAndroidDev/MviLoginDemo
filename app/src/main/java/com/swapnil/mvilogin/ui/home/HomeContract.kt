package com.swapnil.mvilogin.ui.home

import androidx.compose.runtime.Immutable
import com.swapnil.mvilogin.domain.model.AppError
import com.swapnil.mvilogin.domain.model.Category
import com.swapnil.mvilogin.domain.model.ProductSummary

/**
 * A LIST screen keeps ONE data class state (not a sealed Loading/Content/Error) because
 * several things are true at the same time: the list is showing AND the next page is
 * loading, or old results are showing AND a new search is running. A sealed state can
 * only be in one branch at a time; a data class with flags models overlap naturally.
 *
 * @Immutable: List<T> is an interface Compose can't prove is immutable. We promise it
 * (we only ever replace the list via copy, never mutate it), so rows can be skipped.
 * Stricter alternative: kotlinx.collections.immutable's ImmutableList/PersistentList.
 */
@Immutable
data class HomeState(
    val userName: String? = null,
    val cartItemCount: Int = 0,
    val query: String = "",
    val categories: List<Category> = emptyList(),
    val selectedCategory: String? = null, // null = "All"
    val products: List<ProductSummary> = emptyList(),
    val isLoading: Boolean = true,        // first page for the current filter
    val isLoadingMore: Boolean = false,   // next page
    val endReached: Boolean = false,
    val error: AppError? = null,
) {
    val showFullScreenError: Boolean get() = error != null && products.isEmpty()
    val showEmpty: Boolean get() = !isLoading && error == null && products.isEmpty()
}

sealed interface HomeIntent {
    data class QueryChanged(val value: String) : HomeIntent
    data class CategorySelected(val slug: String?) : HomeIntent
    data object LoadMore : HomeIntent
    data object Retry : HomeIntent
    data class ProductClicked(val id: Int) : HomeIntent
    data object Logout : HomeIntent
    data object SimulateSessionExpiry : HomeIntent // demo: publishes on the SharedFlow bus
}

sealed interface HomeEffect {
    data class NavigateToDetail(val id: Int) : HomeEffect
    data class NavigateToLogin(val sessionExpired: Boolean) : HomeEffect
    data class ShowMessage(val message: String) : HomeEffect
}
