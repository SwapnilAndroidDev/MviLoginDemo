package com.swapnil.mvilogin.di

import com.swapnil.mvilogin.data.ConnectivityNetworkMonitor
import com.swapnil.mvilogin.data.InMemorySessionEvents
import com.swapnil.mvilogin.data.repository.AuthRepositoryImpl
import com.swapnil.mvilogin.data.repository.ProductRepositoryImpl
import com.swapnil.mvilogin.domain.repository.AuthRepository
import com.swapnil.mvilogin.domain.repository.NetworkMonitor
import com.swapnil.mvilogin.domain.repository.ProductRepository
import com.swapnil.mvilogin.domain.repository.SessionEvents
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * @Binds — "when someone asks for the interface, give them this implementation".
 * The implementations have @Inject constructors, so no construction code is needed.
 * Abstract method, no body -> Dagger generates less code than @Provides.
 *
 * Scoping (@Singleton) is declared on the implementation classes themselves.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds
    abstract fun bindProductRepository(impl: ProductRepositoryImpl): ProductRepository

    @Binds
    abstract fun bindNetworkMonitor(impl: ConnectivityNetworkMonitor): NetworkMonitor

    @Binds
    abstract fun bindSessionEvents(impl: InMemorySessionEvents): SessionEvents
}
