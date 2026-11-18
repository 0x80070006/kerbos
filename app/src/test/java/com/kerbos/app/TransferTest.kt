package com.kerbos.app

import com.kerbos.app.otp.Algo
import com.kerbos.app.otp.Base32
import com.kerbos.app.otp.Otp
import com.kerbos.app.otp.OtpType
import com.kerbos.app.transfer.Exporter
import com.kerbos.app.transfer.Importer
import com.kerbos.app.transfer.Kdf
import com.kerbos.app.transfer.PasswordRequired
import com.kerbos.app.transfer.WrongPassword
import com.kerbos.app.vault.Entry
import com.kerbos.app.vault.Merge
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class TransferTest {
    private val noUri: (String) -> List<Entry> = { error("pas d'URI attendue") }

    private val sample = listOf(
        Entry(issuer = "GitHub", name = "dev@kerbos.app", secret = "JBSWY3DPEHPK3PXP", group = "Pro", favorite = true),
        Entry(issuer = "Steam", name = "gamer", secret = "GEZDGNBVGY3TQOJQ", type = OtpType.STEAM, digits = 5),
        Entry(issuer = "Bank", name = "moi", secret = "KRSXG5CTMVRXEZLU", algo = Algo.SHA256, digits = 8, period = 60),
        Entry(issuer = "HOTP Co", name = "x", secret = "MFRGGZDFMZTWQ2LK", type = OtpType.HOTP, counter = 42),
    )

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun assertSameAccounts(expected: List<Entry>, actual: List<Entry>) {
        assertEquals(expected.size, actual.size)
        for ((e, a) in expected.zip(actual)) {
            assertEquals(e.issuer, a.issuer); assertEquals(e.name, a.name); assertEquals(e.secret, a.secret)
            assertEquals(e.type, a.type); assertEquals(e.algo, a.algo); assertEquals(e.digits, a.digits)
            if (e.type == OtpType.HOTP) assertEquals(e.counter, a.counter) else assertEquals(e.period, a.period)
        }
    }

    @Test fun scryptRfc7914Vector() {
        val dk = Kdf.scrypt("password".toByteArray(), "NaCl".toByteArray(), 1024, 8, 16, 64)
        assertEquals(
            "fdbabe1c9d3472007856e7190d01e9fe7c6ad7cbc8237830e77376634b373162" +
                "2eaf30d92e22a3886ff109279d9830dac727afb94a83ee6d8360cbdfa2cc0640",
            hex(dk),
        )
    }

    @Test fun totpRfc6238Vector() {
        val e = Entry(secret = Base32.encode("12345678901234567890".toByteArray()), digits = 8)
        assertEquals("94287082", Otp.generate(e, 59_000L))
        assertEquals("07081804", Otp.generate(e, 1111111109_000L))
    }

    @Test fun aegisPlainRoundTrip() {
        val r = Importer.parse(Exporter.aegisPlain(sample).toByteArray(), null, noUri)
        assertEquals("Aegis", r.source)
        assertSameAccounts(sample, r.entries)
        assertEquals("Pro", r.entries[0].group)
        assertTrue(r.entries[0].favorite)
    }

    @Test fun aegisEncryptedRoundTrip() {
        val file = Exporter.aegisEncrypted(sample, "s3cret-pw", n = 1024).toByteArray()
        try { Importer.parse(file, null, noUri); fail() } catch (e: PasswordRequired) {}
        try { Importer.parse(file, "wrong", noUri); fail() } catch (e: WrongPassword) {}
        assertSameAccounts(sample, Importer.parse(file, "s3cret-pw", noUri).entries)
    }

    @Test fun kerbosBackupRoundTrip() {
        val file = Exporter.kerbos(sample, "pw-123456").toByteArray()
        try { Importer.parse(file, "nope", noUri); fail() } catch (e: WrongPassword) {}
        assertSameAccounts(sample, Importer.parse(file, "pw-123456", noUri).entries)
    }

    @Test fun twoFasEncrypted() {
        val services = JSONArray().put(
            JSONObject().put("name", "GitHub").put("secret", "JBSWY3DPEHPK3PXP")
                .put("otp", JSONObject().put("account", "dev").put("digits", 6).put("period", 30).put("algorithm", "SHA1").put("tokenType", "TOTP"))
        )
        val rnd = SecureRandom()
        val salt = ByteArray(256).also(rnd::nextBytes); val iv = ByteArray(12).also(rnd::nextBytes)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec("pw".toCharArray(), salt, 10_000, 256)).encoded
        val ct = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)); doFinal(services.toString().toByteArray())
        }
        val b = Base64.getEncoder()
        val file = JSONObject().put("schemaVersion", 4).put("services", JSONArray())
            .put("servicesEncrypted", b.encodeToString(ct) + ":" + b.encodeToString(salt) + ":" + b.encodeToString(iv)).toString()
        val r = Importer.parse(file.toByteArray(), "pw", noUri)
        assertEquals("2FAS", r.source)
        assertEquals("GitHub", r.entries.single().issuer)
        assertEquals("dev", r.entries.single().name)
    }

    @Test fun andOtpEncrypted() {
        val json = JSONArray().put(
            JSONObject().put("secret", "JBSWY3DPEHPK3PXP").put("issuer", "Proton").put("label", "me").put("type", "TOTP")
                .put("algorithm", "SHA1").put("digits", 6).put("period", 30).put("tags", JSONArray().put("Perso"))
        ).toString()
        val rnd = SecureRandom()
        val iter = 1000
        val salt = ByteArray(12).also(rnd::nextBytes); val nonce = ByteArray(12).also(rnd::nextBytes)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(PBEKeySpec("pw".toCharArray(), salt, iter, 256)).encoded
        val ct = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); doFinal(json.toByteArray())
        }
        val file = ByteBuffer.allocate(4).putInt(iter).array() + salt + nonce + ct
        val r = Importer.parse(file, "pw", noUri)
        assertEquals("andOTP", r.source)
        assertEquals("Proton", r.entries.single().issuer)
        assertEquals("Perso", r.entries.single().group)
    }

    @Test fun freeOtpPlus() {
        val secret = JSONArray(); "Hello!".toByteArray().forEach { secret.put(it.toInt()) }
        val file = JSONObject().put("tokens", JSONArray().put(
            JSONObject().put("type", "TOTP").put("algo", "SHA1").put("digits", 6).put("period", 30)
                .put("issuerExt", "Mastodon").put("label", "@me").put("secret", secret)
        )).toString()
        val r = Importer.parse(file.toByteArray(), null, noUri)
        assertEquals("Mastodon", r.entries.single().issuer)
        assertEquals(Base32.encode("Hello!".toByteArray()), r.entries.single().secret)
    }

    @Test fun sameAccountOnTwoDevicesIsCollapsedIdentically() {
        // Device A and device B each added GitHub (same secret) before syncing; B also has Proton.
        val ghA = Entry(uuid = "aaaa", issuer = "GitHub", name = "dev", secret = "JBSWY3DPEHPK3PXP", createdAt = 100, updatedAt = 100, icon = "si:github")
        val ghB = Entry(uuid = "bbbb", issuer = "GitHub", name = "dev", secret = "jbsw y3dp ehpk 3pxp", createdAt = 200, updatedAt = 200, group = "Pro")
        val proton = Entry(uuid = "cccc", issuer = "Proton", secret = "KRSXG5CTMVRXEZLU", updatedAt = 150)
        val onA = Merge.merge(listOf(ghA), listOf(ghB, proton), now = 1000)
        val onB = Merge.merge(listOf(ghB, proton), listOf(ghA), now = 1000)
        assertEquals(onA.toSet(), onB.toSet())
        val live = onA.filter { !it.deleted }
        assertEquals(2, live.size)
        val gh = live.single { it.issuer == "GitHub" }
        assertEquals("aaaa", gh.uuid)          // smallest uuid kept
        assertEquals("Pro", gh.group)          // latest copy's data
        assertEquals("si:github", gh.icon)     // completed from the other copy
        assertTrue(onA.single { it.uuid == "bbbb" }.deleted)
        // Idempotent: merging again changes nothing, and an old copy cannot come back.
        assertTrue(Merge.same(onA, Merge.merge(onA, listOf(ghA, ghB), now = 1000)))
    }

    @Test fun etagNormalisation() {
        assertEquals("\"abc\"", com.kerbos.app.sync.WebDav.cleanEtag("W/\"abc\""))
        assertEquals("\"abc\"", com.kerbos.app.sync.WebDav.cleanEtag("\"abc-gzip\""))
        assertEquals("\"abc\"", com.kerbos.app.sync.WebDav.cleanEtag("abc"))
        assertEquals(null, com.kerbos.app.sync.WebDav.cleanEtag(null))
    }

    @Test fun mergeKeepsBothSidesAndDeletions() {
        val a = Entry(issuer = "A", secret = "JBSWY3DPEHPK3PXP", updatedAt = 10)
        val b = Entry(issuer = "B", secret = "JBSWY3DPEHPK3PXP", updatedAt = 11)
        val c = Entry(issuer = "C", secret = "JBSWY3DPEHPK3PXP", updatedAt = 5)
        val cDeleted = c.tombstone(20)
        val bRenamed = b.copy(issuer = "B2", updatedAt = 30)
        val merged = Merge.merge(listOf(a, b, c), listOf(bRenamed, cDeleted), now = 100)
        assertEquals(3, merged.size)
        assertEquals("B2", merged.first { it.uuid == b.uuid }.issuer)
        assertTrue(merged.first { it.uuid == c.uuid }.deleted)
        assertEquals(Merge.merge(listOf(a, b, c), listOf(bRenamed, cDeleted), 100).toSet(),
            Merge.merge(listOf(bRenamed, cDeleted), listOf(a, b, c), 100).toSet())
    }
}
