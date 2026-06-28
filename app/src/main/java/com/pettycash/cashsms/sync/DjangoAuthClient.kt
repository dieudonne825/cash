package com.pettycash.cashsms.sync

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object DjangoAuthClient {
    private val TAG = "DjangoAuthClient"
    private val client = OkHttpClient()
    private val json = "application/json; charset=utf-8".toMediaType()

    data class LoginResult(
        val token: String? = null,
        val errorMessage: String? = null,
        val errorDetails: String? = null,
        val errorUrl: String? = null,
        val httpCode: Int? = null
    )

    fun login(baseUrl: String, username: String, password: String): LoginResult {
        val url = baseUrl.trimEnd('/') + "/api/v1/token/login/"
        Log.d(TAG, "Login attempt - URL: $url, username: $username")
        
        val body = JSONObject()
            .put("username", username)
            .put("password", password)
            .toString()

        val req = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .post(body.toRequestBody(json))
            .build()

        return try {
            Log.d(TAG, "Sending POST request to login endpoint")
            client.newCall(req).execute().use { resp ->
                Log.d(TAG, "Response received - Code: ${resp.code}")
                
                val raw = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    val token = try {
                        JSONObject(raw).optString("auth_token").takeIf { it.isNotBlank() }
                    } catch (_: Exception) {
                        null
                    }
                    if (token != null) {
                        Log.d(TAG, "Login successful - Token received")
                        LoginResult(token = token)
                    } else {
                        Log.e(TAG, "Login failed - auth_token missing in response")
                        LoginResult(
                            errorMessage = "auth_token manquant", 
                            errorUrl = url,
                            errorDetails = raw.take(200),
                            httpCode = resp.code
                        )
                    }
                } else {
                    val message = parseDjoserError(raw) ?: "HTTP ${resp.code}"
                    Log.e(TAG, "Login error: $message")
                    LoginResult(
                        errorMessage = message, 
                        errorUrl = url,
                        errorDetails = raw.take(200),
                        httpCode = resp.code
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network error during login: ${e.message}", e)
            LoginResult(
                errorMessage = e.message ?: "network error",
                errorUrl = url,
                errorDetails = e.javaClass.simpleName + ": " + e.message
            )
        }
    }

    /**
     * Optional pre-check compatible with your frontend:
     * POST /api/v1/home/check-lockout/ { "username": "..." }
     * If locked, backend may return 423 with details.
     */
    fun checkLockout(baseUrl: String, username: String): LoginResult {
        val url = baseUrl.trimEnd('/') + "/api/v1/home/check-lockout/"
        Log.d(TAG, "Checking lockout - URL: $url, username: $username")
        
        val body = JSONObject().put("username", username).toString()

        val req = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .post(body.toRequestBody(json))
            .build()

        return try {
            Log.d(TAG, "Sending POST request to check-lockout endpoint")
            client.newCall(req).execute().use { resp ->
                Log.d(TAG, "Response received - Code: ${resp.code}")
                
                val raw = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    Log.d(TAG, "Lockout check passed")
                    LoginResult()
                } else {
                    val message = parseDjoserError(raw) ?: raw.ifBlank { "HTTP ${resp.code}" }
                    Log.e(TAG, "Lockout check error: $message")
                    LoginResult(
                        errorMessage = message,
                        errorUrl = url,
                        errorDetails = raw.take(200),
                        httpCode = resp.code
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network error during lockout check: ${e.message}", e)
            LoginResult(
                errorMessage = e.message ?: "network error",
                errorUrl = url,
                errorDetails = e.javaClass.simpleName + ": " + e.message
            )
        }
    }

    private fun parseDjoserError(raw: String): String? {
        return try {
            val obj = JSONObject(raw)
            when {
                obj.has("non_field_errors") -> obj.optJSONArray("non_field_errors")?.optString(0)
                obj.keys().hasNext() -> {
                    val k = obj.keys().next()
                    val v = obj.opt(k)
                    when (v) {
                        is String -> v
                        else -> v?.toString()
                    }
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }
}
