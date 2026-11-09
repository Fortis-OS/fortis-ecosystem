package com.fortis.mobile.network

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit

class AuthInterceptor(private val apiKey: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .addHeader("Authorization", "Bearer $apiKey")
            .build()
        return chain.proceed(request)
    }
}

object AgentClient {
    private var client: OkHttpClient? = null

    // Call this when settings change
    fun getClient(apiKey: String): OkHttpClient {
        if (client == null) {
            client = OkHttpClient.Builder()
                .addInterceptor(AuthInterceptor(apiKey))
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.MINUTES)
                .writeTimeout(10, TimeUnit.MINUTES)
                .build()
        }
        return client!!
    }
    
    // TODO: Add Retrofit interfaces for /api/v1/telemetry and /api/v1/action
}
