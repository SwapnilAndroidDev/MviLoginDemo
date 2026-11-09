package com.swapnil.mvilogin.domain.model

/**
 * ENUM WITH PROPERTIES — every strength level has the same shape (label + progress),
 * the set is fixed, and we can compare levels by ordinal (WEAK < STRONG).
 * Note: no Compose Color here — the domain layer stays UI-agnostic and unit-testable.
 */
enum class PasswordStrength(val label: String, val progress: Float) {
    EMPTY(label = "", progress = 0f),
    WEAK(label = "Weak", progress = 0.33f),
    MEDIUM(label = "Medium", progress = 0.66f),
    STRONG(label = "Strong", progress = 1f);

    companion object {
        /**
         * Single pass over the characters.
         * Time: O(n) where n = password length. Space: O(1) — four booleans, no lists,
         * no regex, no allocations (important because this runs on every keystroke).
         */
        fun of(password: String): PasswordStrength {
            if (password.isEmpty()) return EMPTY

            var hasUpper = false
            var hasLower = false
            var hasDigit = false
            var hasSymbol = false
            for (c in password) {
                when {
                    c.isUpperCase() -> hasUpper = true
                    c.isLowerCase() -> hasLower = true
                    c.isDigit() -> hasDigit = true
                    !c.isWhitespace() -> hasSymbol = true
                }
            }

            var score = 0
            if (hasUpper) score++
            if (hasLower) score++
            if (hasDigit) score++
            if (hasSymbol) score++
            if (password.length >= 8) score++
            if (password.length >= 12) score++

            return when {
                score >= 5 -> STRONG
                score >= 3 -> MEDIUM
                else -> WEAK
            }
        }
    }
}
