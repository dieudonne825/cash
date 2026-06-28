package com.pettycash.cashsms.sync

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object LaravelAuthClient {
    private val TAG = "LaravelAuthClient"
    private val client = OkHttpClient()
    private val json = "application/json; charset=utf-8".toMediaType()

    data class LoginResult(
        val token: String? = null,
        val errorMessage: String? = null,
        val errorDetails: String? = null,
        val errorUrl: String? = null,
        val httpCode: Int? = null
    )

    fun login(baseUrl: String, email: String, password: String): LoginResult {
        val url = baseUrl.trimEnd('/') + "/api/login"
        Log.d(TAG, "Laravel Login attempt - URL: $url, email: $email")
        
        val body = JSONObject()
            .put("email", email)   // Laravel utilise "email"
            .put("password", password)
            .toString()

        val req = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .post(body.toRequestBody(json))
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                Log.d(TAG, "Laravel Login response - Code: ${resp.code}")
                if (resp.isSuccessful) {
                    val token = JSONObject(raw).optString("auth_token").takeIf { it.isNotBlank() }
                        ?: JSONObject(raw).optString("token").takeIf { it.isNotBlank() }
                        ?: JSONObject(raw).optString("access_token").takeIf { it.isNotBlank() }
                    
                    if (token != null) {
                        LoginResult(token = token)
                    } else {
                        LoginResult(
                            errorMessage = "auth_token/token/access_token manquant",
                            errorUrl = url,
                            errorDetails = raw.take(200),
                            httpCode = resp.code
                        )
                    }
                } else {
                    val message = try {
                        JSONObject(raw).optString("message")
                    } catch (_: Exception) { "Erreur HTTP ${resp.code}" }
                    LoginResult(
                        errorMessage = message,
                        errorUrl = url,
                        errorDetails = raw.take(200),
                        httpCode = resp.code
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network error during Laravel login: ${e.message}", e)
            LoginResult(
                errorMessage = e.message ?: "Erreur réseau",
                errorUrl = url,
                errorDetails = e.javaClass.simpleName + ": " + e.message
            )
        }
    }
}
