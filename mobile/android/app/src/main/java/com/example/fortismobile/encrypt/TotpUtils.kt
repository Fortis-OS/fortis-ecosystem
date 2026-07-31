package com.example.fortismobile.encrypt

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.nio.ByteBuffer
import kotlin.math.pow

object TotpUtils {

    fun generateTotpCode(secretBase32: String, timeMillis: Long): String {
        val secretBytes = try {
            decodeBase32(secretBase32)
        } catch (e: Exception) {
            return "ERROR"
        }

        // TOTP window is 30 seconds
        val timeWindow = timeMillis / 30000L

        // Prepare the time window as an 8-byte array
        val timeBuffer = ByteBuffer.allocate(8)
        timeBuffer.putLong(0, timeWindow)
        val timeBytes = timeBuffer.array()

        // Initialize HMAC-SHA1
        val mac = Mac.getInstance("HmacSHA1")
        val key = SecretKeySpec(secretBytes, "HmacSHA1")
        mac.init(key)

        // Compute hash
        val hash = mac.doFinal(timeBytes)

        // Dynamic truncation
        val offset = hash[hash.size - 1].toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
                ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                ((hash[offset + 2].toInt() and 0xFF) shl 8) or
                (hash[offset + 3].toInt() and 0xFF)

        // 6-digit TOTP
        val otp = binary % 10.0.pow(6.0).toInt()

        return String.format("%06d", otp)
    }

    private fun decodeBase32(base32: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var cleanBase32 = base32.uppercase().replace(" ", "").replace("-", "")
        cleanBase32 = cleanBase32.trimEnd('=')

        var buffer = 0
        var bitsLeft = 0
        val result = mutableListOf<Byte>()

        for (char in cleanBase32) {
            val index = alphabet.indexOf(char)
            if (index < 0) throw IllegalArgumentException("Invalid Base32 character: $char")
            buffer = (buffer shl 5) or index
            bitsLeft += 5

            if (bitsLeft >= 8) {
                result.add((buffer shr (bitsLeft - 8)).toByte())
                bitsLeft -= 8
            }
        }

        return result.toByteArray()
    }
}
