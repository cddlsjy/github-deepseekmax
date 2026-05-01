package com.gemini.githubexplorer

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class AppConfig(
    val token: String = "",
    val fontScale: Float = Constants.DEFAULT_FONT_SCALE,
    val lastSearchQuery: String = "",
    val lastRepoFullName: String = ""
)

class ConfigManager(private val context: Context) {

    companion object {
        private val TOKEN_KEY = stringPreferencesKey("github_token")
        private val FONT_SCALE_KEY = doublePreferencesKey("font_scale")
        private val LAST_SEARCH_QUERY_KEY = stringPreferencesKey("last_search_query")
        private val LAST_REPO_FULL_NAME_KEY = stringPreferencesKey("last_repo_full_name")
    }

    suspend fun loadConfig(): AppConfig {
        return context.dataStore.data.map { preferences ->
            AppConfig(
                token = preferences[TOKEN_KEY] ?: "",
                fontScale = (preferences[FONT_SCALE_KEY] ?: Constants.DEFAULT_FONT_SCALE.toDouble()).toFloat(),
                lastSearchQuery = preferences[LAST_SEARCH_QUERY_KEY] ?: "",
                lastRepoFullName = preferences[LAST_REPO_FULL_NAME_KEY] ?: ""
            )
        }.first()
    }

    suspend fun saveConfig(config: AppConfig) {
        context.dataStore.edit { preferences ->
            preferences[TOKEN_KEY] = config.token
            preferences[FONT_SCALE_KEY] = config.fontScale.toDouble()
            preferences[LAST_SEARCH_QUERY_KEY] = config.lastSearchQuery
            preferences[LAST_REPO_FULL_NAME_KEY] = config.lastRepoFullName
        }
    }

    suspend fun saveToken(token: String) {
        context.dataStore.edit { preferences ->
            preferences[TOKEN_KEY] = token
        }
    }

    suspend fun saveFontScale(scale: Float) {
        context.dataStore.edit { preferences ->
            preferences[FONT_SCALE_KEY] = scale.toDouble()
        }
    }

    suspend fun saveLastSearchQuery(query: String) {
        context.dataStore.edit { preferences ->
            preferences[LAST_SEARCH_QUERY_KEY] = query
        }
    }

    suspend fun saveLastRepoFullName(fullName: String) {
        context.dataStore.edit { preferences ->
            preferences[LAST_REPO_FULL_NAME_KEY] = fullName
        }
    }
}
