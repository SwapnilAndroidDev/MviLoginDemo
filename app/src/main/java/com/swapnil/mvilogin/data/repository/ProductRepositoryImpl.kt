package com.swapnil.mvilogin.data.repository

import com.swapnil.mvilogin.data.network.DummyJsonApi
import com.swapnil.mvilogin.data.network.safeApiCall
import com.swapnil.mvilogin.data.network.toDetail
import com.swapnil.mvilogin.data.network.toDomain
import com.swapnil.mvilogin.data.network.toSummary
import com.swapnil.mvilogin.di.IoDispatcher
import com.swapnil.mvilogin.domain.model.Category
import com.swapnil.mvilogin.domain.model.Page
import com.swapnil.mvilogin.domain.model.ProductDetail
import com.swapnil.mvilogin.domain.model.ProductQuery
import com.swapnil.mvilogin.domain.model.ProductSummary
import com.swapnil.mvilogin.domain.repository.ProductRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProductRepositoryImpl @Inject constructor(
    private val api: DummyJsonApi,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ProductRepository {

    /**
     * Categories almost never change -> cache in memory.
     * Mutex (not `synchronized`): it SUSPENDS instead of blocking a thread, so two
     * concurrent callers make ONE network call; the second waits and reads the cache.
     * Time after first load: O(1). Space: O(number of categories).
     */
    private val categoriesMutex = Mutex()
    private var cachedCategories: List<Category>? = null

    override suspend fun categories(): List<Category> = categoriesMutex.withLock {
        cachedCategories ?: safeApiCall(io) { api.categories().map { it.toDomain() } }
            .also { cachedCategories = it }
    }

    override suspend fun products(query: ProductQuery, skip: Int, limit: Int): Page<ProductSummary> =
        safeApiCall(io) {
            val fields = DummyJsonApi.LIST_FIELDS
            val response = when {
                query.text.isNotBlank() -> api.search(query.text, limit, skip, fields)
                query.categorySlug != null -> api.productsByCategory(query.categorySlug, limit, skip, fields)
                else -> api.products(limit, skip, fields)
            }
            Page(items = response.products.map { it.toSummary() }, total = response.total, skip = skip)
        }

    override suspend fun product(id: Int): ProductDetail =
        safeApiCall(io) { api.product(id).toDetail() }
}
