package dev.herdr.android

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import javax.crypto.Cipher
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

/** Activity-owned prompt; leaving the Activity cancels an unfinished operation. */
class BiometricUnlock(private val activity: FragmentActivity) {
    private var finished: CompletableDeferred<Unit>? = null
    private var expectedCipher: Cipher? = null
    private var pending: CancellableContinuation<Cipher>? = null
    private val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val continuation = pending ?: return
                val cipher = result.cryptoObject?.cipher
                if (cipher !== expectedCipher) return
                pending = null
                expectedCipher = null
                finished?.complete(Unit)
                if (cipher != null) continuation.resume(cipher)
                else continuation.resumeWithException(IllegalStateException("Biometric operation unavailable"))
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                val continuation = pending ?: return
                pending = null
                finished?.complete(Unit)
                if (errorCode in setOf(BiometricPrompt.ERROR_NEGATIVE_BUTTON, BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_CANCELED)) {
                    continuation.cancel()
                } else continuation.resumeWithException(IllegalStateException(errString.toString()))
            }
        })

    fun available(): Boolean = BiometricManager.from(activity)
        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    suspend fun authenticate(cipher: Cipher, title: String, server: String): Cipher {
        // Android delivers cancellation asynchronously. Drain it before starting the next prompt.
        if (withTimeoutOrNull(2_000) { finished?.await(); true } != true) {
            pending?.cancel()
            pending = null
            expectedCipher = null
            finished = null
            error("Biometric request interrupted. Try again or connect manually.")
        }
        return suspendCancellableCoroutine { continuation ->
            check(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                !activity.supportFragmentManager.isStateSaved) { "Authentication interrupted. Try again." }
            check(pending == null) { "A biometric request is already active" }
            pending = continuation
            expectedCipher = cipher
            finished = CompletableDeferred()
            continuation.invokeOnCancellation {
                if (pending === continuation) prompt.cancelAuthentication()
            }
            try { prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(server)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText("Cancel")
                .build(), BiometricPrompt.CryptoObject(cipher)) }
            catch (error: Exception) {
                pending = null
                finished?.complete(Unit)
                continuation.resumeWithException(error)
            }
        }
    }

    fun cancel() { pending?.cancel() }
}
