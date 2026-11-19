package com.kai.assistant.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kai.assistant.api.BackendUrlResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "kai_settings")

enum class VoiceGender { Female, Male }

data class KaiSettings(
    val languageCode: String = "en",
    val voiceGender: VoiceGender = VoiceGender.Female,
    val speechRate: Float = 1.0f,
    val memoryEnabled: Boolean = true,
    val darkTheme: Boolean = false,
    /** User override for kai-backend. Blank → device-aware default. */
    val backendBaseUrl: String = "",
)

class SettingsRepository(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private object Keys {
        val language = stringPreferencesKey("language")
        val voiceGender = stringPreferencesKey("voice_gender")
        val speechRate = floatPreferencesKey("speech_rate")
        val memoryEnabled = booleanPreferencesKey("memory_enabled")
        val darkTheme = booleanPreferencesKey("dark_theme")
        val backendBaseUrl = stringPreferencesKey("backend_base_url")
    }

    private val _settings = MutableStateFlow(KaiSettings())
    val settings: StateFlow<KaiSettings> = _settings.asStateFlow()

    init {
        scope.launch {
            appContext.dataStore.data
                .map { prefs -> prefs.toKaiSettings() }
                .collect { _settings.value = it }
        }
    }

    fun snapshot(): KaiSettings = _settings.value

    /** Resolved URL for API calls (may be blank on physical devices until configured). */
    fun effectiveBackendBaseUrl(): String =
        BackendUrlResolver.resolve(snapshot().backendBaseUrl)

    suspend fun setLanguage(code: String) {
        appContext.dataStore.edit { it[Keys.language] = code }
    }

    suspend fun setVoiceGender(gender: VoiceGender) {
        appContext.dataStore.edit { it[Keys.voiceGender] = gender.name }
    }

    suspend fun setSpeechRate(rate: Float) {
        appContext.dataStore.edit { it[Keys.speechRate] = rate.coerceIn(0.5f, 2.0f) }
    }

    suspend fun setMemoryEnabled(enabled: Boolean) {
        appContext.dataStore.edit { it[Keys.memoryEnabled] = enabled }
    }

    suspend fun setDarkTheme(enabled: Boolean) {
        appContext.dataStore.edit { it[Keys.darkTheme] = enabled }
    }

    suspend fun setBackendBaseUrl(url: String) {
        val normalized = url.trim().trimEnd('/')
        appContext.dataStore.edit { it[Keys.backendBaseUrl] = normalized }
    }

    private fun Preferences.toKaiSettings(): KaiSettings =
        KaiSettings(
            languageCode = this[Keys.language] ?: "en",
            voiceGender = runCatching {
                VoiceGender.valueOf(this[Keys.voiceGender] ?: VoiceGender.Female.name)
            }.getOrDefault(VoiceGender.Female),
            speechRate = this[Keys.speechRate] ?: 1.0f,
            memoryEnabled = this[Keys.memoryEnabled] ?: true,
            darkTheme = this[Keys.darkTheme] ?: false,
            backendBaseUrl = this[Keys.backendBaseUrl].orEmpty(),
        )
}
