package com.swapnil.mvilogin.ui.home

import com.swapnil.mvilogin.MainDispatcherRule
import com.swapnil.mvilogin.data.InMemorySessionEvents
import com.swapnil.mvilogin.domain.model.AppError
import com.swapnil.mvilogin.domain.model.ProductQuery
import com.swapnil.mvilogin.fakes.FakeAuthRepository
import com.swapnil.mvilogin.fakes.FakeProductRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val products = FakeProductRepository()
    private val auth = FakeAuthRepository(io = mainRule.testDispatcher)
    private val sessionEvents = InMemorySessionEvents()

    private fun createViewModel() = HomeViewModel(products, auth, sessionEvents)

    @Test
    fun `loads first page and categories on start`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        with(vm.state.value) {
            assertEquals(HomeViewModel.PAGE_SIZE, this.products.size)
            assertEquals(2, categories.size)
            assertFalse(isLoading)
            assertFalse(endReached)
        }
    }

    @Test
    fun `load more appends pages until the end, then stops`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onIntent(HomeIntent.LoadMore); advanceUntilIdle()
        assertEquals(40, vm.state.value.products.size)

        vm.onIntent(HomeIntent.LoadMore); advanceUntilIdle()
        assertEquals(45, vm.state.value.products.size)
        assertTrue(vm.state.value.endReached)

        val requestsBefore = products.requestedQueries.size
        vm.onIntent(HomeIntent.LoadMore); advanceUntilIdle()
        assertEquals(requestsBefore, products.requestedQueries.size) // guarded: no extra call
    }

    @Test
    fun `typing is debounced and only the final query hits the API`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()
        products.requestedQueries.clear()

        vm.onIntent(HomeIntent.QueryChanged("Pro"))
        advanceTimeBy(100)
        vm.onIntent(HomeIntent.QueryChanged("Product 1"))
        advanceUntilIdle()

        assertEquals(listOf(ProductQuery(text = "Product 1")), products.requestedQueries)
        assertTrue(vm.state.value.products.all { it.title.contains("Product 1") })
    }

    @Test
    fun `flatMapLatest cancels the stale request when the category changes quickly`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onIntent(HomeIntent.CategorySelected("cat-a"))
        runCurrent()                                  // cat-a request started (500 ms latency)
        advanceTimeBy(FakeProductRepository.LATENCY_MS / 2)
        vm.onIntent(HomeIntent.CategorySelected("cat-b"))
        advanceUntilIdle()

        // cat-a's response never landed: every row is from cat-b.
        assertTrue(vm.state.value.products.isNotEmpty())
        assertTrue(vm.state.value.products.all { it.category == "cat-b" })
    }

    @Test
    fun `error shows full screen state and retry recovers`() = runTest {
        products.failNext = true
        val vm = createViewModel()
        advanceUntilIdle()
        assertEquals(AppError.Network, vm.state.value.error)
        assertTrue(vm.state.value.showFullScreenError)

        vm.onIntent(HomeIntent.Retry)
        advanceUntilIdle()
        assertEquals(null, vm.state.value.error)
        assertEquals(HomeViewModel.PAGE_SIZE, vm.state.value.products.size)
    }

    @Test
    fun `session expired broadcast logs out and navigates to login`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onIntent(HomeIntent.SimulateSessionExpiry)
        advanceUntilIdle()

        assertEquals(1, auth.logoutCalls)
        assertEquals(HomeEffect.NavigateToLogin(sessionExpired = true), vm.effects.first())
    }
}
