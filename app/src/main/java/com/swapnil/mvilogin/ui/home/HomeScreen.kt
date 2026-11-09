package com.swapnil.mvilogin.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import com.swapnil.mvilogin.domain.model.Category
import com.swapnil.mvilogin.domain.model.ProductSummary
import com.swapnil.mvilogin.ui.common.FullScreenLoading
import com.swapnil.mvilogin.ui.common.FullScreenMessage
import com.swapnil.mvilogin.ui.common.PriceText
import com.swapnil.mvilogin.ui.common.RatingText
import com.swapnil.mvilogin.ui.login.toMessage
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** Stateful route: ViewModel + effects. */
@Composable
fun HomeRoute(
    onOpenProduct: (id: Int) -> Unit,
    onLoggedOut: (sessionExpired: Boolean) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnOpenProduct by rememberUpdatedState(onOpenProduct)
    val currentOnLoggedOut by rememberUpdatedState(onLoggedOut)

    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is HomeEffect.NavigateToDetail -> currentOnOpenProduct(effect.id)
                    is HomeEffect.NavigateToLogin -> currentOnLoggedOut(effect.sessionExpired)
                    is HomeEffect.ShowMessage -> scope.launch { snackbarHostState.showSnackbar(effect.message) }
                }
            }
        }
    }

    val onIntent = remember(viewModel) { viewModel::onIntent }
    HomeScreen(state = state, snackbarHostState = snackbarHostState, onIntent = onIntent)
}

/** Stateless screen: previewable and testable without Hilt. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeState,
    snackbarHostState: SnackbarHostState,
    onIntent: (HomeIntent) -> Unit,
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Hi, ${state.userName ?: "there"}", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "🛒 ${state.cartItemCount} items in your cart",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { onIntent(HomeIntent.SimulateSessionExpiry) }) { Text("Expire") }
                    TextButton(onClick = { onIntent(HomeIntent.Logout) }) { Text("Logout") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            SearchField(query = state.query, onQueryChange = { onIntent(HomeIntent.QueryChanged(it)) })
            CategoryChips(
                categories = state.categories,
                selected = state.selectedCategory,
                onSelect = { onIntent(HomeIntent.CategorySelected(it)) },
            )

            // Old results stay visible while a new filter loads -> thin bar instead of a blank screen.
            if (state.isLoading && state.products.isNotEmpty()) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            Box(Modifier.weight(1f)) {
                when {
                    state.isLoading && state.products.isEmpty() -> FullScreenLoading()
                    state.showFullScreenError -> FullScreenMessage(
                        message = state.error?.toMessage().orEmpty(),
                        actionLabel = "Retry",
                        onAction = { onIntent(HomeIntent.Retry) },
                    )
                    state.showEmpty -> FullScreenMessage("No products match your search")
                    else -> ProductList(
                        products = state.products,
                        isLoadingMore = state.isLoadingMore,
                        endReached = state.endReached,
                        onProductClick = { onIntent(HomeIntent.ProductClicked(it)) },
                        onLoadMore = { onIntent(HomeIntent.LoadMore) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Search products (e.g. phone)") },
        singleLine = true,
        trailingIcon = {
            if (query.isNotEmpty()) TextButton(onClick = { onQueryChange("") }) { Text("Clear") }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun CategoryChips(categories: List<Category>, selected: String?, onSelect: (String?) -> Unit) {
    if (categories.isEmpty()) return
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "all") {
            FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("All") })
        }
        items(categories, key = { it.slug }) { category ->
            FilterChip(
                selected = selected == category.slug,
                onClick = { onSelect(category.slug) },
                label = { Text(category.name) },
            )
        }
    }
}

@Composable
private fun ProductList(
    products: List<ProductSummary>,
    isLoadingMore: Boolean,
    endReached: Boolean,
    onProductClick: (Int) -> Unit,
    onLoadMore: () -> Unit,
) {
    val listState = rememberLazyListState()
    val currentOnLoadMore by rememberUpdatedState(onLoadMore)

    // derivedStateOf: the scroll position changes every frame, but this Boolean only flips
    // occasionally. Readers recompose/re-run only when the BOOLEAN changes, not on every pixel.
    val shouldLoadMore by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - PREFETCH_DISTANCE
        }
    }
    // snapshotFlow turns Compose state into a Flow -> we can use Flow operators on it.
    LaunchedEffect(listState) {
        snapshotFlow { shouldLoadMore }
            .distinctUntilChanged()
            .filter { it }
            .collect { currentOnLoadMore() }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        // key = stable id: Compose keeps each row's state/position across inserts and skips
        // unchanged rows. contentType lets Compose reuse compositions between same-type items.
        items(products, key = { it.id }, contentType = { "product" }) { product ->
            ProductRow(product = product, onClick = { onProductClick(product.id) })
        }
        if (isLoadingMore) {
            item(key = "loading", contentType = "footer") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                }
            }
        } else if (endReached && products.isNotEmpty()) {
            item(key = "end", contentType = "footer") {
                Text(
                    "You've reached the end · ${products.size} products",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun ProductRow(product: ProductSummary, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Coil: async load, memory + disk cache, downsampled to the 80dp target size
            // (decoding a full-size image into a thumbnail slot wastes memory).
            AsyncImage(
                model = product.thumbnailUrl,
                contentDescription = product.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(product.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    product.brand ?: product.category.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PriceText(product.price, product.discountPercentage)
                RatingText(product.rating)
            }
        }
    }
}

private const val PREFETCH_DISTANCE = 5

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    MaterialTheme {
        HomeScreen(
            state = HomeState(
                userName = "Emily",
                cartItemCount = 3,
                categories = listOf(Category("beauty", "Beauty"), Category("smartphones", "Smartphones")),
                isLoading = false,
                products = List(3) { i ->
                    ProductSummary(i, "Product $i", "Brand", "beauty", 19.99 + i, 10.0, 4.5, null)
                },
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onIntent = {},
        )
    }
}
