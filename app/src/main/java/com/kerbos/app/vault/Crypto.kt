package com.kerbos.app.vault

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object Crypto {
    const val PBKDF2_ITER = 210_000
    private val rng = SecureRandom()

    fun random(n: Int) = ByteArray(n).also { rng.nextBytes(it) }

    fun pbkdf2(password: CharArray, salt: ByteArray, iter: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iter, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    class Sealed(val nonce: ByteArray, val ct: ByteArray)

    fun encrypt(key: ByteArray, pt: ByteArray, aad: String? = null): Sealed {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        val n = random(12)
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, n))
        aad?.let { c.updateAAD(it.toByteArray()) }
        return Sealed(n, c.doFinal(pt))
    }

    fun decrypt(key: ByteArray, nonce: ByteArray, ct: ByteArray, aad: String? = null): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        aad?.let { c.updateAAD(it.toByteArray()) }
        return c.doFinal(ct)
    }

    fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
}

/** Android Keystore key bound to strong biometrics, used to wrap the vault master key. */
object BiometricKey {
    private const val ALIAS = "kerbos_biometric_v1"

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun create(): SecretKey {
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val b = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= 30) b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        kg.init(b.build())
        return kg.generateKey()
    }

    fun delete() {
        runCatching { keyStore().deleteEntry(ALIAS) }
    }

    fun encryptCipher(): Cipher {
        delete()
        val k = create()
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, k) }
    }

    /** Returns null when the key is missing or was invalidated (e.g. new fingerprint enrolled). */
    fun decryptCipher(nonce: ByteArray): Cipher? = try {
        val k = keyStore().getKey(ALIAS, null) as? SecretKey
        k?.let { Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, it, GCMParameterSpec(128, nonce)) } }
    } catch (e: KeyPermanentlyInvalidatedException) {
        null
    } catch (e: Exception) {
        null
    }
}

object Biometric {
    fun available(ctx: Context) =
        BiometricManager.from(ctx).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    fun isCancel(code: Int) = code == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
        code == BiometricPrompt.ERROR_USER_CANCELED || code == BiometricPrompt.ERROR_CANCELED

    fun prompt(
        activity: FragmentActivity,
        cipher: Cipher,
        title: String,
        subtitle: String,
        negative: String,
        onSuccess: (Cipher) -> Unit,
        onError: (Int, String) -> Unit,
    ) {
        val p = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    result.cryptoObject?.cipher?.let(onSuccess)
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onError(errorCode, errString.toString())
                }
            })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(negative)
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setConfirmationRequired(false)
            .build()
        p.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }
}
