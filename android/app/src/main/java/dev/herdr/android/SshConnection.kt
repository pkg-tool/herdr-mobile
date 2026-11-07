package dev.herdr.android

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject

/** A profile owns its SSH session; each JSON request owns a fresh exec channel. */
class SshConnection(private val profile: ServerProfile) : HerdrTransport {
    private val mutex = Mutex()
    @Volatile private var session: Session? = null

    suspend fun connect(password: String, privateKey: ByteArray? = null) = mutex.withLock {
        profile.validate()
        withContext(Dispatchers.IO) {
            val jsch = JSch()
            val hostKey = PinnedHostKey(profile.fingerprint)
            jsch.hostKeyRepository = hostKey
            if (privateKey != null) {
                val passphrase = password.toByteArray(Charsets.UTF_8)
                try {
                    jsch.addIdentity(profile.id, privateKey, null, passphrase)
                } finally {
                    passphrase.fill(0)
                    privateKey.fill(0)
                }
            }
            val candidate = jsch.getSession(profile.username, profile.host, profile.port)
            candidate.setConfig("StrictHostKeyChecking", "yes")
            candidate.setConfig("PreferredAuthentications", if (privateKey == null) "password" else "publickey")
            if (privateKey == null) candidate.setPassword(password)
            candidate.timeout = 15_000
            candidate.setServerAliveInterval(15_000)
            session = candidate
            try {
                runInterruptible { candidate.connect(15_000) }
            } catch (error: Throwable) {
                candidate.disconnect()
                session = null
                hostKey.rejectedFingerprint?.let { actual -> throw com.jcraft.jsch.JSchException("SSH host key mismatch. Expected ${profile.fingerprint}, received $actual. Verify the server’s key over a trusted connection before updating this server.", error) }
                throw error
            } finally {
                candidate.setPassword(null as String?)
                jsch.removeAllIdentity()
            }
        }
    }

    override suspend fun request(method: String, params: JsonObject): JsonObject = mutex.withLock {
        withContext(Dispatchers.IO) {
            val ssh = session?.takeIf { it.isConnected } ?: throw IOException("No SSH connection")
            val id = UUID.randomUUID().toString()
            val request = HerdrProtocol.request(id, method, params)
            val channel = ssh.openChannel("exec") as ChannelExec
            try {
                channel.setCommand("printf '\\036herdr-mobile-ready:1\\037\\n'; " + profile.bridgeCommand())
                channel.setPty(false)
                val input = channel.inputStream
                val output = channel.outputStream
                withTimeout(20_000) {
                    runInterruptible { channel.connect(10_000) }
                    runInterruptible { readBridgeMarker(input) }
                    runInterruptible { output.write(request); output.flush() }
                    val response = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val available = input.available()
                        if (available > 0) {
                            val count = input.read(buffer, 0, minOf(available, buffer.size))
                            if (count < 0) throw IOException("SSH connection closed before a response arrived")
                            val newline = (0 until count).firstOrNull { buffer[it] == 10.toByte() }
                            val length = newline ?: count
                            if (response.size() + length > HerdrProtocol.MAX_RESPONSE_BYTES) {
                                throw IOException("Herdr response exceeds 8 MiB")
                            }
                            response.write(buffer, 0, length)
                            if (newline != null) {
                                return@withTimeout HerdrProtocol.response(id, response.toString("UTF-8"))
                            }
                        } else {
                            if (channel.isClosed && input.available() == 0) throw IOException(
                                "Herdr returned no response (exit code ${channel.exitStatus}). Check the executable path and running session.",
                            )
                            delay(20)
                        }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    error("Unreachable")
                }
            } finally {
                channel.disconnect()
            }
        }
    }

    suspend fun openClientChannel(): SshChannel = mutex.withLock {
        withContext(Dispatchers.IO) {
            val ssh = session?.takeIf { it.isConnected } ?: throw IOException("No SSH connection")
            val channel = ssh.openChannel("exec") as ChannelExec
            try {
                channel.setCommand("printf '\\036herdr-mobile-ready:1\\037\\n'; exec ${shellQuote(profile.executable)} --session ${shellQuote(profile.session)} remote-client-bridge --idle-timeout-v1")
                channel.setPty(false)
                val input = channel.inputStream
                val output = channel.outputStream
                withTimeout(20_000) {
                    runInterruptible { channel.connect(10_000) }
                    runInterruptible { readBridgeMarker(input) }
                }
                SshChannel(input, output, channel)
            } catch (error: Throwable) {
                channel.disconnect()
                throw error
            }
        }
    }

    override fun close() {
        session?.disconnect()
        session = null
    }
}

private fun readBridgeMarker(input: InputStream) {
    val marker = "\u001eherdr-mobile-ready:1\u001f\n".toByteArray()
    var matched = 0
    repeat(65_536) {
        val next = input.read()
        if (next < 0) throw IOException("SSH connection closed before Herdr started")
        matched = if (next == marker[matched].toInt()) matched + 1 else if (next == marker[0].toInt()) 1 else 0
        if (matched == marker.size) return
    }
    throw IOException("SSH output before Herdr startup is too large")
}

class SshChannel(val input: InputStream, val output: OutputStream, private val channel: ChannelExec) : AutoCloseable {
    override fun close() = channel.disconnect()
}

internal class PinnedHostKey(private val fingerprint: String) : HostKeyRepository {
    var rejectedFingerprint: String? = null
        private set
    override fun check(host: String?, key: ByteArray): Int {
        val actual = "SHA256:" + Base64.getEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
        return if (MessageDigest.isEqual(actual.toByteArray(), fingerprint.toByteArray())) {
            HostKeyRepository.OK
        } else { rejectedFingerprint = actual; HostKeyRepository.CHANGED }
    }

    override fun getKnownHostsRepositoryID(): String = "Pinned SHA256 host key"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
    override fun remove(host: String?, type: String?) = Unit
    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
}
