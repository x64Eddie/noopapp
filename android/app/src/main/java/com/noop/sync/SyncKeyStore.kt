package com.noop.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure, at-rest-encrypted storage for the open-wearables backend connection: base URL, the
 * open-wearables User ID (UUID) this device's data is attributed to, and the API key used to
 * authenticate sync requests. Mirrors [com.noop.ai.AiKeyStore] — Jetpack Security
 * [EncryptedSharedPreferences] backed by an Android Keystore master key, so the API key is never
 * written to disk in the clear.
 */
object SyncKeyStore {

    private const val FILE_NAME = "noop_sync_secure_prefs"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_API_KEY = "api_key"

    private fun prefs(ctx: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(ctx.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            ctx.applicationContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** e.g. "http://your-backend-host:8000" — no trailing slash, no path suffix. */
    fun saveBaseUrl(ctx: Context, url: String) {
        prefs(ctx).edit().putString(KEY_BASE_URL, url.trim().trimEnd('/')).apply()
    }

    fun readBaseUrl(ctx: Context): String = prefs(ctx).getString(KEY_BASE_URL, null).orEmpty()

    fun saveUserId(ctx: Context, userId: String) {
        prefs(ctx).edit().putString(KEY_USER_ID, userId.trim()).apply()
    }

    fun readUserId(ctx: Context): String = prefs(ctx).getString(KEY_USER_ID, null).orEmpty()

    fun saveApiKey(ctx: Context, key: String) {
        prefs(ctx).edit().putString(KEY_API_KEY, key.trim()).apply()
    }

    fun readApiKey(ctx: Context): String = prefs(ctx).getString(KEY_API_KEY, null).orEmpty()

    /** True once base URL, user id, and API key are all set — the gate the UI/worker use. */
    fun isConfigured(ctx: Context): Boolean =
        readBaseUrl(ctx).isNotBlank() && readUserId(ctx).isNotBlank() && readApiKey(ctx).isNotBlank()

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }
}
