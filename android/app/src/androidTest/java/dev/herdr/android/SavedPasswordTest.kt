package dev.herdr.android

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Run on an enrolled test emulator; its fingerprint sensor is driven by the host test runner. */
class SavedPasswordTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun biometricEncryptionPersistenceBindingAndForget() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("biometricTest") == "true")
        val context = ui.activity.applicationContext
        val store = PasswordStore(context)
        val profile = ServerProfile(name = "Biometric test", host = "test.invalid", username = "test", fingerprint = "SHA256:" + "A".repeat(43))
        val secret = "Disposable test password ${System.nanoTime()}"
        assertTrue("Enroll a fingerprint before running this test", ui.activity.biometricUnlock.available())
        suspend fun authorize(cipher: Cipher): Cipher = withTimeout(30_000) {
            withContext(Dispatchers.Main) { ui.activity.biometricUnlock.authenticate(cipher, "Herdr security test", "Disposable credentials only") }
        }
        try {
            val unauthorized = store.encryptionCipher(profile)
            try {
                store.encrypt(profile, unauthorized, secret)
                fail("Keystore allowed encryption without biometric authorization")
            } catch (_: GeneralSecurityException) { }
            withContext(Dispatchers.Main) {
                val attempt = async(start = CoroutineStart.UNDISPATCHED) {
                    ui.activity.biometricUnlock.authenticate(store.encryptionCipher(profile), "Cancel test", profile.name)
                }
                ui.activity.biometricUnlock.cancel()
                try { attempt.await(); fail("Cancelled prompt produced an authorized operation") }
                catch (_: CancellationException) { }
            }
            val prepared = store.prepareSave(profile)
            val encrypted = store.encrypt(profile, authorize(prepared.cipher), secret)
            val pending = PendingSavedPassword(encrypted, prepared.generation)
            assertFalse(store.contains(profile.id))
            assertTrue(store.save(profile.id, pending))
            val reloaded = PasswordStore(context)
            assertTrue(reloaded.contains(profile.id))
            val saved = reloaded.read(profile.id)
            assertEquals(encrypted, saved)
            File(context.noBackupFilesDir, "ssh-passwords").listFiles().orEmpty().forEach {
                assertFalse("Plaintext persisted", it.readText().contains(secret))
            }
            try {
                reloaded.decrypt(profile, reloaded.decryptionCipher(profile, saved), saved)
                fail("Keystore allowed decryption without biometric authorization")
            } catch (_: GeneralSecurityException) { }
            val renamed = profile.copy(name = "Renamed server")
            assertEquals(secret, reloaded.decrypt(renamed, authorize(reloaded.decryptionCipher(renamed, saved)), saved))
            val changed = profile.copy(host = "different.invalid")
            try {
                reloaded.decrypt(changed, authorize(reloaded.decryptionCipher(changed, saved)), saved)
                fail("Saved password could be used for a different server")
            } catch (_: GeneralSecurityException) { }
            val corrupted = saved.copy(ciphertext = android.util.Base64.encodeToString(ByteArray(32), android.util.Base64.NO_WRAP))
            try {
                reloaded.decrypt(profile, authorize(reloaded.decryptionCipher(profile, corrupted)), corrupted)
                fail("Tampered ciphertext was accepted")
            } catch (_: GeneralSecurityException) { }
            store.forget(profile.id)
            assertFalse(store.contains(profile.id))
            assertFalse("Forgotten password was resurrected by a late save", store.save(profile.id, pending))
            assertFalse(store.contains(profile.id))
            try { store.decryptionCipher(profile, saved); fail("Forgotten key still available") }
            catch (_: SavedPasswordUnavailable) { }
        } finally { store.forget(profile.id) }
    }
}
