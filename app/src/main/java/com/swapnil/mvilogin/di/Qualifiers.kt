package com.swapnil.mvilogin.di

import javax.inject.Qualifier

/**
 * QUALIFIERS — Dagger resolves by TYPE. We have several CoroutineDispatchers
 * (IO, Default, Main) and several CoroutineScopes, so a qualifier tells Dagger
 * WHICH one we mean. Without it you'd get a "bound multiple times" compile error.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
