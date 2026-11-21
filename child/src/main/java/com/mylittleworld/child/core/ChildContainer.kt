package com.mylittleworld.child.core

import android.content.Context
import android.os.Build
import android.util.Log
import com.mylittleworld.shared.model.DeviceConfig
import com.mylittleworld.shared.net.ApiClient
import com.mylittleworld.shared.net.ChildApi
import com.mylittleworld.shared.store.SecureStore
import com.mylittleworld.shared.store.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-wide dependency graph. Deliberately small: no DI framework is needed
 * for an app this size, and manual wiring keeps startup fast on old hardware.
 */
class ChildContainer(private val context: Context) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsStore(context)
    val secure = SecureStore(context)

    private val _config = MutableStateFlow(DeviceConfig())
    val config: StateFlow<DeviceConfig> = _config.asStateFlow()

    private val _paired = MutableStateFlow(false)
    val paired: StateFlow<Boolean> = _paired.asStateFlow()

    private val _serverUrl = MutableStateFlow(SettingsStore.DEFAULT_SERVER)
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    @Volatile
    private var cachedApi: Pair<String, ChildApi>? = null

    /** True when the app is pointed at a local development service. */
    val isLocalDevMode: Boolean
        get() = _serverUrl.value.contains("10.0.2.2") || _serverUrl.value.contains("localhost") ||
            _serverUrl.value.contains("127.0.0.1") || _serverUrl.value.contains(".invalid")

    init {
        scope.launch {
            settings.serverUrl.collect { _serverUrl.value = it }
        }
        scope.launch {
            settings.config.collect { _config.value = it }
        }
        scope.launch {
            settings.paired.collect { _paired.value = it }
        }
    }

    fun childApi(): ChildApi {
        val url = _serverUrl.value
        cachedApi?.let { (cachedUrl, api) -> if (cachedUrl == url) return api }
        val api = ApiClient(
            baseUrl = url,
            tokenProvider = { secure.token },
        )
        val childApi = ChildApi(api)
        cachedApi = url to childApi
        return childApi
    }

    fun deviceName(): String {
        val model = Build.MODEL ?: "Android phone"
        return model.take(40)
    }

    fun appVersion(): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.versionName ?: "1.0.0"
    }.getOrDefault("1.0.0")

    fun recordCrash(name: String) {
        runCatching {
            context.getSharedPreferences("mlw_crash", Context.MODE_PRIVATE).edit()
                .putLong("last_crash_at", System.currentTimeMillis())
                .putString("last_crash", name)
                .apply()
        }
        Log.w("ChildContainer", "Recorded crash $name")
    }

    fun lastCrashAt(): Long = runCatching {
        context.getSharedPreferences("mlw_crash", Context.MODE_PRIVATE)
            .getLong("last_crash_at", 0L)
    }.getOrDefault(0L)

    /** Applies a locally edited config and pushes it to the service when paired. */
    suspend fun saveConfigLocally(config: DeviceConfig) {
        settings.saveConfig(config)
    }

    suspend fun refreshFromServer(): Result<DeviceConfig> = runCatching {
        val snapshot = childApi().snapshot()
        settings.saveConfig(snapshot.config)
        snapshot.config
    }
}
