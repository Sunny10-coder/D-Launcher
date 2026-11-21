package com.mylittleworld.shared.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mylittleworld.shared.model.DeviceConfig
import com.mylittleworld.shared.net.ApiJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "mlw_settings")

/**
 * Local persistent state. Corrupt JSON is tolerated: the affected value falls
 * back to its default instead of crashing the launcher.
 */
class SettingsStore(private val context: Context) {

    val serverUrl: Flow<String> = context.dataStore.data.map { it[KEY_SERVER] ?: DEFAULT_SERVER }
    val deviceId: Flow<String?> = context.dataStore.data.map { it[KEY_DEVICE_ID] }
    val paired: Flow<Boolean> = deviceId.map { !it.isNullOrBlank() }
    val pinHash: Flow<String?> = context.dataStore.data.map { it[KEY_PIN] }
    val autoAnswerAccepted: Flow<Boolean> = context.dataStore.data.map { it[KEY_AUTO_ANSWER] ?: false }
    val firstRunDone: Flow<Boolean> = context.dataStore.data.map { it[KEY_ONBOARDED] ?: false }

    val config: Flow<DeviceConfig> = context.dataStore.data.map { prefs ->
        val raw = prefs[KEY_CONFIG]
        if (raw.isNullOrBlank()) DeviceConfig() else runCatching {
            ApiJson.json.decodeFromString(DeviceConfig.serializer(), raw)
        }.getOrDefault(DeviceConfig(nickname = "Little Star"))
    }

    suspend fun currentConfig(): DeviceConfig = config.first()

    suspend fun getServerUrl(): String = serverUrl.first()

    suspend fun getDeviceId(): String? = deviceId.first()

    suspend fun saveServerUrl(url: String) = edit { it[KEY_SERVER] = url.trim().trimEnd('/') }

    suspend fun saveDevice(deviceId: String, parentName: String?) = edit {
        it[KEY_DEVICE_ID] = deviceId
        parentName?.let { name -> it[KEY_PARENT_NAME] = name }
    }

    suspend fun savePinHash(hash: String) = edit { it[KEY_PIN] = hash }

    suspend fun saveConfig(config: DeviceConfig) = edit {
        it[KEY_CONFIG] = ApiJson.json.encodeToString(DeviceConfig.serializer(), config)
    }

    suspend fun setAutoAnswerAccepted(value: Boolean) = edit { it[KEY_AUTO_ANSWER] = value }

    suspend fun setOnboarded(value: Boolean) = edit { it[KEY_ONBOARDED] = value }

    suspend fun unpair() = edit {
        it.remove(KEY_DEVICE_ID)
        it.remove(KEY_CONFIG)
        it.remove(KEY_PIN)
        it.remove(KEY_AUTO_ANSWER)
    }

    suspend fun commandCursor(): String? = context.dataStore.data.first()[KEY_CURSOR]

    suspend fun saveCommandCursor(value: String) = edit { it[KEY_CURSOR] = value }

    suspend fun purgeChildData() = edit {
        it.remove(KEY_CONFIG)
        it.remove(KEY_CURSOR)
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit { block(it) }
    }

    companion object {
        const val DEFAULT_SERVER = "https://family-connect.example.invalid"
        private val KEY_SERVER = stringPreferencesKey("server_url")
        private val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        private val KEY_PARENT_NAME = stringPreferencesKey("parent_name")
        private val KEY_PIN = stringPreferencesKey("settings_pin")
        private val KEY_CONFIG = stringPreferencesKey("device_config")
        private val KEY_CURSOR = stringPreferencesKey("command_cursor")
        private val KEY_AUTO_ANSWER = booleanPreferencesKey("auto_answer_accepted")
        private val KEY_ONBOARDED = booleanPreferencesKey("onboarded")
    }
}
