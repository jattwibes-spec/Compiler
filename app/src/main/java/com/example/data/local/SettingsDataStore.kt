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

enum class BuildEngineMode(val id: String, val label: String) {
    GITHUB_ACTIONS("GITHUB_ACTIONS", "GitHub Cloud"),
    FASTAPI_BACKEND("FASTAPI_BACKEND", "FastAPI Server");

    companion object {
        fun fromId(id: String?): BuildEngineMode =
            entries.firstOrNull { it.id == id } ?: GITHUB_ACTIONS
    }
}

data class CompilerSettings(
    val buildEngineMode: BuildEngineMode = BuildEngineMode.GITHUB_ACTIONS,
    val githubRepo: String = "",
    val githubToken: String = "",
    val downloadFolderUri: String = "",
    val downloadFolderLabel: String = "",
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
        val BUILD_ENGINE_MODE = stringPreferencesKey("build_engine_mode")
        val GITHUB_REPO = stringPreferencesKey("github_repo")
        val GITHUB_TOKEN = stringPreferencesKey("github_token")
        val DOWNLOAD_FOLDER_URI = stringPreferencesKey("download_folder_uri")
        val DOWNLOAD_FOLDER_LABEL = stringPreferencesKey("download_folder_label")
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
            buildEngineMode = BuildEngineMode.fromId(prefs[Keys.BUILD_ENGINE_MODE]),
            githubRepo = prefs[Keys.GITHUB_REPO] ?: "",
            githubToken = prefs[Keys.GITHUB_TOKEN] ?: "",
            downloadFolderUri = prefs[Keys.DOWNLOAD_FOLDER_URI] ?: "",
            downloadFolderLabel = prefs[Keys.DOWNLOAD_FOLDER_LABEL] ?: "",
            serverBaseUrl = prefs[Keys.SERVER_BASE_URL] ?: "http://10.0.2.2:8000",
            authToken = prefs[Keys.AUTH_TOKEN] ?: "",
            username = prefs[Keys.USERNAME] ?: "",
            geminiApiKey = prefs[Keys.GEMINI_API_KEY] ?: "",
            maxUploadSizeMb = (prefs[Keys.MAX_UPLOAD_SIZE_MB] ?: 100).coerceIn(10, 500),
            maxConcurrentBuilds = (prefs[Keys.MAX_CONCURRENT_BUILDS] ?: 2).coerceIn(1, 4),
            themeMode = theme
        )
    }

    suspend fun updateBuildEngineMode(mode: BuildEngineMode) {
        context.dataStore.edit { it[Keys.BUILD_ENGINE_MODE] = mode.id }
    }

    suspend fun updateGithubConfig(repo: String, token: String) {
        context.dataStore.edit {
            it[Keys.GITHUB_REPO] = repo.trim()
            it[Keys.GITHUB_TOKEN] = token.trim()
        }
    }

    suspend fun updateDownloadFolder(uriString: String, label: String) {
        context.dataStore.edit {
            it[Keys.DOWNLOAD_FOLDER_URI] = uriString
            it[Keys.DOWNLOAD_FOLDER_LABEL] = label
        }
    }

    suspend fun clearDownloadFolder() {
        context.dataStore.edit {
            it[Keys.DOWNLOAD_FOLDER_URI] = ""
            it[Keys.DOWNLOAD_FOLDER_LABEL] = ""
        }
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
