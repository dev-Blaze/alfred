package com.yshah.alfred.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.yshah.alfred.assistant.AssistantMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.modeDataStore by preferencesDataStore(name = "alfred_mode_prefs")

/**
 * Persists the last-selected mode outside any Activity/session lifecycle — MainActivity and
 * AlfredVoiceInteractionSession are both created/destroyed per invocation, so this can't live on
 * either of them (app-scoped singleton via DataStoreModule instead).
 */
class ModePreferences(private val context: Context) {
    private object Keys {
        val LAST_MODE = stringPreferencesKey("last_mode")
    }

    val lastMode: Flow<AssistantMode> = context.modeDataStore.data.map { prefs ->
        AssistantMode.fromKey(prefs[Keys.LAST_MODE]) ?: AssistantMode.TASK
    }

    suspend fun setLastMode(mode: AssistantMode) {
        context.modeDataStore.edit { it[Keys.LAST_MODE] = mode.key }
    }
}
