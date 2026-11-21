package com.mylittleworld.parent

import android.content.Context
import com.mylittleworld.shared.net.ApiClient
import com.mylittleworld.shared.net.ParentApi
import com.mylittleworld.shared.store.SecureStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Small, explicit preference holder for non-secret parent state. */
class ParentPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("mlw_parent", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER, DEFAULT_SERVER) ?: DEFAULT_SERVER
        set(value) = prefs.edit().putString(KEY_SERVER, value.trim().trimEnd('/')).apply()

    var email: String?
        get() = prefs.getString(KEY_EMAIL, null)
        set(value) = prefs.edit().putString(KEY_EMAIL, value).apply()

    var parentName: String?
        get() = prefs.getString(KEY_NAME, null)
        set(value) = prefs.edit().putString(KEY_NAME, value).apply()

    var lastDeviceId: String?
        get() = prefs.getString(KEY_DEVICE, null)
        set(value) = prefs.edit().putString(KEY_DEVICE, value).apply()

    val isLocalDev: Boolean
        get() = serverUrl.contains("10.0.2.2") || serverUrl.contains("localhost") ||
            serverUrl.contains("127.0.0.1") || serverUrl.contains(".invalid")

    fun clearSession() {
        prefs.edit().remove(KEY_EMAIL).remove(KEY_NAME).remove(KEY_DEVICE).apply()
    }

    companion object {
        const val DEFAULT_SERVER = "http://10.0.2.2:8787"
        private const val KEY_SERVER = "server_url"
        private const val KEY_EMAIL = "email"
        private const val KEY_NAME = "parent_name"
        private const val KEY_DEVICE = "device_id"
    }
}

class ParentContainer(context: Context) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val prefs = ParentPrefs(context)
    val secure = SecureStore(context, "mlw_parent_secure")

    private val _signedIn = MutableStateFlow(secure.token != null)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private var cached: Pair<String, ParentApi>? = null

    fun api(): ParentApi {
        val url = prefs.serverUrl
        cached?.let { (cachedUrl, api) -> if (cachedUrl == url) return api }
        val client = ApiClient(baseUrl = url, tokenProvider = { secure.token })
        val api = ParentApi(client)
        cached = url to api
        return api
    }

    fun onSignedIn(token: String, expiresAt: String, name: String, email: String) {
        secure.token = token
        secure.tokenExpiresAt = expiresAt
        secure.parentName = name
        prefs.parentName = name
        prefs.email = email
        _signedIn.value = true
    }

    fun signOut() {
        secure.clear()
        prefs.clearSession()
        _signedIn.value = false
        cached = null
    }
}
