package com.swapnil.mvilogin.data.network

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * DummyJSON (https://dummyjson.com) — free, open-source fake REST API with real auth
 * (JWT access/refresh tokens), users, carts, and ~190 products with images and reviews.
 *
 * `suspend` Retrofit functions: Retrofit enqueues on OkHttp's own thread pool and resumes
 * the coroutine with the result, so the call itself is main-safe and cancellable
 * (cancelling the coroutine cancels the HTTP call).
 */
interface DummyJsonApi {

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequestDto): LoginResponseDto

    @Authenticated
    @GET("auth/me")
    suspend fun me(): UserDto

    @Authenticated
    @GET("carts/user/{userId}")
    suspend fun cartsOfUser(@Path("userId") userId: Int): CartsResponseDto

    @GET("products/categories")
    suspend fun categories(): List<CategoryDto>

    @GET("products")
    suspend fun products(
        @Query("limit") limit: Int,
        @Query("skip") skip: Int,
        @Query("select") select: String,
    ): ProductsResponseDto

    @GET("products/search")
    suspend fun search(
        @Query("q") query: String,
        @Query("limit") limit: Int,
        @Query("skip") skip: Int,
        @Query("select") select: String,
    ): ProductsResponseDto

    @GET("products/category/{slug}")
    suspend fun productsByCategory(
        @Path("slug") slug: String,
        @Query("limit") limit: Int,
        @Query("skip") skip: Int,
        @Query("select") select: String,
    ): ProductsResponseDto

    @GET("products/{id}")
    suspend fun product(@Path("id") id: Int): ProductDto

    companion object {
        const val BASE_URL = "https://dummyjson.com/"

        /**
         * List rows only need these fields. `select` makes the server send ~10x less JSON
         * than the full product (no description, reviews, images array, meta...):
         * less bandwidth, less parsing, less memory.
         */
        const val LIST_FIELDS = "id,title,price,discountPercentage,rating,thumbnail,brand,category"
    }
}

/** Marks endpoints that need the bearer token. Read by AuthInterceptor via Retrofit's Invocation tag. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Authenticated
