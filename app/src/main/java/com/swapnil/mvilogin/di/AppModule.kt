package com.swapnil.mvilogin.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * @Provides — use when YOU must write the construction code:
 * third-party types (Dispatchers, Retrofit, OkHttp, Room) or anything without @Inject constructor.
 *
 * `object` module -> Dagger calls the static methods directly, no module instance allocated.
 */
@Module
@InstallIn(SingletonComponent::class) // lives as long as the Application
object AppModule {

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    /**
     * An application-wide scope for work that must OUTLIVE a screen
     * (shared StateFlows via stateIn, analytics flush, cache writes).
     * SupervisorJob: one failing child does not cancel the others or the scope.
     * Prefer this over GlobalScope: it's injectable, replaceable in tests, and explicit.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
