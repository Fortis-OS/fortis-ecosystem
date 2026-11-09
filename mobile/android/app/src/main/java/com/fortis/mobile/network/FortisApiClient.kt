package com.fortis.mobile.network

import com.fortis.mobile.domain.ActiveDeviceProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import com.fortis.mobile.domain.VaultEntry
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

object FortisApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    fun getClient(): OkHttpClient = client

    /**
     * Builds a request with the given path (e.g. "/api/v1/telemetry").
     * Automatically injects the Active Device's IP and API key.
     * Throws an exception if no active device is set.
     */
    fun buildRequest(path: String, builderBlock: Request.Builder.() -> Unit = {}): Request {
        val device = ActiveDeviceProvider.get() 
            ?: throw IllegalStateException("No active device selected")
        
        val builder = Request.Builder()
            .url("http://${device.ipAddress}:8023$path")
            .header("Authorization", "Bearer ${device.apiKey}")
        
        builder.builderBlock()
        return builder.build()
    }

    suspend fun fetchVaultEntries(): List<VaultEntry> = withContext(Dispatchers.IO) {
        val request = buildRequest("/api/v1/vault/entries") { get() }
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: "[]"
        if (!response.isSuccessful) {
            throw Exception("Failed to fetch vault entries: ${response.code}")
        }
        
        val entries = mutableListOf<VaultEntry>()
        try {
            val jsonArray = JSONArray(responseBody)
            for (i in 0 until jsonArray.length()) {
                val jsonObject = jsonArray.getJSONObject(i)
                entries.add(VaultEntry.fromJson(jsonObject))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext entries
    }

    suspend fun addTotpEntry(title: String, secret: String) = withContext(Dispatchers.IO) {
        val jsonPayload = org.json.JSONObject().apply {
            put("title", title)
            put("notes", "Added via Fortis Mobile")
            put("entry_type", "Login")
            put("totp_secret", secret)
        }.toString()

        val mediaType = "application/json".toMediaTypeOrNull()
        val requestBody = jsonPayload.toRequestBody(mediaType)
        val request = buildRequest("/api/v1/vault/entries") { post(requestBody) }
        
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("Failed to add TOTP entry: ${response.code}")
        }
    }
}
