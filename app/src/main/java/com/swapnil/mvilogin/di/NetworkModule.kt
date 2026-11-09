package com.swapnil.mvilogin.di

import com.swapnil.mvilogin.BuildConfig
import com.swapnil.mvilogin.data.network.AuthInterceptor
import com.swapnil.mvilogin.data.network.DummyJsonApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * @Provides because Json, OkHttpClient and Retrofit are third-party types built with
 * builders — Dagger can't call their constructors itself.
 *
 * ONE OkHttpClient for the whole app (@Singleton): each client owns a connection pool
 * and thread pool. Creating several wastes memory and loses HTTP/2 connection reuse.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true  // API adds a field -> no crash
        coerceInputValues = true  // API sends null for a non-null field -> use the default
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(authInterceptor: AuthInterceptor): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
                    redactHeader("Authorization") // never log tokens
                }
            )
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json): Retrofit =
        Retrofit.Builder()
            .baseUrl(DummyJsonApi.BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun provideDummyJsonApi(retrofit: Retrofit): DummyJsonApi = retrofit.create(DummyJsonApi::class.java)
}
