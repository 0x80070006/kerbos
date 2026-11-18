package com.kerbos.app.otp

import android.net.Uri
import android.util.Base64
import com.kerbos.app.vault.Entry

/** Parses otpauth:// URIs and Google Authenticator otpauth-migration:// exports. */
object OtpUri {

    fun parse(text: String): List<Entry> {
        val t = text.trim()
        val uri = Uri.parse(t)
        return when (uri.scheme?.lowercase()) {
            "otpauth" -> listOf(parseSingle(uri))
            "otpauth-migration" -> parseMigration(uri)
            else -> throw IllegalArgumentException("Ce QR code n'est pas un code OTP")
        }
    }

    private fun parseSingle(uri: Uri): Entry {
        var type = when (uri.host?.lowercase()) {
            "totp" -> OtpType.TOTP
            "hotp" -> OtpType.HOTP
            "steam" -> OtpType.STEAM
            else -> throw IllegalArgumentException("Type OTP inconnu")
        }
        val label = uri.path?.removePrefix("/") ?: ""
        var issuer = uri.getQueryParameter("issuer")?.trim() ?: ""
        var name = label.trim()
        val idx = label.indexOf(':')
        if (idx >= 0) {
            val li = label.substring(0, idx).trim()
            name = label.substring(idx + 1).trim()
            if (issuer.isEmpty()) issuer = li
        }
        val secret = uri.getQueryParameter("secret")?.let { Base32.normalize(it) }
            ?: throw IllegalArgumentException("Secret manquant")
        require(Base32.isValid(secret)) { "Secret invalide" }
        val algo = when (uri.getQueryParameter("algorithm")?.uppercase()) {
            "SHA256" -> Algo.SHA256
            "SHA512" -> Algo.SHA512
            else -> Algo.SHA1
        }
        var digits = uri.getQueryParameter("digits")?.toIntOrNull() ?: 6
        val period = uri.getQueryParameter("period")?.toIntOrNull()?.takeIf { it > 0 } ?: 30
        val counter = uri.getQueryParameter("counter")?.toLongOrNull() ?: 0L
        if (uri.getQueryParameter("encoder")?.lowercase() == "steam") type = OtpType.STEAM
        if (type == OtpType.STEAM) digits = 5
        return Entry(
            type = type, name = name, issuer = issuer, secret = secret, algo = algo,
            digits = digits.coerceIn(4, 10), period = period, counter = counter,
        )
    }

    fun toUri(e: Entry): String {
        val label = Uri.encode(if (e.issuer.isNotEmpty()) "${e.issuer}:${e.name}" else e.name)
        val host = when (e.type) { OtpType.TOTP -> "totp"; OtpType.HOTP -> "hotp"; OtpType.STEAM -> "steam" }
        val sb = StringBuilder("otpauth://$host/$label?secret=${e.secret}")
        if (e.issuer.isNotEmpty()) sb.append("&issuer=").append(Uri.encode(e.issuer))
        sb.append("&algorithm=").append(e.algo.name).append("&digits=").append(e.digits)
        if (e.type == OtpType.HOTP) sb.append("&counter=").append(e.counter) else sb.append("&period=").append(e.period)
        return sb.toString()
    }

    // ---- Google Authenticator export (protobuf) ----

    private class Pb(val b: ByteArray, var p: Int = 0, val end: Int = b.size) {
        fun has() = p < end
        fun varint(): Long {
            var r = 0L; var s = 0
            while (true) {
                val x = b[p++].toInt() and 0xFF
                r = r or ((x and 0x7F).toLong() shl s)
                if (x and 0x80 == 0) return r
                s += 7
            }
        }
        fun bytes(): ByteArray { val n = varint().toInt(); val r = b.copyOfRange(p, p + n); p += n; return r }
        fun skip(wt: Int) {
            when (wt) {
                0 -> varint(); 1 -> p += 8; 2 -> { val n = varint().toInt(); p += n }; 5 -> p += 4
                else -> throw IllegalArgumentException("protobuf invalide")
            }
        }
    }

    private fun parseMigration(uri: Uri): List<Entry> {
        val q = uri.encodedQuery ?: throw IllegalArgumentException("Export vide")
        val raw = q.split('&').firstOrNull { it.startsWith("data=") }?.substring(5)
            ?: throw IllegalArgumentException("Export vide")
        val b64 = Uri.decode(raw).replace(' ', '+')
        val data = Base64.decode(b64, Base64.DEFAULT)
        val root = Pb(data)
        val out = mutableListOf<Entry>()
        while (root.has()) {
            val tag = root.varint().toInt()
            if (tag shr 3 == 1 && tag and 7 == 2) {
                val msg = root.bytes()
                val pb = Pb(msg)
                var secret = ByteArray(0); var name = ""; var issuer = ""
                var algo = Algo.SHA1; var digits = 6; var type = OtpType.TOTP; var counter = 0L
                while (pb.has()) {
                    val t = pb.varint().toInt()
                    when (t shr 3) {
                        1 -> secret = pb.bytes()
                        2 -> name = String(pb.bytes())
                        3 -> issuer = String(pb.bytes())
                        4 -> algo = when (pb.varint().toInt()) { 2 -> Algo.SHA256; 3 -> Algo.SHA512; else -> Algo.SHA1 }
                        5 -> digits = if (pb.varint().toInt() == 2) 8 else 6
                        6 -> type = if (pb.varint().toInt() == 1) OtpType.HOTP else OtpType.TOTP
                        7 -> counter = pb.varint()
                        else -> pb.skip(t and 7)
                    }
                }
                val idx = name.indexOf(':')
                if (idx >= 0) {
                    if (issuer.isEmpty()) issuer = name.substring(0, idx).trim()
                    name = name.substring(idx + 1).trim()
                }
                out += Entry(
                    type = type, name = name, issuer = issuer, secret = Base32.encode(secret),
                    algo = algo, digits = digits, counter = counter,
                )
            } else root.skip(tag and 7)
        }
        require(out.isNotEmpty()) { "Aucun compte dans l'export" }
        return out
    }
}
