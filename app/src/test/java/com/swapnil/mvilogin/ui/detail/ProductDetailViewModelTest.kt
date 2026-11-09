package com.swapnil.mvilogin.ui.detail

import androidx.lifecycle.SavedStateHandle
import com.swapnil.mvilogin.MainDispatcherRule
import com.swapnil.mvilogin.domain.model.AppError
import com.swapnil.mvilogin.fakes.FakeProductRepository
import com.swapnil.mvilogin.ui.navigation.ProductDetailDestination
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProductDetailViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private fun createViewModel(id: Int) = ProductDetailViewModel(
        savedStateHandle = SavedStateHandle(mapOf(ProductDetailDestination.ARG_ID to id)),
        repository = FakeProductRepository(),
    )

    @Test
    fun `loads content for the id from the nav arguments`() = runTest {
        val vm = createViewModel(id = 7)
        // stateIn(WhileSubscribed) only runs upstream while someone collects -> subscribe in the test.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }

        assertEquals(ProductDetailState.Loading, vm.state.value)
        advanceUntilIdle()

        val content = vm.state.value
        assertTrue(content is ProductDetailState.Content)
        assertEquals(7, (content as ProductDetailState.Content).product.id)
    }

    @Test
    fun `missing product shows NotFound error`() = runTest {
        val vm = createViewModel(id = FakeProductRepository.MISSING_ID)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        advanceUntilIdle()

        assertEquals(ProductDetailState.Error(AppError.NotFound), vm.state.value)
    }
}
