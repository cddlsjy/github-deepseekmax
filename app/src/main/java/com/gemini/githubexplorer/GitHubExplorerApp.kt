package com.gemini.githubexplorer

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "github_browser_config")

class GitHubExplorerApp : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}
