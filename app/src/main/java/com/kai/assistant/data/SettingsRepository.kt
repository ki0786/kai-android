package com.kai.assistant.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "kai_settings")

enum class VoiceGender { Female, Male }

data class KaiSettings(
    val languageCode: String = "en",
    val voiceGender: VoiceGender = VoiceGender.Female,
    val speechRate: Float = 1.0f,
    val memoryEnabled: Boolean = true,
    /** Keep a log of conversation turns on this phone. Off until the user turns it on. */
    val chatHistoryEnabled: Boolean = false,
    val darkTheme: Boolean = false,
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val language = stringPreferencesKey("language")
        val voiceGender = stringPreferencesKey("voice_gender")
        val speechRate = floatPreferencesKey("speech_rate")
        val memoryEnabled = booleanPreferencesKey("memory_enabled")
        val darkTheme = booleanPreferencesKey("dark_theme")
        val chatHistory = booleanPreferencesKey("chat_history_enabled")
    }

    val settings: Flow<KaiSettings> = context.dataStore.data.map { prefs ->
        KaiSettings(
            languageCode = prefs[Keys.language] ?: "en",
            voiceGender = runCatching {
                VoiceGender.valueOf(prefs[Keys.voiceGender] ?: VoiceGender.Female.name)
            }.getOrDefault(VoiceGender.Female),
            speechRate = prefs[Keys.speechRate] ?: 1.0f,
            memoryEnabled = prefs[Keys.memoryEnabled] ?: true,
            darkTheme = prefs[Keys.darkTheme] ?: false,
            chatHistoryEnabled = prefs[Keys.chatHistory] ?: false,
        )
    }

    suspend fun setLanguage(code: String) {
        context.dataStore.edit { it[Keys.language] = code }
    }

    suspend fun setVoiceGender(gender: VoiceGender) {
        context.dataStore.edit { it[Keys.voiceGender] = gender.name }
    }

    suspend fun setSpeechRate(rate: Float) {
        context.dataStore.edit { it[Keys.speechRate] = rate.coerceIn(0.5f, 2.0f) }
    }

    suspend fun setMemoryEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.memoryEnabled] = enabled }
    }

    suspend fun setChatHistoryEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.chatHistory] = enabled }
    }

    suspend fun setDarkTheme(enabled: Boolean) {
        context.dataStore.edit { it[Keys.darkTheme] = enabled }
    }
}
