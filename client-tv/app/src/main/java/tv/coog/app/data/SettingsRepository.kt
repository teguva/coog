package tv.coog.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("coog_settings")

/** Per-device prefs that never hit the server streaming API. */
data class DevicePrefs(
    val trailerSound: String = "on",
    val trailerSkipStartSec: Int = 15,
    val trailerSkipEndSec: Int = 10,
    val preferredBackdropMax: String = "1080p",
)

class SettingsRepository(private val context: Context) {
    private val serverUrlKey = stringPreferencesKey("server_url")
    private val tokenKey = stringPreferencesKey("auth_token")
    private val trailerSoundKey = stringPreferencesKey("trailer_sound")
    private val trailerSkipStartKey = intPreferencesKey("trailer_skip_start_sec")
    private val trailerSkipEndKey = intPreferencesKey("trailer_skip_end_sec")
    private val backdropMaxKey = stringPreferencesKey("preferred_backdrop_max")

    val serverUrl: Flow<String> = context.dataStore.data.map { it[serverUrlKey] ?: "http://10.0.2.2:8090" }
    val token: Flow<String> = context.dataStore.data.map { it[tokenKey] ?: "" }

    val devicePrefs: Flow<DevicePrefs> = context.dataStore.data.map { prefs ->
        DevicePrefs(
            trailerSound = normalizeTrailerSound(prefs[trailerSoundKey]),
            trailerSkipStartSec = (prefs[trailerSkipStartKey] ?: 15).coerceIn(0, 120),
            trailerSkipEndSec = (prefs[trailerSkipEndKey] ?: 10).coerceIn(0, 120),
            preferredBackdropMax = normalizeBackdropMax(prefs[backdropMaxKey]),
        )
    }

    suspend fun setServerUrl(value: String) {
        context.dataStore.edit { it[serverUrlKey] = value.trim().trimEnd('/') }
    }

    suspend fun setToken(value: String) {
        context.dataStore.edit { it[tokenKey] = value.trim() }
    }

    suspend fun setTrailerSound(value: String) {
        context.dataStore.edit { it[trailerSoundKey] = normalizeTrailerSound(value) }
    }

    suspend fun setTrailerSkipStartSec(value: Int) {
        context.dataStore.edit { it[trailerSkipStartKey] = value.coerceIn(0, 120) }
    }

    suspend fun setTrailerSkipEndSec(value: Int) {
        context.dataStore.edit { it[trailerSkipEndKey] = value.coerceIn(0, 120) }
    }

    suspend fun setPreferredBackdropMax(value: String) {
        context.dataStore.edit { it[backdropMaxKey] = normalizeBackdropMax(value) }
    }

    companion object {
        fun normalizeTrailerSound(value: String?): String = when (value?.trim()?.lowercase()) {
            "soft", "low", "quiet", "50" -> "soft"
            "off", "mute", "muted", "0", "false", "no" -> "off"
            else -> "on"
        }

        fun normalizeBackdropMax(value: String?): String = when (value?.trim()?.lowercase()) {
            "1440p", "1440", "qhd", "2k" -> "1440p"
            "2160p", "2160", "4k", "uhd", "3840" -> "2160p"
            else -> "1080p"
        }
    }
}
