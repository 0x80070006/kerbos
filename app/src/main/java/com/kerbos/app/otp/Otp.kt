package com.kerbos.app.otp

import com.kerbos.app.vault.Entry
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class OtpType { TOTP, HOTP, STEAM }

enum class Algo(val mac: String) { SHA1("HmacSHA1"), SHA256("HmacSHA256"), SHA512("HmacSHA512") }

object Base32 {
    private const val ALPHA = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun normalize(s: String) = s.uppercase().filterNot { it == ' ' || it == '-' }.trimEnd('=')

    fun isValid(s: String): Boolean = normalize(s).let { n -> n.isNotEmpty() && n.all { it in ALPHA } }

    fun decode(input: String): ByteArray {
        val s = normalize(input)
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in s) {
            val v = ALPHA.indexOf(c)
            require(v >= 0) { "Secret base32 invalide" }
            buffer = ((buffer shl 5) or v) and 0xFFFFF
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xFF)
                bits -= 8
            }
        }
        return out.toByteArray()
    }

    fun encode(data: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = ((buffer shl 8) or (b.toInt() and 0xFF)) and 0xFFFFF
            bits += 8
            while (bits >= 5) {
                sb.append(ALPHA[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(ALPHA[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }
}

object Otp {
    private const val STEAM_CHARS = "23456789BCDFGHJKMNPQRTVWXY"

    private fun hotpRaw(key: ByteArray, counter: Long, algo: Algo): Int {
        val mac = Mac.getInstance(algo.mac)
        mac.init(SecretKeySpec(key, algo.mac))
        val h = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array())
        val o = h[h.size - 1].toInt() and 0xF
        return ((h[o].toInt() and 0x7F) shl 24) or
            ((h[o + 1].toInt() and 0xFF) shl 16) or
            ((h[o + 2].toInt() and 0xFF) shl 8) or
            (h[o + 3].toInt() and 0xFF)
    }

    fun generate(e: Entry, now: Long = System.currentTimeMillis()): String = try {
        val key = Base32.decode(e.secret)
        val counter = if (e.type == OtpType.HOTP) e.counter else (now / 1000) / e.period.coerceAtLeast(1)
        val bin = hotpRaw(key, counter, e.algo)
        if (e.type == OtpType.STEAM) {
            var v = bin
            buildString {
                repeat(5) {
                    append(STEAM_CHARS[v % STEAM_CHARS.length]); v /= STEAM_CHARS.length
                }
            }
        } else {
            var mod = 1L
            repeat(e.digits) { mod *= 10 }
            (bin.toLong() % mod).toString().padStart(e.digits, '0')
        }
    } catch (ex: Exception) {
        "------"
    }

    /** Fraction of the period remaining (1 → 0) and seconds remaining. */
    fun remaining(e: Entry, now: Long = System.currentTimeMillis()): Pair<Float, Int> {
        val p = e.period.coerceAtLeast(1) * 1000L
        val left = p - (now % p)
        return (left.toFloat() / p) to ((left + 999) / 1000).toInt()
    }

    fun format(code: String): String = when (code.length) {
        6 -> code.substring(0, 3) + " " + code.substring(3)
        7 -> code.substring(0, 3) + " " + code.substring(3)
        8 -> code.substring(0, 4) + " " + code.substring(4)
        else -> code
    }
}
