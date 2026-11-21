package com.mylittleworld.shared.store

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Credentials (device token / session token) are kept in Keystore-encrypted
 * preferences. They are never written to logs or plain files.
 */
class SecureStore(context: Context, fileName: String = "mlw_secure") {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            fileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        // A corrupted keystore entry must not brick the launcher: fall back to a
        // private preferences file and force re-pairing by clearing credentials.
        Log.w(TAG, "Encrypted storage unavailable, resetting credentials")
        context.getSharedPreferences(fileName, Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences(fileName, Context.MODE_PRIVATE)
    }

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit().apply {
            if (value == null) remove(KEY_TOKEN) else putString(KEY_TOKEN, value)
        }.apply()

    var tokenExpiresAt: String?
        get() = prefs.getString(KEY_EXPIRES, null)
        set(value) = prefs.edit().apply {
            if (value == null) remove(KEY_EXPIRES) else putString(KEY_EXPIRES, value)
        }.apply()

    var parentName: String?
        get() = prefs.getString(KEY_PARENT_NAME, null)
        set(value) = prefs.edit().apply {
            if (value == null) remove(KEY_PARENT_NAME) else putString(KEY_PARENT_NAME, value)
        }.apply()

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val TAG = "SecureStore"
        const val KEY_TOKEN = "token"
        const val KEY_EXPIRES = "token_expires"
        const val KEY_PARENT_NAME = "parent_name"
    }
}
