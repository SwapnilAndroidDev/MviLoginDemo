package com.swapnil.mvilogin.data.network

import kotlinx.serialization.Serializable

/*
 * DTOs (Data Transfer Objects) mirror the JSON exactly.
 * - Defaults on every optional field + `ignoreUnknownKeys = true` in Json config
 *   => the app doesn't crash when the API adds, removes or omits fields.
 * - kotlinx.serialization generates the (de)serializer at COMPILE time:
 *   no reflection (unlike Gson), Kotlin-aware defaults & nullability (Gson ignores both),
 *   and KMP-ready.
 */

@Serializable
data class LoginRequestDto(
    val username: String,
    val password: String,
    val expiresInMins: Int = 30,
)

@Serializable
data class LoginResponseDto(
    val id: Int,
    val username: String,
    val email: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val image: String? = null,
    val accessToken: String,
    val refreshToken: String = "",
)

@Serializable
data class UserDto(
    val id: Int,
    val username: String,
    val email: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val image: String? = null,
)

@Serializable
data class CartsResponseDto(
    val carts: List<CartDto> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class CartDto(
    val id: Int,
    val totalQuantity: Int = 0,
)

@Serializable
data class CategoryDto(
    val slug: String,
    val name: String,
)

@Serializable
data class ProductsResponseDto(
    val products: List<ProductDto> = emptyList(),
    val total: Int = 0,
    val skip: Int = 0,
    val limit: Int = 0,
)

@Serializable
data class ProductDto(
    val id: Int,
    val title: String = "",
    val description: String = "",
    val category: String = "",
    val price: Double = 0.0,
    val discountPercentage: Double = 0.0,
    val rating: Double = 0.0,
    val stock: Int = 0,
    val tags: List<String> = emptyList(),
    val brand: String? = null,
    val warrantyInformation: String? = null,
    val shippingInformation: String? = null,
    val availabilityStatus: String? = null,
    val returnPolicy: String? = null,
    val minimumOrderQuantity: Int? = null,
    val reviews: List<ReviewDto> = emptyList(),
    val images: List<String> = emptyList(),
    val thumbnail: String? = null,
)

@Serializable
data class ReviewDto(
    val rating: Int = 0,
    val comment: String = "",
    val date: String = "",
    val reviewerName: String = "",
)
