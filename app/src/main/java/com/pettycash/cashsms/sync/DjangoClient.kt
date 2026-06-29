package com.pettycash.cashsms.sync

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object DjangoClient {
    private val TAG = "DjangoClient"
    private val client = OkHttpClient()
    private val json = "application/json; charset=utf-8".toMediaType()

    fun postJson(url: String, token: String, jsonBody: String): okhttp3.Response {
        Log.d(TAG, "POST request to: $url")
        Log.d(TAG, "Request body: $jsonBody")
        
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Token $token")
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

    fun getJson(url: String, token: String): okhttp3.Response {
        Log.d(TAG, "GET request to: $url")
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Token $token")
            .addHeader("Accept", "application/json")
            .get()
            .build()
        try {
            val response = client.newCall(request).execute()
            Log.d(TAG, "Response received - Code: ${response.code}")
            return response
        } catch (e: Exception) {
            Log.e(TAG, "Network error: ${e.message}", e)
            throw e
        }
    }
}
