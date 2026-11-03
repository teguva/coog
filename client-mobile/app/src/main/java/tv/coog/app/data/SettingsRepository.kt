package tv.coog.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("coog_settings")

/** Where mobile Maize drives toys — TV is always server BLE. */
enum class MaizeBleMode {
    /** Phone/tablet embedded Intiface (or Intiface Central on-device). */
    Device,
    /** Coog server intiface-engine (same path as TV). */
    Server,
    ;

    companion object {
        fun fromStorage(raw: String?): MaizeBleMode = when (raw?.trim()?.lowercase()) {
            "server" -> Server
            else -> Device
        }
    }

    fun storage(): String = when (this) {
        Device -> "device"
        Server -> "server"
    }
}

class SettingsRepository(private val context: Context) {
    private val serverUrlKey = stringPreferencesKey("server_url")
    private val tokenKey = stringPreferencesKey("auth_token")
    private val maizeBleModeKey = stringPreferencesKey("maize_ble_mode")

    val serverUrl: Flow<String> = context.dataStore.data.map { it[serverUrlKey] ?: "http://10.0.2.2:8090" }
    val token: Flow<String> = context.dataStore.data.map { it[tokenKey] ?: "" }
    val maizeBleMode: Flow<MaizeBleMode> = context.dataStore.data.map {
        MaizeBleMode.fromStorage(it[maizeBleModeKey])
    }

    suspend fun setServerUrl(value: String) {
        context.dataStore.edit { it[serverUrlKey] = value.trim().trimEnd('/') }
    }

    suspend fun setToken(value: String) {
        context.dataStore.edit { it[tokenKey] = value.trim() }
    }

    suspend fun setMaizeBleMode(mode: MaizeBleMode) {
        context.dataStore.edit { it[maizeBleModeKey] = mode.storage() }
    }
}
