package com.swapnil.mvilogin.domain

import com.swapnil.mvilogin.domain.model.PasswordStrength
import com.swapnil.mvilogin.domain.model.StockStatus
import com.swapnil.mvilogin.domain.model.UsernameError
import com.swapnil.mvilogin.domain.model.discountedPrice
import com.swapnil.mvilogin.domain.usecase.LoginUseCase
import com.swapnil.mvilogin.domain.usecase.ValidateUsernameUseCase
import com.swapnil.mvilogin.fakes.FakeAuthRepository
import com.swapnil.mvilogin.ui.login.DemoCredentials
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginUseCaseTest {

    @Test
    fun `zip fetches profile and cart count in parallel and saves the session`() = runTest {
        val repo = FakeAuthRepository(io = StandardTestDispatcher(testScheduler))

        val session = LoginUseCase(repo)(DemoCredentials.USERNAME, DemoCredentials.PASSWORD).first()

        assertEquals("Emily", session.profile.firstName)
        assertEquals(3, session.cartItemCount)
        assertEquals(session, repo.session.value)
        // login 1200 + max(me 800, carts 600) = 2000 ms. Sequential would be 2600 ms.
        assertEquals(2_000L, currentTime)
    }

    @Test
    fun `username validation`() {
        val validate = ValidateUsernameUseCase()
        assertEquals(UsernameError.EMPTY, validate("  "))
        assertEquals(UsernameError.TOO_SHORT, validate("em"))
        assertEquals(UsernameError.INVALID_CHARACTERS, validate("emily s"))
        assertNull(validate("emilys"))
    }

    @Test
    fun `password strength levels`() {
        assertEquals(PasswordStrength.EMPTY, PasswordStrength.of(""))
        assertEquals(PasswordStrength.WEAK, PasswordStrength.of("abc"))
        assertEquals(PasswordStrength.MEDIUM, PasswordStrength.of("abcdefg1"))
        assertEquals(PasswordStrength.STRONG, PasswordStrength.of("Password@123"))
    }

    @Test
    fun `stock status parsing and discount math`() {
        assertEquals(StockStatus.LOW_STOCK, StockStatus.from("Low Stock", 3))
        assertEquals(StockStatus.OUT_OF_STOCK, StockStatus.from(null, 0))
        assertEquals(StockStatus.IN_STOCK, StockStatus.from("something new", 50))
        assertEquals(90.0, discountedPrice(100.0, 10.0), 0.0001)
    }
}
