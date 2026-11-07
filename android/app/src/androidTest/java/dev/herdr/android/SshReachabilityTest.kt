package dev.herdr.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.InetSocketAddress
import java.net.Socket
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in network diagnostic; never sends credentials. */
@RunWith(AndroidJUnit4::class)
class SshReachabilityTest {
    @Test fun serverRespondsWithSshBanner() {
        val arguments = InstrumentationRegistry.getArguments()
        val host = arguments.getString("sshProbeHost")
        assumeTrue("Pass sshProbeHost to check a real server", host != null)
        val port = arguments.getString("sshProbePort")?.toInt() ?: 22
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host!!, port), 10_000)
            socket.soTimeout = 10_000
            val input = socket.getInputStream()
            val banner = StringBuilder()
            while (banner.length < 255) {
                val byte = input.read()
                if (byte == -1 || byte == 10) break
                banner.append(byte.toChar())
            }
            assertTrue("Expected an SSH-2.0 server banner", banner.startsWith("SSH-2.0-"))
        }
    }
}
