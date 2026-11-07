package dev.herdr.android

import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal val wireJson = Json { ignoreUnknownKeys = true }

class ApiException(val code: String, message: String) : IOException(message)

interface HerdrTransport : AutoCloseable {
    suspend fun request(method: String, params: JsonObject): JsonObject
}

internal object HerdrProtocol {
    const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
    const val MAX_REQUEST_BYTES = 1024 * 1024

    fun request(id: String, method: String, params: JsonObject): ByteArray {
        val bytes = buildJsonObject {
            put("id", id)
            put("method", method)
            put("params", params)
        }.toString().plus("\n").toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_REQUEST_BYTES) { "Request is too large" }
        return bytes
    }

    fun response(id: String, text: String): JsonObject {
        val envelope = wireJson.parseToJsonElement(text).jsonObject
        if (envelope["id"]?.jsonPrimitive?.content != id) {
            throw IOException("Server response belongs to a different request")
        }
        envelope["error"]?.jsonObject?.let { error ->
            throw ApiException(
                error.getValue("code").jsonPrimitive.content,
                error.getValue("message").jsonPrimitive.content,
            )
        }
        return envelope["result"]?.jsonObject ?: throw IOException("Herdr response contains no result")
    }
}
