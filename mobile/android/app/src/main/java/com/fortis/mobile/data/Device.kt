package com.fortis.mobile.data

/**
 * A paired laptop (docs/security §6.2). No secrets here: the request and
 * approval keys live in the Android Keystore under aliases derived from
 * [uuid]. The agent's public key is pinned at pairing (security §5.5).
 */
data class Device(
    /** Local pairing id; Keystore aliases are `fortis.<uuid>.request|approval`. */
    val uuid: String,
    /** Laptop name reported by the agent. */
    val name: String,
    /** Tailscale IPv4 address of the laptop. */
    val ipAddress: String,
    val port: Int = 8023,
    /** This phone's device id on that laptop (sent as X-Fortis-Device). */
    val deviceId: String,
    /** base64 SEC1 P-256 public key of the agent. */
    val agentPubkey: String,
    /** The one laptop the phone syncs the vault with (security §6.3). */
    val isVaultLaptop: Boolean = false,
    val pairedAt: Long = System.currentTimeMillis(),
    val lastSeen: Long = 0,
    val capabilitiesJson: String = "{}",
    val isOnline: Boolean = true,
) {
    val baseUrl: String get() = "http://$ipAddress:$port"
}
