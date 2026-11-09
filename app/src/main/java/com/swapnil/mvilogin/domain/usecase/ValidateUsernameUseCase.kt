package com.swapnil.mvilogin.domain.usecase

import com.swapnil.mvilogin.domain.model.UsernameError
import javax.inject.Inject

/**
 * DummyJSON logs in with a USERNAME (e.g. "emilys"), not an email.
 * Pure Kotlin — no Android classes — so it runs in plain JVM unit tests.
 *
 * `@Inject constructor()` with no dependencies: Dagger can build it without a @Module.
 */
class ValidateUsernameUseCase @Inject constructor() {

    operator fun invoke(username: String): UsernameError? {
        val value = username.trim()
        return when {
            value.isEmpty() -> UsernameError.EMPTY
            value.length < MIN_LENGTH -> UsernameError.TOO_SHORT
            !ALLOWED.matches(value) -> UsernameError.INVALID_CHARACTERS
            else -> null
        }
    }

    private companion object {
        const val MIN_LENGTH = 3

        // Compiled ONCE (companion = static). Building a Regex per keystroke would
        // recompile the pattern every time. Matching this pattern is O(n).
        val ALLOWED = Regex("^[A-Za-z0-9._-]+$")
    }
}
