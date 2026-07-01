package com.yshah.alfred.di

import android.content.Context
import androidx.room.Room
import com.yshah.alfred.data.AlfredDatabase
import com.yshah.alfred.data.InteractionDao
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
    fun provideAlfredDatabase(@ApplicationContext context: Context): AlfredDatabase =
        Room.databaseBuilder(context, AlfredDatabase::class.java, "alfred.db").build()

    @Provides
    @Singleton
    fun provideInteractionDao(database: AlfredDatabase): InteractionDao = database.interactionDao()
}
