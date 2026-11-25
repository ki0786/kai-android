package com.kai.assistant.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kai.assistant.voice.wake.WakePrefs
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
    /** Background "Hey KAI". Off until the user turns it on. */
    val backgroundHeyKai: Boolean = false,
    /** 0 is stricter, 1 triggers more easily. 0.5 matches the model's usual threshold. */
    val heyKaiSensitivity: Float = 0.5f,
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val language = stringPreferencesKey("language")
        val voiceGender = stringPreferencesKey("voice_gender")
        val speechRate = floatPreferencesKey("speech_rate")
        val memoryEnabled = booleanPreferencesKey("memory_enabled")
        val darkTheme = booleanPreferencesKey("dark_theme")
        val chatHistory = booleanPreferencesKey("chat_history_enabled")
        val backgroundHeyKai = booleanPreferencesKey("background_hey_kai")
        val heyKaiSensitivity = floatPreferencesKey("hey_kai_sensitivity")
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
            backgroundHeyKai = prefs[Keys.backgroundHeyKai] ?: false,
            heyKaiSensitivity = (prefs[Keys.heyKaiSensitivity] ?: 0.5f).coerceIn(0f, 1f),
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

    suspend fun setBackgroundHeyKai(enabled: Boolean) {
        WakePrefs.setEnabled(context, enabled)
        context.dataStore.edit { it[Keys.backgroundHeyKai] = enabled }
    }

    suspend fun setHeyKaiSensitivity(sensitivity: Float) {
        val clamped = sensitivity.coerceIn(0f, 1f)
        WakePrefs.setSensitivity(context, clamped)
        context.dataStore.edit { it[Keys.heyKaiSensitivity] = clamped }
    }
}
