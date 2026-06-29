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

    fun isSecurityEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("security_enabled", false)

    fun setSecurityEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("security_enabled", enabled)
            .apply()
    }

    fun getSecurityType(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("security_type", "PIN") ?: "PIN"

    fun setSecurityType(context: Context, type: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("security_type", type)
            .apply()
    }

    fun getSavedPin(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("saved_pin", "0000") ?: "0000"

    fun setSavedPin(context: Context, pin: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("saved_pin", pin)
            .apply()
    }

    fun getCustomRules(context: Context): List<CustomFilterRule> {
        val jsonStr = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("custom_filter_rules", null) ?: return emptyList()
        return try {
            val array = org.json.JSONArray(jsonStr)
            val list = mutableListOf<CustomFilterRule>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    CustomFilterRule(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        senderContains = obj.optString("senderContains"),
                        bodyContains = obj.optString("bodyContains"),
                        transactionType = obj.optString("transactionType", "IN"),
                        operatorName = obj.optString("operatorName", "Autre")
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveCustomRules(context: Context, rules: List<CustomFilterRule>) {
        val array = org.json.JSONArray()
        rules.forEach { rule ->
            val obj = org.json.JSONObject().apply {
                put("id", rule.id)
                put("senderContains", rule.senderContains)
                put("bodyContains", rule.bodyContains)
                put("transactionType", rule.transactionType)
                put("operatorName", rule.operatorName)
            }
            array.put(obj)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("custom_filter_rules", array.toString())
            .apply()
    }
}

data class CustomFilterRule(
    val id: String,
    val senderContains: String,
    val bodyContains: String,
    val transactionType: String, // "IN" (Dépôt) or "OUT" (Retrait)
    val operatorName: String
)
