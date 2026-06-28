package com.pettycash.cashsms.sync

import android.content.Context

object SyncPrefs {
    private const val PREFS = "cash_sms_sync"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_TOKEN = "token"
    private const val KEY_ACTIVE_BASE_URL = "active_base_url"
    private const val KEY_BACKEND_TYPE = "backend_type"

    fun getBaseUrl(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_BASE_URL, null)

    fun getToken(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TOKEN, null)

    fun getBackendType(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_BACKEND_TYPE, "DJANGO") ?: "DJANGO"

    fun setBaseUrl(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_BASE_URL, value.trim())
            .putString(KEY_ACTIVE_BASE_URL, value.trim())
            .apply()
    }

    fun setToken(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TOKEN, value.trim()).apply()
    }

    fun setBackendType(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_BACKEND_TYPE, value.trim().uppercase())
            .apply()
    }

    fun getActiveBaseUrl(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ACTIVE_BASE_URL, null)

    fun setActiveBaseUrl(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ACTIVE_BASE_URL, value.trim())
            .apply()
    }
}
