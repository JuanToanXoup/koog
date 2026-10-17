package ai.koog.prompt.executor.ollama.client

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.ollama.client.dto.OllamaChatMessageDTO
import ai.koog.prompt.executor.ollama.client.dto.OllamaChatResponseDTO
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * A streamed chat request must carry the tools like a non-streamed one does. Without them the model
 * never sees its tools and writes tool-call syntax into its answer instead of calling anything.
 */
class OllamaStreamingToolsTest {

    private val weather = ToolDescriptor(
        name = "weather_forecast",
        description = "Daily forecast for a location",
        requiredParameters = listOf(ToolParameterDescriptor("location", "City name", ToolParameterType.String)),
    )

    private fun server() = MockOllamaChatServer { request ->
        OllamaChatResponseDTO(
            model = request.model,
            message = OllamaChatMessageDTO(role = "assistant", content = "Sunny"),
            done = true,
            doneReason = "stop",
        )
    }

    @Test
    fun testStreamingRequestCarriesTheTools() = runTest {
        val server = server()
        val client = OllamaClient(httpClientFactory = KtorKoogHttpClient.Factory(HttpClient(server.mockEngine)))

        client.executeStreaming(prompt("test") { user("Weather in Paris?") }, OllamaModels.Meta.LLAMA_3_2, listOf(weather)).toList()

        val tools = assertNotNull(server.requestHistory.single().tools, "the streamed request has no tools")
        assertEquals(listOf("weather_forecast"), tools.map { it.function.name })
        assertEquals("Daily forecast for a location", tools.single().function.description)
    }

    @Test
    fun testStreamingRequestWithoutToolsSendsNone() = runTest {
        val server = server()
        val client = OllamaClient(httpClientFactory = KtorKoogHttpClient.Factory(HttpClient(server.mockEngine)))

        client.executeStreaming(prompt("test") { user("Hi") }, OllamaModels.Meta.LLAMA_3_2).toList()

        assertEquals(null, server.requestHistory.single().tools)
    }
}
