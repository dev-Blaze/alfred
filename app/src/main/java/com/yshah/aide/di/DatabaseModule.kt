package com.yshah.aide.di

import android.content.Context
import androidx.room.Room
import com.yshah.aide.data.AideDatabase
import com.yshah.aide.data.InteractionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideAideDatabase(@ApplicationContext context: Context): AideDatabase =
        Room.databaseBuilder(context, AideDatabase::class.java, "aide.db").build()

    @Provides
    @Singleton
    fun provideInteractionDao(database: AideDatabase): InteractionDao = database.interactionDao()
}
