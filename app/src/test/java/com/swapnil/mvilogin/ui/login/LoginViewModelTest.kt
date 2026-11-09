package com.swapnil.mvilogin.ui.login

import androidx.lifecycle.SavedStateHandle
import com.swapnil.mvilogin.MainDispatcherRule
import com.swapnil.mvilogin.data.InMemorySessionEvents
import com.swapnil.mvilogin.domain.model.AppError
import com.swapnil.mvilogin.domain.model.UsernameError
import com.swapnil.mvilogin.domain.usecase.LoginUseCase
import com.swapnil.mvilogin.domain.usecase.ValidateUsernameUseCase
import com.swapnil.mvilogin.fakes.FakeAuthRepository
import com.swapnil.mvilogin.fakes.FakeNetworkMonitor
import com.swapnil.mvilogin.ui.navigation.LoginDestination
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val network = FakeNetworkMonitor()

    // Manual construction = the payoff of constructor injection: no Hilt needed in unit tests.
    private fun createViewModel(savedState: SavedStateHandle = SavedStateHandle()) = LoginViewModel(
        savedStateHandle = savedState,
        login = LoginUseCase(FakeAuthRepository(io = mainRule.testDispatcher)),
        validateUsername = ValidateUsernameUseCase(),
        sessionEvents = InMemorySessionEvents(),
        networkMonitor = network,
    )

    @Test
    fun `demo credentials go Loading then Success, clear the password and navigate`() = runTest {
        val vm = createViewModel()
        vm.onIntent(LoginIntent.FillDemoCredentials)
        vm.onIntent(LoginIntent.Submit)

        runCurrent()
        assertEquals(LoginStatus.Loading, vm.state.value.status)

        advanceUntilIdle()
        assertEquals(LoginStatus.Success("Emily"), vm.state.value.status)
        assertEquals("", vm.state.value.password)
        assertEquals(LoginEffect.NavigateToHome, vm.effects.first())
    }

    @Test
    fun `wrong password ends in InvalidCredentials`() = runTest {
        val vm = createViewModel()
        vm.onIntent(LoginIntent.UsernameChanged(DemoCredentials.USERNAME))
        vm.onIntent(LoginIntent.PasswordChanged("wrong-pass"))
        vm.onIntent(LoginIntent.Submit)
        advanceUntilIdle()

        assertEquals(LoginStatus.Error(AppError.InvalidCredentials), vm.state.value.status)
    }

    @Test
    fun `network errors are retried twice with backoff then surfaced`() = runTest {
        val vm = createViewModel()
        vm.onIntent(LoginIntent.UsernameChanged(FakeAuthRepository.FLAKY_USER))
        vm.onIntent(LoginIntent.PasswordChanged(DemoCredentials.PASSWORD))
        vm.onIntent(LoginIntent.Submit)
        advanceUntilIdle()

        assertEquals(LoginStatus.Error(AppError.Network), vm.state.value.status)
        // 3 attempts x 1200 ms + 2 backoffs x 1000 ms = 5600 ms of virtual time
        assertEquals(5_600L, currentTime)
    }

    @Test
    fun `username error appears only after the debounce window`() = runTest {
        val vm = createViewModel()
        vm.onIntent(LoginIntent.UsernameChanged("em"))
        runCurrent()

        advanceTimeBy(LoginViewModel.USERNAME_DEBOUNCE_MS - 1)
        assertNull(vm.state.value.usernameError)

        advanceTimeBy(2)
        assertEquals(UsernameError.TOO_SHORT, vm.state.value.usernameError)
    }

    @Test
    fun `going offline flows through merge into state and disables the form`() = runTest {
        val vm = createViewModel()
        vm.onIntent(LoginIntent.FillDemoCredentials)
        advanceUntilIdle()

        network.online.value = false
        advanceUntilIdle()

        assertFalse(vm.state.value.isOnline)
        assertFalse(vm.state.value.isFormValid) // combine re-evaluated with online = false
    }

    @Test
    fun `sessionExpired nav argument shows a message once`() = runTest {
        val vm = createViewModel(SavedStateHandle(mapOf(LoginDestination.ARG_SESSION_EXPIRED to true)))
        assertEquals(LoginEffect.ShowMessage(AppError.SessionExpired.toMessage()), vm.effects.first())
    }
}
