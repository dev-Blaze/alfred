package com.yshah.aide.di

import android.content.Context
import com.yshah.aide.capture.AndroidTtsController
import com.yshah.aide.capture.SpeechCaptureController
import com.yshah.aide.capture.SpeechRecognizerCaptureController
import com.yshah.aide.capture.TtsController
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CaptureModule {
    @Provides
    @Singleton
    fun provideSpeechCaptureController(@ApplicationContext context: Context): SpeechCaptureController =
        SpeechRecognizerCaptureController(context)

    @Provides
    @Singleton
    fun provideTtsController(@ApplicationContext context: Context): TtsController =
        AndroidTtsController(context)
}
