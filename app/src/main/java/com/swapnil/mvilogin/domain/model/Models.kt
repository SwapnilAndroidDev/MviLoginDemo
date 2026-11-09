package com.swapnil.mvilogin.domain.model

/**
 * VALUE CLASS — type safety with (almost) zero runtime cost.
 * At runtime it is just a String; the compiler stops you from passing a username
 * where a token is expected. It gets boxed only when used as nullable, generic or Any.
 */
@JvmInline
value class AuthToken(val value: String)

/** What a successful POST /auth/login gives the domain layer. */
data class AuthResult(val token: AuthToken, val userId: Int)

data class UserProfile(
    val id: Int,
    val username: String,
    val firstName: String,
    val lastName: String,
    val email: String,
    val imageUrl: String?,
) {
    val fullName: String get() = "$firstName $lastName".trim()
}

data class Session(
    val token: AuthToken,
    val profile: UserProfile,
    val cartItemCount: Int,
)

/**
 * SEALED CLASS — a closed set of error types where each variant can carry
 * DIFFERENT data (Server has an HTTP code, Unknown has a message).
 * An enum can't do that: every enum constant is a single instance with the same shape.
 * `when (error)` over a sealed type is exhaustive — add a variant and the compiler
 * shows every place that must handle it.
 */
sealed class AppError {
    data object InvalidCredentials : AppError()
    data object SessionExpired : AppError()
    data object NotFound : AppError()
    data object Network : AppError()
    data class Server(val code: Int) : AppError()
    data class Unknown(val message: String?) : AppError()
}

/** Carries a typed [AppError] through exceptions (suspend calls and Flow errors). */
class AppException(val error: AppError) : Exception(error.toString())

/**
 * ENUM — a fixed set of constants with identical shape and no per-instance data.
 * Cheap (singletons), has `entries`, `ordinal`, natural ordering, and works in `when`.
 */
enum class UsernameError { EMPTY, TOO_SHORT, INVALID_CHARACTERS }

/**
 * SEALED INTERFACE — like a sealed class but with no constructor/state of its own,
 * and a type can implement several sealed interfaces. App-wide session events,
 * broadcast via a SharedFlow (see SessionEvents). The OkHttp AuthInterceptor
 * publishes Expired when the API answers 401.
 */
sealed interface SessionEvent {
    data object Expired : SessionEvent
    data class LoggedInElsewhere(val deviceName: String) : SessionEvent
}
