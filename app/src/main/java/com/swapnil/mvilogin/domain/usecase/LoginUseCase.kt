package com.swapnil.mvilogin.domain.usecase

import com.swapnil.mvilogin.domain.model.Session
import com.swapnil.mvilogin.domain.repository.AuthRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.zip
import javax.inject.Inject

/**
 * Orchestrates:  POST /auth/login  ->  ( GET /auth/me  ZIP  GET /carts/user/{id} )  ->  Session
 *
 * `flow { }` is a COLD flow: nothing runs until collected, and each collector
 * (including each `retry`) gets a fresh execution. That's what "do a login" should be.
 */
class LoginUseCase @Inject constructor(
    private val repository: AuthRepository,
) {
    operator fun invoke(username: String, password: String): Flow<Session> = flow {
        // Step 1: one-shot suspend call. Sequential on purpose — we need the token first.
        val auth = repository.login(username.trim(), password)

        // Step 2: ZIP — the home header needs BOTH the profile and the cart count.
        // zip collects both flows CONCURRENTLY: total time = max(me, carts), not the sum.
        // It pairs the 1st item of each, and completes when the shorter flow completes.
        // (The login response also contains the user; calling /auth/me is how real apps
        // validate the fresh token and load the full profile.)
        emitAll(
            repository.currentUser().zip(repository.cartItemCount(auth.userId)) { profile, cartCount ->
                Session(token = auth.token, profile = profile, cartItemCount = cartCount)
            }
        )
    }.onEach { session -> repository.saveSession(session) }
}
