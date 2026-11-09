package com.swapnil.mvilogin.ui.login

import com.swapnil.mvilogin.domain.model.AppError
import com.swapnil.mvilogin.domain.model.SessionEvent
import com.swapnil.mvilogin.domain.model.UsernameError

/*
 * Mapping domain types -> user-facing text lives in the UI layer (shared by all screens).
 * Every `when` below is EXHAUSTIVE with no `else` branch: add a new AppError variant
 * and the compiler points you here. That's the main payoff of sealed/enum types.
 *
 * Production: return @StringRes ids or a UiText wrapper so text is localisable.
 */

fun UsernameError.toMessage(): String = when (this) {
    UsernameError.EMPTY -> "Username is required"
    UsernameError.TOO_SHORT -> "Username must be at least 3 characters"
    UsernameError.INVALID_CHARACTERS -> "Use letters, numbers, dot, dash or underscore"
}

fun AppError.toMessage(): String = when (this) {
    AppError.InvalidCredentials -> "Incorrect username or password"
    AppError.SessionExpired -> "Your session expired. Please sign in again."
    AppError.NotFound -> "We couldn't find that item"
    AppError.Network -> "Network problem. Check your connection and try again"
    is AppError.Server -> "Server error ($code). Please try again later"
    is AppError.Unknown -> "Something went wrong. Please try again"
}

fun SessionEvent.toMessage(): String = when (this) {
    SessionEvent.Expired -> "Your session expired. Please sign in again."
    is SessionEvent.LoggedInElsewhere -> "You signed in on $deviceName"
}
