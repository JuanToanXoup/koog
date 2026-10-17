package ai.koog.prompt.executor.ollama.client

import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.ollama.client.dto.OllamaChatMessageDTO
import ai.koog.prompt.executor.ollama.client.dto.OllamaChatResponseDTO
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.toMessageResponse
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The last chunk of an Ollama stream (`done: true`) must close the stream: without an End frame the
 * buffered text never becomes a TextComplete frame, and the frames assemble into an assistant message
 * with no parts at all.
 */
class OllamaStreamingEndTest {

    private fun client(chunks: List<OllamaChatResponseDTO>): OllamaClient {
        var index = 0
        val server = MockStreamingOllamaChatServer { chunks[index++] }
        return OllamaClient(httpClientFactory = KtorKoogHttpClient.Factory(HttpClient(server.mockEngine)))
    }

    private fun chunk(content: String, done: Boolean = false) = OllamaChatResponseDTO(
        model = "test-model",
        message = OllamaChatMessageDTO(role = "assistant", content = content),
        done = done,
    )

    @Test
    fun testStreamEndsWithTextCompleteAndEnd() = runTest {
        val frames = client(
            listOf(
                chunk("Hel"),
                chunk("lo"),
                OllamaChatResponseDTO(
                    model = "test-model",
                    message = OllamaChatMessageDTO(role = "assistant", content = ""),
                    done = true,
                    doneReason = "stop",
                    promptEvalCount = 12,
                    evalCount = 3,
                ),
            )
        ).executeStreaming(prompt("test") { }, OllamaModels.Meta.LLAMA_3_2).toList()

        assertEquals(listOf("Hel", "lo"), frames.filterIsInstance<StreamFrame.TextDelta>().map { it.text })
        val complete = assertIs<StreamFrame.TextComplete>(frames[frames.lastIndex - 1])
        assertEquals("Hello", complete.text)
        val end = assertIs<StreamFrame.End>(frames.last())
        assertEquals("stop", end.finishReason)
        assertEquals(12, end.metaInfo.inputTokensCount)
        assertEquals(3, end.metaInfo.outputTokensCount)
        assertEquals(15, end.metaInfo.totalTokensCount)
    }

    @Test
    fun testStreamedFramesAssembleIntoTheTextMessage() = runTest {
        val frames = client(listOf(chunk("Hi"), chunk(" there"), chunk("", done = true)))
            .executeStreaming(prompt("test") { }, OllamaModels.Meta.LLAMA_3_2)
            .toList()

        val message = frames.toMessageResponse()
        val text = assertIs<MessagePart.Text>(message.parts.single())
        assertEquals("Hi there", text.text)
    }
}
