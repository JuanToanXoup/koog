package ai.koog.prompt.executor.clients.anthropic

import ai.koog.prompt.Prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.utils.time.KoogClock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Messages API rejects blank text blocks ("text content blocks must be non-empty", "must contain
 * non-whitespace text"), so the request carries none.
 */
class AnthropicBlankTextTest {
    private val client = AnthropicLLMClient(apiKey = "test-key")
    private val model = AnthropicModels.Sonnet_4
    private val requestMeta = RequestMetaInfo.create(KoogClock.System)
    private val responseMeta = ResponseMetaInfo.create(KoogClock.System)

    private fun user(text: String) = Message.User(text, requestMeta)

    private fun messagesOf(vararg messages: Message): List<JsonObject> {
        val request = client.createAnthropicRequest(Prompt(messages.toList(), "test"), emptyList(), model, false)
        return Json.parseToJsonElement(request).jsonObject.getValue("messages").jsonArray.map { it.jsonObject }
    }

    private fun JsonObject.role() = getValue("role").jsonPrimitive.content

    private fun JsonObject.blockTypes() = getValue("content").jsonArray.map { it.jsonObject.getValue("type").jsonPrimitive.content }

    @Test
    fun testThinkingOnlyReplyWithEmptyTextSendsOnlyTheThinkingBlock() {
        // A reasoning step Claude answered with thinking alone, padded with an empty text part.
        val reply = Message.Assistant(
            listOf(MessagePart.Reasoning("", encrypted = "signature"), MessagePart.Text("")),
            responseMeta,
        )

        val messages = messagesOf(user("Hello"), reply, user("Now take the next step."))

        assertEquals(listOf("user", "assistant", "user"), messages.map { it.role() })
        assertEquals(listOf("thinking"), messages[1].blockTypes())
    }

    @Test
    fun testWhitespaceTextIsDroppedNextToToolCalls() {
        val reply = Message.Assistant(
            listOf(MessagePart.Text(" \n"), MessagePart.Tool.Call(id = "call-1", tool = "search", args = "{}")),
            responseMeta,
        )

        val messages = messagesOf(user("Find it"), reply)

        assertEquals(listOf("tool_use"), messages[1].blockTypes())
    }

    @Test
    fun testMessageWithOnlyBlankTextIsDropped() {
        val messages = messagesOf(user("Hello"), Message.Assistant("", responseMeta), user("Hello again"))

        assertEquals(listOf("user", "user"), messages.map { it.role() })
    }

    @Test
    fun testBlankUserTextPartIsDropped() {
        val message = Message.User(listOf(MessagePart.Text(""), MessagePart.Text("Hello")), requestMeta)

        val messages = messagesOf(message)

        assertEquals(listOf("text"), messages.single().blockTypes())
        assertEquals("Hello", messages.single().getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)
    }

    @Test
    fun testNonBlankTextIsKept() {
        val messages = messagesOf(user("Hello"), Message.Assistant("Hi there", responseMeta))

        assertEquals(listOf("text"), messages[1].blockTypes())
    }
}
