package com.freemax.app.core.di

import com.freemax.api.MaxClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideMaxClient(): MaxClient {
        return MaxClient()
    }
}
