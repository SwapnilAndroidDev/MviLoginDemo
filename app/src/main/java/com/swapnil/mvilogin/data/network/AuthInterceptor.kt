package com.swapnil.mvilogin.data.network

import com.swapnil.mvilogin.data.TokenStore
import com.swapnil.mvilogin.domain.model.SessionEvent
import com.swapnil.mvilogin.domain.repository.SessionEvents
import okhttp3.Interceptor
import okhttp3.Response
import retrofit2.Invocation
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adds "Authorization: Bearer <token>" ONLY to endpoints annotated with @Authenticated.
 * Retrofit attaches an `Invocation` tag to each request, which tells us which interface
 * method created it — so auth is declared next to the endpoint, not in a URL list here.
 *
 * On 401 it clears the token and broadcasts SessionEvent.Expired on the SharedFlow bus.
 * Interceptors run on OkHttp threads (not coroutines) -> tryPublish (non-suspending).
 *
 * Production: use an OkHttp `Authenticator` to call POST /auth/refresh with the refresh
 * token and retry once before declaring the session expired.
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val tokenStore: TokenStore,
    private val sessionEvents: SessionEvents,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val needsAuth = original.tag(Invocation::class.java)
            ?.method()
            ?.isAnnotationPresent(Authenticated::class.java) == true
        val token = tokenStore.accessToken

        val request = if (needsAuth && token != null) {
            original.newBuilder().header("Authorization", "Bearer $token").build()
        } else {
            original
        }

        val response = chain.proceed(request)
        if (needsAuth && response.code == 401) {
            tokenStore.clear()
            sessionEvents.tryPublish(SessionEvent.Expired)
        }
        return response
    }
}
