package dev.linjian.healthbridge

import android.content.Context

object BridgePrefs {
    private const val FILE = "erses_health_bridge"
    private const val KEY_SERVER = "server_url"
    private const val KEY_TOKEN = "token"
    private const val KEY_DEVICE = "device_id"
    private const val KEY_AUTO = "auto_sync"
    private const val KEY_LAST_OK = "last_ok"
    private const val KEY_LAST_ERROR = "last_error"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun server(context: Context): String =
        prefs(context).getString(KEY_SERVER, "")?.trim()?.trimEnd('/') ?: ""

    fun token(context: Context): String =
        prefs(context).getString(KEY_TOKEN, "")?.trim() ?: ""

    fun device(context: Context): String =
        prefs(context).getString(KEY_DEVICE, "my-phone")?.trim().orEmpty().ifBlank { "my-phone" }

    fun autoSync(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO, false)
    fun lastOk(context: Context): String = prefs(context).getString(KEY_LAST_OK, "") ?: ""
    fun lastError(context: Context): String = prefs(context).getString(KEY_LAST_ERROR, "") ?: ""

    fun saveConnection(context: Context, server: String, token: String, device: String) {
        prefs(context).edit()
            .putString(KEY_SERVER, server.trim().trimEnd('/'))
            .putString(KEY_TOKEN, token.trim())
            .putString(KEY_DEVICE, device.trim().ifBlank { "my-phone" })
            .apply()
    }

    fun setAutoSync(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO, enabled).apply()
    }

    fun recordSuccess(context: Context, timestamp: String) {
        prefs(context).edit()
            .putString(KEY_LAST_OK, timestamp)
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    fun recordError(context: Context, message: String) {
        prefs(context).edit().putString(KEY_LAST_ERROR, message.take(300)).apply()
    }
}
