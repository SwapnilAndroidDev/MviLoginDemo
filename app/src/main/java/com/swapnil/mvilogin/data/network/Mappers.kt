package com.swapnil.mvilogin.data.network

import com.swapnil.mvilogin.domain.model.Category
import com.swapnil.mvilogin.domain.model.ProductDetail
import com.swapnil.mvilogin.domain.model.ProductSummary
import com.swapnil.mvilogin.domain.model.Review
import com.swapnil.mvilogin.domain.model.StockStatus
import com.swapnil.mvilogin.domain.model.UserProfile

/* DTO -> domain. Each mapper is O(fields); list mappers are O(n). Kept as pure extension functions. */

fun UserDto.toDomain() = UserProfile(
    id = id,
    username = username,
    firstName = firstName,
    lastName = lastName,
    email = email,
    imageUrl = image,
)

fun CategoryDto.toDomain() = Category(slug = slug, name = name)

fun ProductDto.toSummary() = ProductSummary(
    id = id,
    title = title,
    brand = brand,
    category = category,
    price = price,
    discountPercentage = discountPercentage,
    rating = rating,
    thumbnailUrl = thumbnail,
)

fun ProductDto.toDetail() = ProductDetail(
    id = id,
    title = title,
    description = description,
    brand = brand,
    category = category,
    price = price,
    discountPercentage = discountPercentage,
    rating = rating,
    stock = stock,
    stockStatus = StockStatus.from(availabilityStatus, stock),
    tags = tags,
    images = images.ifEmpty { listOfNotNull(thumbnail) },
    warranty = warrantyInformation,
    shipping = shippingInformation,
    returnPolicy = returnPolicy,
    minimumOrderQuantity = minimumOrderQuantity,
    reviews = reviews.map { Review(it.rating, it.comment, it.reviewerName, it.date.take(10)) },
)
