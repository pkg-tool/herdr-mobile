package dev.herdr.android

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class EncryptedPassword(val iv: String, val ciphertext: String)

/** Only ciphertext is persisted. Each operation needs a fresh, cryptographically bound biometric. */
data class PendingSavedPassword(val encrypted: EncryptedPassword, val generation: Long)
data class PasswordEncryption(val cipher: Cipher, val generation: Long)
class SavedPasswordUnavailable : java.security.GeneralSecurityException("Saved password is unavailable")

class PasswordStore(context: Context) {
    private val generations = mutableMapOf<String, Long>()
    private val directory = File(context.noBackupFilesDir, "ssh-passwords")
    private val keyStore by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }
    private fun token(id: String) = MessageDigest.getInstance("SHA-256")
        .digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun alias(id: String) = "herdr.ssh.password.v1.${token(id)}"
    private fun file(id: String) = AtomicFile(File(directory, token(id)))
    private fun binding(profile: ServerProfile) = Json.encodeToString(profile.copy(name = "")).toByteArray()

    @Synchronized fun contains(id: String): Boolean = file(id).baseFile.exists()

    @Synchronized fun prepareSave(profile: ServerProfile): PasswordEncryption {
        val cipher = try { encryptionCipher(profile) }
        catch (_: android.security.keystore.KeyPermanentlyInvalidatedException) {
            forget(profile.id)
            encryptionCipher(profile)
        }
        return PasswordEncryption(cipher, generations[profile.id] ?: 0L)
    }

    @Synchronized fun encryptionCipher(profile: ServerProfile): Cipher {
        val name = alias(profile.id)
        val key = (keyStore.getKey(name, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
            val spec = KeyGenParameterSpec.Builder(name, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
            if (Build.VERSION.SDK_INT >= 30) spec.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            else {
                @Suppress("DEPRECATION")
                spec.setUserAuthenticationValidityDurationSeconds(-1)
            }
            init(spec.build())
            generateKey()
        }
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key)
        }
    }

    @Synchronized fun read(id: String): EncryptedPassword = Json.decodeFromString(file(id).readFully().toString(Charsets.UTF_8))

    @Synchronized fun decryptionCipher(profile: ServerProfile, saved: EncryptedPassword): Cipher {
        val key = keyStore.getKey(alias(profile.id), null) as? SecretKey
            ?: throw SavedPasswordUnavailable()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(saved.iv, Base64.NO_WRAP)))
        }
    }

    fun encrypt(profile: ServerProfile, cipher: Cipher, password: String): EncryptedPassword {
        cipher.updateAAD(binding(profile))
        val bytes = password.toByteArray(Charsets.UTF_8)
        return try {
            EncryptedPassword(Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
                Base64.encodeToString(cipher.doFinal(bytes), Base64.NO_WRAP))
        } finally { bytes.fill(0) }
    }

    fun decrypt(profile: ServerProfile, cipher: Cipher, saved: EncryptedPassword): String {
        cipher.updateAAD(binding(profile))
        val bytes = cipher.doFinal(Base64.decode(saved.ciphertext, Base64.NO_WRAP))
        return try { bytes.toString(Charsets.UTF_8) } finally { bytes.fill(0) }
    }

    @Synchronized fun save(id: String, pending: PendingSavedPassword): Boolean {
        if (pending.generation != (generations[id] ?: 0L)) return false
        val saved = pending.encrypted
        check(directory.isDirectory || directory.mkdirs()) { "Could not create password storage" }
        val target = file(id)
        val output = target.startWrite()
        try {
            output.write(Json.encodeToString(saved).toByteArray())
            target.finishWrite(output)
            return true
        } catch (error: Exception) {
            target.failWrite(output)
            throw error
        }
    }

    @Synchronized fun forget(id: String) {
        generations[id] = (generations[id] ?: 0L) + 1L
        // Destroy the decryption key first, even if deleting the ciphertext fails.
        keyStore.deleteEntry(alias(id))
        file(id).delete()
    }
}
