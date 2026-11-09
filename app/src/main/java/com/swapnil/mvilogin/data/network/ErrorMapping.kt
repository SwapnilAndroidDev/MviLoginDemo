package com.swapnil.mvilogin.data.network

import com.swapnil.mvilogin.domain.model.AppError
import com.swapnil.mvilogin.domain.model.AppException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException

/** Translate transport/HTTP failures into the domain's sealed AppError. */
fun Throwable.toAppError(isLogin: Boolean = false): AppError = when (this) {
    is AppException -> error
    is HttpException -> when (code()) {
        400, 401 -> if (isLogin) AppError.InvalidCredentials else AppError.SessionExpired
        404 -> AppError.NotFound
        else -> AppError.Server(code())
    }
    is IOException -> AppError.Network // no connection, timeout, DNS...
    else -> AppError.Unknown(message)
}

/**
 * Wraps a one-shot call: switches to [io] and converts failures to AppException.
 *
 * CancellationException is RE-THROWN first. Catching it (e.g. with a blanket
 * `catch (e: Exception)` or `runCatching`) would break structured concurrency:
 * the coroutine would keep running after its scope was cancelled.
 *
 * (Retrofit suspend calls are already main-safe; withContext(io) keeps the rule uniform
 * and moves the DTO -> domain mapping off the main thread too.)
 */
suspend fun <T> safeApiCall(
    io: CoroutineDispatcher,
    isLogin: Boolean = false,
    block: suspend () -> T,
): T = withContext(io) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw AppException(e.toAppError(isLogin))
    }
}

/** Flow equivalent: `catch` sees upstream errors only and never swallows cancellation. */
fun <T> Flow<T>.mapToAppErrors(): Flow<T> = catch { throw AppException(it.toAppError()) }
