package com.swapnil.mvilogin.domain.repository

import com.swapnil.mvilogin.domain.model.AuthResult
import com.swapnil.mvilogin.domain.model.Category
import com.swapnil.mvilogin.domain.model.Page
import com.swapnil.mvilogin.domain.model.ProductDetail
import com.swapnil.mvilogin.domain.model.ProductQuery
import com.swapnil.mvilogin.domain.model.ProductSummary
import com.swapnil.mvilogin.domain.model.Session
import com.swapnil.mvilogin.domain.model.SessionEvent
import com.swapnil.mvilogin.domain.model.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Interfaces live in the domain layer; implementations live in data/.
 * ViewModels depend only on these abstractions (Dependency Inversion):
 * unit tests swap in fakes, Hilt decides the real binding.
 *
 * Rule of thumb:
 *  - ONE value, then done  -> `suspend fun`
 *  - MANY values over time -> `Flow`
 * All functions throw/emit AppException on failure and are MAIN-SAFE.
 */
interface AuthRepository {
    /** Current signed-in session (null = signed out). StateFlow: always has a value. */
    val session: StateFlow<Session?>

    /** POST /auth/login — one-shot. Stores the access token for authenticated calls. */
    suspend fun login(username: String, password: String): AuthResult

    /** GET /auth/me (bearer token). A Flow so the use case can ZIP it with the cart call. */
    fun currentUser(): Flow<UserProfile>

    /** GET /carts/user/{id} -> total quantity of items in the user's carts. */
    fun cartItemCount(userId: Int): Flow<Int>

    fun saveSession(session: Session)
    fun logout()
}

interface ProductRepository {
    /** GET /products/categories — cached in memory after the first call. */
    suspend fun categories(): List<Category>

    /** GET /products | /products/search?q= | /products/category/{slug} with limit & skip. */
    suspend fun products(query: ProductQuery, skip: Int, limit: Int): Page<ProductSummary>

    /** GET /products/{id} */
    suspend fun product(id: Int): ProductDetail
}

/** A stream of connectivity changes (true = online). */
interface NetworkMonitor {
    val isOnline: Flow<Boolean>
}

/**
 * App-wide event bus. The OkHttp interceptor publishes `Expired` on a 401 and any
 * screen that cares listens. Many publishers, many subscribers -> SharedFlow.
 */
interface SessionEvents {
    val events: SharedFlow<SessionEvent>
    suspend fun publish(event: SessionEvent)

    /** Non-suspending variant for callers that aren't coroutines (OkHttp interceptor thread). */
    fun tryPublish(event: SessionEvent): Boolean
}
