package com.example.fortismobile.data

data class Device(
    val uuid: String,
    val name: String,
    val ipAddress: String,
    val apiKey: String,
    val osType: String,
    val hostname: String,
    val lastSeen: Long,
    val capabilitiesJson: String,
    val isOnline: Boolean = true
)
