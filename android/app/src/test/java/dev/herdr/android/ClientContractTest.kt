package dev.herdr.android

import com.jcraft.jsch.HostKeyRepository
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientContractTest {
    private val agent = Pane("w1:p1", "w1", "w1:t1", agent = "claude")

    @Test fun `agent prompt is JSON data and never a shell command`() = runBlocking {
        val transport = RecordingTransport("agent_prompted")
        val text = "review 'file'\n\$(touch bad); `id`"
        HerdrClient(transport).send(agent, text)
        val (method, params) = transport.requests.single()
        assertEquals("agent.prompt", method)
        assertEquals("w1:p1", params.getValue("target").jsonPrimitive.content)
        assertEquals(text, params.getValue("text").jsonPrimitive.content)
        val bytes = HerdrProtocol.request("one", method, params)
        assertEquals(1, bytes.count { it == 10.toByte() })
        val envelope = wireJson.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        assertEquals(text, envelope.getValue("params").jsonObject.getValue("text").jsonPrimitive.content)
    }

    @Test fun `blocked agent is not retried as raw pane input`() = runBlocking {
        val transport = RecordingTransport("ok", ApiException("agent_blocked", "approval required"))
        try {
            HerdrClient(transport).send(agent, "hello")
            throw AssertionError("Expected rejection")
        } catch (error: ApiException) {
            assertEquals("agent_blocked", error.code)
        }
        assertEquals(listOf("agent.prompt"), transport.requests.map { it.first })
    }

    @Test fun `uncertain mutation is never retried`() = runBlocking {
        val transport = RecordingTransport("ok", IOException("connection lost"))
        try {
            HerdrClient(transport).send(agent, "run")
            throw AssertionError("Expected failure")
        } catch (_: IOException) { }
        assertEquals(1, transport.requests.size)
    }

    @Test fun `shell input submits text and enter in one request`() = runBlocking {
        val transport = RecordingTransport("ok")
        HerdrClient(transport).send(agent.copy(agent = null), "pwd")
        val (method, params) = transport.requests.single()
        assertEquals("pane.send_input", method)
        assertEquals("w1:p1", params.getValue("pane_id").jsonPrimitive.content)
        assertEquals("Enter", params.getValue("keys").jsonArray.single().jsonPrimitive.content)
    }

    @Test fun `explicit keys use the agent endpoint when an agent is present`() = runBlocking {
        val transport = RecordingTransport("ok")
        HerdrClient(transport).key(agent, "esc")
        assertEquals("agent.send_keys", transport.requests.single().first)
        assertEquals("esc", transport.requests.single().second.getValue("keys").jsonArray.single().jsonPrimitive.content)
    }

    @Test fun `server error and mismatched request are rejected`() {
        try {
            HerdrProtocol.response("a", """{"id":"a","error":{"code":"pane_not_found","message":"gone"}}""")
            throw AssertionError("Expected server error")
        } catch (error: ApiException) {
            assertEquals("pane_not_found", error.code)
        }
        try {
            HerdrProtocol.response("a", """{"id":"b","result":{"type":"ok"}}""")
            throw AssertionError("Expected mismatched ID rejection")
        } catch (_: IOException) { }
        assertEquals("ok", HerdrProtocol.response("a", """{"id":"a","result":{"type":"ok","future":true}}""")
            .getValue("type").jsonPrimitive.content)
    }

    @Test fun `oversized requests are rejected before transport`() {
        try {
            HerdrProtocol.request("a", "agent.prompt", buildJsonObject { put("text", "я".repeat(600_000)) })
            throw AssertionError("Expected size limit")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun `host key pin refuses another server key`() {
        val key = "host public key bytes".toByteArray()
        val fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
        val repository = PinnedHostKey(fingerprint)
        assertEquals(HostKeyRepository.OK, repository.check("host", key))
        assertEquals(HostKeyRepository.CHANGED, repository.check("host", "different".toByteArray()))
    }

    @Test fun `profile validates port fingerprint and host`() {
        val profile = profile()
        profile.validate()
        listOf(profile.copy(port = 0), profile.copy(host = "ssh://host"), profile.copy(fingerprint = ""),
            profile.copy(executable = "herdr\nother")).forEach { invalid ->
            try {
                invalid.validate()
                throw AssertionError("Expected invalid profile")
            } catch (_: IllegalArgumentException) { }
        }
        assertFalse(profile.id == profile().id)
    }

    @Test fun `bridge command quotes executable and session`() {
        assertEquals("exec '.local/bin/herdr' --session 'default' remote-api-bridge", profile().bridgeCommand())
        assertEquals("'a'\"'\"'b'", shellQuote("a'b"))
        assertTrue(profile().copy(session = "\$(id); hello").bridgeCommand().contains("'\$(id); hello'"))
    }

    private fun profile() = ServerProfile(name = "One", host = "example.org", username = "me", fingerprint = "SHA256:" + "a".repeat(43))

    private class RecordingTransport(private val type: String, private val failure: Exception? = null) : HerdrTransport {
        val requests = mutableListOf<Pair<String, JsonObject>>()
        override suspend fun request(method: String, params: JsonObject): JsonObject {
            requests += method to params
            failure?.let { throw it }
            return buildJsonObject { put("type", type) }
        }
        override fun close() = Unit
    }
}
