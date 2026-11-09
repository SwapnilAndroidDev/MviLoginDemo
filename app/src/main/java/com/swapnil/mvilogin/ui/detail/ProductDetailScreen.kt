package com.swapnil.mvilogin.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.swapnil.mvilogin.domain.model.ProductDetail
import com.swapnil.mvilogin.domain.model.Review
import com.swapnil.mvilogin.domain.model.StockStatus
import com.swapnil.mvilogin.ui.common.FullScreenLoading
import com.swapnil.mvilogin.ui.common.FullScreenMessage
import com.swapnil.mvilogin.ui.common.PriceText
import com.swapnil.mvilogin.ui.common.RatingText
import com.swapnil.mvilogin.ui.login.toMessage

@Composable
fun ProductDetailRoute(onBack: () -> Unit, viewModel: ProductDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ProductDetailScreen(
        state = state,
        onBack = onBack,
        onRetry = { viewModel.onIntent(ProductDetailIntent.Retry) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductDetailScreen(state: ProductDetailState, onBack: () -> Unit, onRetry: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        (state as? ProductDetailState.Content)?.product?.title ?: "Product",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            // Exhaustive `when` over the sealed state: the compiler guarantees every case is drawn.
            when (state) {
                ProductDetailState.Loading -> FullScreenLoading()
                is ProductDetailState.Error -> FullScreenMessage(state.error.toMessage(), actionLabel = "Retry", onAction = onRetry)
                is ProductDetailState.Content -> ProductDetailContent(state.product)
            }
        }
    }
}

/**
 * The whole screen is ONE LazyColumn. Reviews can be long, so they are lazy `items`
 * (composed only when visible) instead of a Column inside a verticalScroll, which would
 * compose every review up front. Never nest a LazyColumn inside a verticalScroll (crash:
 * infinite height constraints).
 */
@Composable
private fun ProductDetailContent(product: ProductDetail) {
    LazyColumn(
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "images") { ImagePager(product.images, product.title) }

        item(key = "header") {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(product.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull(product.brand, product.category.replaceFirstChar { it.uppercase() }).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RatingText(product.rating)
                    StockBadge(product.stockStatus, product.stock)
                }
                PriceText(product.price, product.discountPercentage, large = true)
            }
        }

        item(key = "description") {
            Text(product.description, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp))
        }

        if (product.tags.isNotEmpty()) {
            item(key = "tags") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(product.tags, key = { it }) { tag -> AssistChip(onClick = {}, label = { Text("#$tag") }) }
                }
            }
        }

        item(key = "info") {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoRow("Warranty", product.warranty)
                InfoRow("Shipping", product.shipping)
                InfoRow("Returns", product.returnPolicy)
                InfoRow("Minimum order", product.minimumOrderQuantity?.let { "$it units" })
            }
        }

        item(key = "reviews-header") {
            Column(Modifier.padding(horizontal = 16.dp)) {
                HorizontalDivider()
                Text(
                    "Reviews (${product.reviews.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
        items(product.reviews, contentType = { "review" }) { review -> ReviewRow(review) }
    }
}

@Composable
private fun ImagePager(images: List<String>, title: String) {
    if (images.isEmpty()) return
    val pagerState = rememberPagerState(pageCount = { images.size })
    Box {
        // HorizontalPager is lazy too: only the visible page (+ neighbours) is composed/loaded.
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth().aspectRatio(1f)) { page ->
            AsyncImage(
                model = images[page],
                contentDescription = "$title image ${page + 1}",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
        if (images.size > 1) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.Black.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
            ) {
                Text(
                    "${pagerState.currentPage + 1} / ${images.size}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun StockBadge(status: StockStatus, stock: Int) {
    // Exhaustive `when` over an enum.
    val (container, content) = when (status) {
        StockStatus.IN_STOCK -> Color(0xFFE6F4EA) to Color(0xFF1E7B34)
        StockStatus.LOW_STOCK -> Color(0xFFFFF4E0) to Color(0xFFB26A00)
        StockStatus.OUT_OF_STOCK -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(color = container, shape = RoundedCornerShape(8.dp)) {
        Text(
            if (status == StockStatus.OUT_OF_STOCK) status.label else "${status.label} · $stock left",
            color = content,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(0.35f))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.65f))
    }
}

@Composable
private fun ReviewRow(review: Review) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(review.reviewerName, style = MaterialTheme.typography.labelLarge)
            Text("★".repeat(review.rating.coerceIn(0, 5)), color = MaterialTheme.colorScheme.tertiary)
        }
        Text(review.comment, style = MaterialTheme.typography.bodyMedium)
        Text(review.date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Preview(showBackground = true)
@Composable
private fun ProductDetailPreview() {
    MaterialTheme {
        ProductDetailScreen(
            state = ProductDetailState.Content(
                ProductDetail(
                    id = 1, title = "Essence Mascara Lash Princess", description = "A popular mascara.",
                    brand = "Essence", category = "beauty", price = 9.99, discountPercentage = 7.17, rating = 4.94,
                    stock = 5, stockStatus = StockStatus.LOW_STOCK, tags = listOf("beauty", "mascara"), images = emptyList(),
                    warranty = "1 month warranty", shipping = "Ships in 1 month", returnPolicy = "30 days return policy",
                    minimumOrderQuantity = 24,
                    reviews = listOf(Review(5, "Very satisfied!", "Scarlett Wright", "2024-05-23")),
                )
            ),
            onBack = {},
            onRetry = {},
        )
    }
}
