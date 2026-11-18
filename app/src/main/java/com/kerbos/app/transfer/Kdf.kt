package com.kerbos.app.transfer

import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** PBKDF2 / scrypt on raw bytes (no platform charset surprises), pure JVM. */
object Kdf {
    fun pbkdf2(macAlg: String, password: ByteArray, salt: ByteArray, iterations: Int, keyLen: Int): ByteArray {
        val mac = Mac.getInstance(macAlg)
        mac.init(if (password.isEmpty()) EmptyKey(macAlg) else SecretKeySpec(password, macAlg))
        val hLen = mac.macLength
        val out = ByteArray(keyLen)
        var block = 1
        var off = 0
        while (off < keyLen) {
            mac.update(salt)
            mac.update(ByteBuffer.allocate(4).putInt(block).array())
            var u = mac.doFinal()
            val t = u.copyOf()
            for (i in 1 until iterations) {
                u = mac.doFinal(u)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            val n = minOf(hLen, keyLen - off)
            System.arraycopy(t, 0, out, off, n)
            off += n; block++
        }
        return out
    }

    /** SecretKeySpec refuses empty keys; HMAC with an empty key is still well defined. */
    private class EmptyKey(private val alg: String) : javax.crypto.SecretKey {
        override fun getAlgorithm() = alg
        override fun getFormat() = "RAW"
        override fun getEncoded() = ByteArray(0)
    }

    // ---------------- scrypt (RFC 7914) ----------------

    fun scrypt(password: ByteArray, salt: ByteArray, n: Int, r: Int, p: Int, dkLen: Int): ByteArray {
        require(n > 1 && n and (n - 1) == 0) { "N doit être une puissance de 2" }
        val blockLen = 128 * r
        val b = pbkdf2("HmacSHA256", password, salt, 1, p * blockLen)
        val xy = IntArray(64 * r)
        val v = IntArray(32 * r * n)
        for (i in 0 until p) smix(b, i * blockLen, r, n, v, xy)
        return pbkdf2("HmacSHA256", password, b, 1, dkLen)
    }

    private fun smix(b: ByteArray, bi: Int, r: Int, n: Int, v: IntArray, xy: IntArray) {
        val words = 32 * r
        val x = IntArray(words)
        for (k in 0 until words) {
            val o = bi + k * 4
            x[k] = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
                ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)
        }
        for (i in 0 until n) {
            System.arraycopy(x, 0, v, i * words, words)
            blockMix(x, r, xy)
        }
        for (i in 0 until n) {
            val j = x[(2 * r - 1) * 16] and (n - 1)
            val vo = j * words
            for (k in 0 until words) x[k] = x[k] xor v[vo + k]
            blockMix(x, r, xy)
        }
        for (k in 0 until words) {
            val o = bi + k * 4
            val w = x[k]
            b[o] = w.toByte(); b[o + 1] = (w ushr 8).toByte(); b[o + 2] = (w ushr 16).toByte(); b[o + 3] = (w ushr 24).toByte()
        }
    }

    private fun blockMix(b: IntArray, r: Int, y: IntArray) {
        val x = IntArray(16)
        System.arraycopy(b, (2 * r - 1) * 16, x, 0, 16)
        for (i in 0 until 2 * r) {
            for (k in 0 until 16) x[k] = x[k] xor b[i * 16 + k]
            salsa208(x)
            System.arraycopy(x, 0, y, i * 16, 16)
        }
        for (i in 0 until r) System.arraycopy(y, (2 * i) * 16, b, i * 16, 16)
        for (i in 0 until r) System.arraycopy(y, (2 * i + 1) * 16, b, (i + r) * 16, 16)
    }

    private fun rl(a: Int, b: Int) = Integer.rotateLeft(a, b)

    private fun salsa208(b: IntArray) {
        val x = b.copyOf()
        repeat(4) {
            x[4] = x[4] xor rl(x[0] + x[12], 7); x[8] = x[8] xor rl(x[4] + x[0], 9)
            x[12] = x[12] xor rl(x[8] + x[4], 13); x[0] = x[0] xor rl(x[12] + x[8], 18)
            x[9] = x[9] xor rl(x[5] + x[1], 7); x[13] = x[13] xor rl(x[9] + x[5], 9)
            x[1] = x[1] xor rl(x[13] + x[9], 13); x[5] = x[5] xor rl(x[1] + x[13], 18)
            x[14] = x[14] xor rl(x[10] + x[6], 7); x[2] = x[2] xor rl(x[14] + x[10], 9)
            x[6] = x[6] xor rl(x[2] + x[14], 13); x[10] = x[10] xor rl(x[6] + x[2], 18)
            x[3] = x[3] xor rl(x[15] + x[11], 7); x[7] = x[7] xor rl(x[3] + x[15], 9)
            x[11] = x[11] xor rl(x[7] + x[3], 13); x[15] = x[15] xor rl(x[11] + x[7], 18)
            x[1] = x[1] xor rl(x[0] + x[3], 7); x[2] = x[2] xor rl(x[1] + x[0], 9)
            x[3] = x[3] xor rl(x[2] + x[1], 13); x[0] = x[0] xor rl(x[3] + x[2], 18)
            x[6] = x[6] xor rl(x[5] + x[4], 7); x[7] = x[7] xor rl(x[6] + x[5], 9)
            x[4] = x[4] xor rl(x[7] + x[6], 13); x[5] = x[5] xor rl(x[4] + x[7], 18)
            x[11] = x[11] xor rl(x[10] + x[9], 7); x[8] = x[8] xor rl(x[11] + x[10], 9)
            x[9] = x[9] xor rl(x[8] + x[11], 13); x[10] = x[10] xor rl(x[9] + x[8], 18)
            x[12] = x[12] xor rl(x[15] + x[14], 7); x[13] = x[13] xor rl(x[12] + x[15], 9)
            x[14] = x[14] xor rl(x[13] + x[12], 13); x[15] = x[15] xor rl(x[14] + x[13], 18)
        }
        for (i in 0 until 16) b[i] += x[i]
    }
}
