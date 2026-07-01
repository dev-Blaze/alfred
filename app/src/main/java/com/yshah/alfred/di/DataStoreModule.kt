package com.yshah.alfred.di

import android.content.Context
import com.yshah.alfred.prefs.ModePreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {
    @Provides
    @Singleton
    fun provideModePreferences(@ApplicationContext context: Context): ModePreferences =
        ModePreferences(context)
}
