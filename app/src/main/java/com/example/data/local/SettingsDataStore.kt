package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "wibes_settings")

data class CompilerSettings(
    val serverBaseUrl: String = "http://10.0.2.2:8000",
    val authToken: String = "",
    val username: String = "",
    val geminiApiKey: String = "",
    val maxUploadSizeMb: Int = 100,
    val maxConcurrentBuilds: Int = 2,
    val themeMode: ThemeMode = ThemeMode.SYSTEM
)

class SettingsDataStore(private val context: Context) {

    private object Keys {
        val SERVER_BASE_URL = stringPreferencesKey("server_base_url")
        val AUTH_TOKEN = stringPreferencesKey("auth_token")
        val USERNAME = stringPreferencesKey("username")
        val GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
        val MAX_UPLOAD_SIZE_MB = intPreferencesKey("max_upload_size_mb")
        val MAX_CONCURRENT_BUILDS = intPreferencesKey("max_concurrent_builds")
        val THEME_MODE = stringPreferencesKey("theme_mode")
    }

    val settingsFlow: Flow<CompilerSettings> = context.dataStore.data.map { prefs ->
        val themeStr = prefs[Keys.THEME_MODE] ?: ThemeMode.SYSTEM.name
        val theme = ThemeMode.entries.firstOrNull { it.name == themeStr } ?: ThemeMode.SYSTEM
        CompilerSettings(
            serverBaseUrl = prefs[Keys.SERVER_BASE_URL] ?: "http://10.0.2.2:8000",
            authToken = prefs[Keys.AUTH_TOKEN] ?: "",
            username = prefs[Keys.USERNAME] ?: "",
            geminiApiKey = prefs[Keys.GEMINI_API_KEY] ?: "",
            maxUploadSizeMb = (prefs[Keys.MAX_UPLOAD_SIZE_MB] ?: 100).coerceIn(10, 500),
            maxConcurrentBuilds = (prefs[Keys.MAX_CONCURRENT_BUILDS] ?: 2).coerceIn(1, 4),
            themeMode = theme
        )
    }

    suspend fun updateServerBaseUrl(url: String) {
        val normalized = url.trim().trimEnd('/')
        context.dataStore.edit { it[Keys.SERVER_BASE_URL] = normalized }
    }

    suspend fun updateAuthSession(token: String, username: String) {
        context.dataStore.edit {
            it[Keys.AUTH_TOKEN] = token
            it[Keys.USERNAME] = username
        }
    }

    suspend fun clearAuthSession() {
        context.dataStore.edit {
            it[Keys.AUTH_TOKEN] = ""
            it[Keys.USERNAME] = ""
        }
    }

    suspend fun updateGeminiApiKey(apiKey: String) {
        context.dataStore.edit { it[Keys.GEMINI_API_KEY] = apiKey.trim() }
    }

    suspend fun updateMaxUploadSizeMb(sizeMb: Int) {
        context.dataStore.edit { it[Keys.MAX_UPLOAD_SIZE_MB] = sizeMb.coerceIn(10, 500) }
    }

    suspend fun updateMaxConcurrentBuilds(limit: Int) {
        context.dataStore.edit { it[Keys.MAX_CONCURRENT_BUILDS] = limit.coerceIn(1, 4) }
    }

    suspend fun updateThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }
}
