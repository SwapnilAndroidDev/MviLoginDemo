package com.swapnil.mvilogin.data

import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory token holder.
 *
 * @Volatile: written on a coroutine (IO thread) and read on OkHttp's threads.
 * Volatile guarantees every thread sees the latest write (visibility). A single
 * reference read/write is already atomic, so no lock is needed here.
 *
 * Production: persist with DataStore and encrypt with an Android Keystore key so the
 * user stays signed in after process death — never in plain SharedPreferences.
 */
@Singleton
class TokenStore @Inject constructor() {
    @Volatile
    var accessToken: String? = null

    fun clear() {
        accessToken = null
    }
}
