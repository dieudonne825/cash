package com.pettycash.cashsms.sync

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object LaravelClient {
    private val TAG = "LaravelClient"
    private val client = OkHttpClient()
    private val json = "application/json; charset=utf-8".toMediaType()

    fun postJson(url: String, token: String, jsonBody: String): okhttp3.Response {
        Log.d(TAG, "POST request to: $url")
        Log.d(TAG, "Request body: $jsonBody")
        
        val authHeaderValue = if (token.startsWith("Bearer ", ignoreCase = true)) token else "Bearer $token"
        
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", authHeaderValue)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .post(jsonBody.toRequestBody(json))
            .build()
        
        try {
            val response = client.newCall(request).execute()
            Log.d(TAG, "Response received - Code: ${response.code}")
            if (!response.isSuccessful) {
                Log.e(TAG, "Error response: ${response.body?.string()}")
            }
            return response
        } catch (e: Exception) {
            Log.e(TAG, "Network error: ${e.message}", e)
            throw e
        }
    }
}
