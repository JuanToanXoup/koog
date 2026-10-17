package ai.koog.prompt.executor.clients.anthropic

import ai.koog.prompt.executor.clients.anthropic.models.AnthropicStreamDeltaContentType
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicStreamResponse
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalSerializationApi::class)
class AnthropicStreamSignatureDeltaTest {

    // Same configuration the client uses to decode stream events.
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        namingStrategy = JsonNamingStrategy.SnakeCase
    }

    @Test
    fun testSignatureDeltaIsDecoded() {
        val event = json.decodeFromString<AnthropicStreamResponse>(
            // language=json
            """
            {
              "type": "content_block_delta",
              "index": 0,
              "delta": { "type": "signature_delta", "signature": "EqQBCgIYAhIM1gbcDa9GJwZA2b3hGgxBdjrkzLoky3dl1pkiMOYds" }
            }
            """.trimIndent()
        )

        assertEquals("content_block_delta", event.type)
        assertEquals(0, event.index)
        assertEquals(AnthropicStreamDeltaContentType.SIGNATURE_DELTA.value, event.delta?.type)
        assertEquals("EqQBCgIYAhIM1gbcDa9GJwZA2b3hGgxBdjrkzLoky3dl1pkiMOYds", event.delta?.signature)
    }
}
