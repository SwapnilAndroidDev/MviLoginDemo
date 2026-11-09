package com.swapnil.mvilogin.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.swapnil.mvilogin.di.ApplicationScope
import com.swapnil.mvilogin.domain.repository.NetworkMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * callbackFlow  -> turns a callback API (ConnectivityManager) into a cold Flow.
 * awaitClose    -> unregisters the callback when collection stops (no leak).
 * stateIn       -> turns the cold flow into a HOT StateFlow shared by all screens,
 *                  so we register ONE system callback no matter how many collectors.
 * WhileSubscribed(5_000) -> keep the upstream alive 5 s after the last collector leaves.
 *                  A rotation takes < 5 s, so we don't unregister/re-register on every
 *                  config change, but we DO stop when the app is really in background.
 *
 * Uses @ApplicationScope (not viewModelScope) because this object is a @Singleton
 * that outlives any single screen.
 */
@Singleton
class ConnectivityNetworkMonitor @Inject constructor(
    @ApplicationContext context: Context, // Application context only -> no Activity leak
    @ApplicationScope scope: CoroutineScope,
) : NetworkMonitor {

    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

    override val isOnline: StateFlow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(true)
            }

            override fun onLost(network: Network) {
                trySend(false)
            }
        }
        trySend(connectivityManager.activeNetwork != null) // initial value
        connectivityManager.registerDefaultNetworkCallback(callback)
        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), initialValue = true)
}
