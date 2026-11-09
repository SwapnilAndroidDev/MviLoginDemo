package com.swapnil.mvilogin.domain.model

/*
 * DOMAIN models are what the app works with. They are NOT the JSON DTOs:
 * DTOs mirror the API (nullable, stringly-typed), domain models mirror the app's needs.
 * If DummyJSON renames a field, only the DTO + mapper change; ViewModels and UI don't.
 */

data class Category(val slug: String, val name: String)

/** The filter the Home list is built from (search text OR a category). */
data class ProductQuery(val text: String = "", val categorySlug: String? = null)

/** List rows only need a few fields — the API is asked for exactly these via `select`. */
data class ProductSummary(
    val id: Int,
    val title: String,
    val brand: String?,
    val category: String,
    val price: Double,
    val discountPercentage: Double,
    val rating: Double,
    val thumbnailUrl: String?,
)

data class ProductDetail(
    val id: Int,
    val title: String,
    val description: String,
    val brand: String?,
    val category: String,
    val price: Double,
    val discountPercentage: Double,
    val rating: Double,
    val stock: Int,
    val stockStatus: StockStatus,
    val tags: List<String>,
    val images: List<String>,
    val warranty: String?,
    val shipping: String?,
    val returnPolicy: String?,
    val minimumOrderQuantity: Int?,
    val reviews: List<Review>,
)

data class Review(
    val rating: Int,
    val comment: String,
    val reviewerName: String,
    val date: String,
)

/** One page of a paginated list. */
data class Page<T>(val items: List<T>, val total: Int, val skip: Int) {
    val endReached: Boolean get() = skip + items.size >= total
}

/** Price after discount — pure function, unit-testable, shared by list and detail. */
fun discountedPrice(price: Double, discountPercentage: Double): Double =
    price * (1 - discountPercentage / 100.0)

/**
 * ENUM with a factory: turns the API's free-text "availabilityStatus" into a closed set
 * the UI can switch over exhaustively. Falls back to the stock count if the text is unknown.
 */
enum class StockStatus(val label: String) {
    IN_STOCK("In stock"),
    LOW_STOCK("Low stock"),
    OUT_OF_STOCK("Out of stock");

    companion object {
        fun from(apiValue: String?, stock: Int): StockStatus = when (apiValue?.trim()?.lowercase()) {
            "in stock" -> IN_STOCK
            "low stock" -> LOW_STOCK
            "out of stock" -> OUT_OF_STOCK
            else -> when {
                stock <= 0 -> OUT_OF_STOCK
                stock < 10 -> LOW_STOCK
                else -> IN_STOCK
            }
        }
    }
}
