package dev.herdr.android

import java.io.IOException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add

class HerdrClient(private val connection: HerdrTransport) : AutoCloseable {
    suspend fun snapshot(): Snapshot {
        val result = connection.request("session.snapshot", buildJsonObject {})
        result.expect("session_snapshot")
        return wireJson.decodeFromJsonElement(result.getValue("snapshot"))
    }

    suspend fun read(paneId: String, history: Boolean = false): PaneOutput {
        val result = connection.request("pane.read", buildJsonObject {
            put("pane_id", paneId)
            put("source", if (history) "recent_unwrapped" else "visible")
            put("lines", if (history) 500 else 200)
            put("format", "text")
            put("strip_ansi", true)
        })
        result.expect("pane_read")
        val output = wireJson.decodeFromJsonElement<PaneOutput>(result.getValue("read"))
        if (output.paneId != paneId) throw IOException("Server returned output from a different pane")
        return output
    }

    suspend fun send(pane: Pane, text: String) {
        if (pane.agent != null) {
            connection.request("agent.prompt", buildJsonObject {
                put("target", pane.id)
                put("text", text)
            }).expect("agent_prompted")
        } else {
            connection.request("pane.send_input", buildJsonObject {
                put("pane_id", pane.id)
                put("text", text)
                putJsonArray("keys") { add("Enter") }
            }).expect("ok")
        }
    }

    suspend fun key(pane: Pane, key: String) {
        val isAgent = pane.agent != null
        connection.request(if (isAgent) "agent.send_keys" else "pane.send_keys", buildJsonObject {
            put(if (isAgent) "target" else "pane_id", pane.id)
            putJsonArray("keys") { add(key) }
        }).expect("ok")
    }

    override fun close() = connection.close()
}

private fun JsonObject.expect(type: String) {
    if (get("type")?.jsonPrimitive?.content != type) throw IOException("Unexpected Herdr response: $type")
}
