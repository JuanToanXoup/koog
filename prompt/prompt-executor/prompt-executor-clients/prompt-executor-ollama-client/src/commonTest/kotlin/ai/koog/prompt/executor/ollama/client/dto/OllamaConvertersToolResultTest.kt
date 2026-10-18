package ai.koog.prompt.executor.ollama.client.dto

import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.ollama.client.OllamaModels
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class OllamaConvertersToolResultTest {

    private val call = Message.Assistant(
        parts = listOf(MessagePart.Tool.Call(id = "c1", tool = "divide", args = """{"a":1250,"b":4}""")),
        metaInfo = ResponseMetaInfo.Empty,
    )

    private val result = MessagePart.Tool.Result(id = "c1", tool = "divide", parts = listOf(MessagePart.Text("""{"result":312.5}""")))

    @Test
    fun testToolResultsFollowTheCallWithoutAnEmptyUserTurn() {
        val prompt = Prompt(
            messages = listOf(
                Message.User(parts = listOf(MessagePart.Text("Split 1,250 four ways")), metaInfo = RequestMetaInfo.Empty),
                call,
                Message.User(parts = listOf(result), metaInfo = RequestMetaInfo.Empty),
            ),
            id = "test",
        )

        val messages = prompt.toOllamaChatMessages(OllamaModels.Meta.LLAMA_3_2)

        assertEquals(listOf("user", "assistant", "tool"), messages.map { it.role })
        assertEquals("""{"result":312.5}""", messages.last().content)
    }

    @Test
    fun testUserTextNextToAToolResultIsKept() {
        val prompt = Prompt(
            messages = listOf(
                call,
                Message.User(parts = listOf(MessagePart.Text("Here it is"), result), metaInfo = RequestMetaInfo.Empty),
            ),
            id = "test",
        )

        val messages = prompt.toOllamaChatMessages(OllamaModels.Meta.LLAMA_3_2)

        assertEquals(listOf("assistant", "user", "tool"), messages.map { it.role })
        assertEquals("Here it is", messages[1].content)
    }
}
