package com.swapnil.mvilogin.fakes

import com.swapnil.mvilogin.domain.model.AppError
import com.swapnil.mvilogin.domain.model.AppException
import com.swapnil.mvilogin.domain.model.AuthResult
import com.swapnil.mvilogin.domain.model.AuthToken
import com.swapnil.mvilogin.domain.model.Category
import com.swapnil.mvilogin.domain.model.Page
import com.swapnil.mvilogin.domain.model.ProductDetail
import com.swapnil.mvilogin.domain.model.ProductQuery
import com.swapnil.mvilogin.domain.model.ProductSummary
import com.swapnil.mvilogin.domain.model.Session
import com.swapnil.mvilogin.domain.model.StockStatus
import com.swapnil.mvilogin.domain.model.UserProfile
import com.swapnil.mvilogin.domain.repository.AuthRepository
import com.swapnil.mvilogin.domain.repository.NetworkMonitor
import com.swapnil.mvilogin.domain.repository.ProductRepository
import com.swapnil.mvilogin.ui.login.DemoCredentials
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/*
 * Fakes (hand-written implementations) instead of mocks: they behave like the real thing,
 * survive refactors, and need no mocking library. Delays + injected dispatcher let tests
 * assert exact timings on VIRTUAL time.
 */

class FakeNetworkMonitor : NetworkMonitor {
    val online = MutableStateFlow(true)
    override val isOnline: Flow<Boolean> = online
}

class FakeAuthRepository(private val io: CoroutineDispatcher) : AuthRepository {
    private val _session = MutableStateFlow<Session?>(null)
    override val session: StateFlow<Session?> = _session.asStateFlow()
    var logoutCalls = 0
        private set

    override suspend fun login(username: String, password: String): AuthResult = withContext(io) {
        delay(LOGIN_MS)
        when {
            username == FLAKY_USER -> throw AppException(AppError.Network)
            username != DemoCredentials.USERNAME || password != DemoCredentials.PASSWORD ->
                throw AppException(AppError.InvalidCredentials)
            else -> AuthResult(AuthToken("token"), userId = 1)
        }
    }

    override fun currentUser(): Flow<UserProfile> = flow {
        delay(ME_MS)
        emit(UserProfile(1, "emilys", "Emily", "Johnson", "emily@x.dummyjson.com", null))
    }.flowOn(io)

    override fun cartItemCount(userId: Int): Flow<Int> = flow {
        delay(CARTS_MS)
        emit(3)
    }.flowOn(io)

    override fun saveSession(session: Session) {
        _session.value = session
    }

    override fun logout() {
        logoutCalls++
        _session.value = null
    }

    companion object {
        const val FLAKY_USER = "flaky"
        const val LOGIN_MS = 1_200L
        const val ME_MS = 800L
        const val CARTS_MS = 600L
    }
}

/** 45 products: odd ids in "cat-a", even ids in "cat-b". */
class FakeProductRepository : ProductRepository {
    private val all = (1..45).map { i ->
        ProductSummary(i, "Product $i", "Brand", if (i % 2 == 1) "cat-a" else "cat-b", 10.0 * i, 5.0, 4.0, null)
    }
    val requestedQueries = mutableListOf<ProductQuery>()
    var failNext = false

    override suspend fun categories() = listOf(Category("cat-a", "Cat A"), Category("cat-b", "Cat B"))

    override suspend fun products(query: ProductQuery, skip: Int, limit: Int): Page<ProductSummary> {
        requestedQueries += query
        delay(LATENCY_MS)
        if (failNext) {
            failNext = false
            throw AppException(AppError.Network)
        }
        val filtered = all.filter {
            (query.text.isBlank() || it.title.contains(query.text, ignoreCase = true)) &&
                (query.categorySlug == null || it.category == query.categorySlug)
        }
        return Page(filtered.drop(skip).take(limit), total = filtered.size, skip = skip)
    }

    override suspend fun product(id: Int): ProductDetail {
        delay(LATENCY_MS)
        if (id == MISSING_ID) throw AppException(AppError.NotFound)
        val s = all.first { it.id == id }
        return ProductDetail(
            s.id, s.title, "desc", s.brand, s.category, s.price, s.discountPercentage, s.rating, 20,
            StockStatus.IN_STOCK, emptyList(), emptyList(), null, null, null, null, emptyList(),
        )
    }

    companion object {
        const val LATENCY_MS = 500L
        const val MISSING_ID = 999
    }
}
