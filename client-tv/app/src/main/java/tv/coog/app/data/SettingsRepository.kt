package tv.coog.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("coog_settings")

class SettingsRepository(private val context: Context) {
    private val serverUrlKey = stringPreferencesKey("server_url")
    private val tokenKey = stringPreferencesKey("auth_token")

    val serverUrl: Flow<String> = context.dataStore.data.map { it[serverUrlKey] ?: "http://10.0.2.2:8090" }
    val token: Flow<String> = context.dataStore.data.map { it[tokenKey] ?: "" }

    suspend fun setServerUrl(value: String) {
        context.dataStore.edit { it[serverUrlKey] = value.trim().trimEnd('/') }
    }

    suspend fun setToken(value: String) {
        context.dataStore.edit { it[tokenKey] = value.trim() }
    }
}
