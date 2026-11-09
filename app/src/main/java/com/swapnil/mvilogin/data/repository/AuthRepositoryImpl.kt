package com.swapnil.mvilogin.data.repository

import com.swapnil.mvilogin.data.TokenStore
import com.swapnil.mvilogin.data.network.DummyJsonApi
import com.swapnil.mvilogin.data.network.LoginRequestDto
import com.swapnil.mvilogin.data.network.mapToAppErrors
import com.swapnil.mvilogin.data.network.safeApiCall
import com.swapnil.mvilogin.data.network.toDomain
import com.swapnil.mvilogin.di.IoDispatcher
import com.swapnil.mvilogin.domain.model.AuthResult
import com.swapnil.mvilogin.domain.model.AuthToken
import com.swapnil.mvilogin.domain.model.Session
import com.swapnil.mvilogin.domain.model.UserProfile
import com.swapnil.mvilogin.domain.repository.AuthRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * @Singleton because it HOLDS STATE (the session). Without the scope, Login and Home
 * would each get a different instance and Home would never see the session.
 */
@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val api: DummyJsonApi,
    private val tokenStore: TokenStore,
    @IoDispatcher private val io: CoroutineDispatcher,
) : AuthRepository {

    private val _session = MutableStateFlow<Session?>(null)
    override val session: StateFlow<Session?> = _session.asStateFlow()

    override suspend fun login(username: String, password: String): AuthResult =
        safeApiCall(io, isLogin = true) {
            val response = api.login(LoginRequestDto(username = username, password = password))
            tokenStore.accessToken = response.accessToken
            AuthResult(token = AuthToken(response.accessToken), userId = response.id)
        }

    // flowOn(io): everything ABOVE runs on IO; the collector keeps its own dispatcher.
    override fun currentUser(): Flow<UserProfile> =
        flow { emit(api.me().toDomain()) }
            .mapToAppErrors()
            .flowOn(io)

    override fun cartItemCount(userId: Int): Flow<Int> =
        flow { emit(api.cartsOfUser(userId).carts.sumOf { it.totalQuantity }) }
            .mapToAppErrors()
            .flowOn(io)

    override fun saveSession(session: Session) {
        _session.value = session
    }

    override fun logout() {
        tokenStore.clear()
        _session.value = null
    }
}
