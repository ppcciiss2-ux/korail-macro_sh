package com.korailmacro.app.korail

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Adapted from devgyurak/pykorail a9ceed7, auth/dynapath.py.
 * Copyright (c) 2026 devgyurak and pykorail contributors.
 * Subject to licenses/pykorail.txt, including its Commercial Gain Restriction.
 * Software declaration only; does not implement hardware attestation.
 */
internal data class DeviceFacts(val release: String, val model: String, val manufacturer: String,
    val board: String, val sdk: Int, val strongBox: Boolean, val id: String)

internal object DeclarationToken {
    // Public protocol constant from the upstream codec, not a user's credential.
    private val protocolConstant = "9a330fd663b62c5f888a7ce76ac7202d93f646b4e0bd605cd4183278ca4ca888".toByteArray(Charsets.US_ASCII)
    private const val alphabet = "TeFOuQBxj1qRUHwKkymSY8l3psvCzAL5tofNcihg9WDG072EZ4bVad6rJnPXIM"
    private val random = SecureRandom()
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun le(n: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array()
    private fun be(n: Int) = ByteBuffer.allocate(4).putInt(n).array()
    private fun b64(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    fun generate(facts: DeviceFacts, timestamp: Long = System.currentTimeMillis(), nonce: Int = random.nextInt()): String {
        require(facts.id.matches(Regex("[0-9a-fA-F]{16}")))
        val plain = ("!v=2&es=288904575606514493&ts=$timestamp&st=Android&sv=2.0.1&ai=korailplus" +
            "&os=${b64(facts.release)}&dm=${b64(facts.model)}&iid=${b64(facts.id)}" +
            "&mf=${b64(facts.manufacturer)}&bd=${b64(facts.board)}&sl=${facts.sdk}&sb=${if (facts.strongBox) 1 else 0}" +
            "&kl=990094375841594426&ec=978212327729758430").toByteArray(Charsets.US_ASCII)
        return encode(plain, nonce)
    }

    internal fun encode(plain: ByteArray, nonce: Int): String {
        val nonceBytes = le(nonce)
        val encrypted = ByteArray(plain.size)
        for (block in 0 until (plain.size + 31) / 32) {
            val stream = hash(protocolConstant + nonceBytes + be(block))
            for (offset in 0 until minOf(32, plain.size - block * 32)) {
                val i = block * 32 + offset
                encrypted[i] = (plain[i].toInt() xor stream[offset].toInt()).toByte()
            }
        }
        val frame = nonceBytes + hash(protocolConstant + nonceBytes + encrypted).copyOf(3) + encrypted
        return buildString {
            append("201")
            for (offset in frame.indices step 4) {
                val count = minOf(4, frame.size - offset)
                var word = 0L
                for (i in 0 until count) word = (word shl 8) or (frame[offset + i].toLong() and 255)
                val width = intArrayOf(0, 2, 3, 5, 6)[count]
                val encoded = CharArray(width) { alphabet[0] }
                for (i in width - 1 downTo 0) { encoded[i] = alphabet[(word % 62).toInt()]; word /= 62 }
                append(encoded.concatToString())
            }
        }
    }
}
