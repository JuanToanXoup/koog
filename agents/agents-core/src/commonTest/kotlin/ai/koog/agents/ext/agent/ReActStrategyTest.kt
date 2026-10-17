package ai.koog.agents.ext.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.CreateTool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.toStreamFrames
import ai.koog.utils.time.KoogClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class ReActStrategyTest {
    private val clock = KoogClock { Instant.parse("2026-01-01T00:00:00Z") }

    /** Records every request and answers from a script, in order. */
    private inner class RecordingExecutor(private vararg val replies: Message.Assistant) : PromptExecutor() {
        val requests = mutableListOf<Pair<Prompt, List<ToolDescriptor>>>()

        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
            requests += prompt to tools
            return replies[requests.size - 1]
        }

        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> = flow {
            execute(prompt, model, tools).toStreamFrames().forEach { emit(it) }
        }

        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
            throw UnsupportedOperationException("Not used")

        override fun close() = Unit
    }

    private fun text(text: String) = Message.Assistant(text, ResponseMetaInfo.create(clock))

    private fun call(tool: String, args: String) =
        Message.Assistant(MessagePart.Tool.Call(id = "call-1", tool = tool, args = args), ResponseMetaInfo.create(clock))

    private fun model(vararg capabilities: LLMCapability) =
        LLModel(provider = LLMProvider.OpenAI, id = "test-model", capabilities = listOf(LLMCapability.Completion, LLMCapability.Tools) + capabilities)

    private fun agent(executor: PromptExecutor, model: LLModel) = AIAgent(
        promptExecutor = executor,
        llmModel = model,
        strategy = reActStrategy(),
        toolRegistry = ToolRegistry { tool(CreateTool) },
    )

    @Test
    fun testActionRequestsNeverEndWithTheAssistantsReasoning() = runTest {
        val executor = RecordingExecutor(
            text("I should create the item first."),
            call(CreateTool.name, """{"name":"item"}"""),
            text("The item exists now; I can report it."),
            text("Created the item."),
        )

        val result = agent(executor, model(LLMCapability.ToolChoice)).run("Create an item")

        assertEquals("Created the item.", result)
        val actionRequests = executor.requests.filter { (prompt, _) -> prompt.params.toolChoice != LLMParams.ToolChoice.None }
        assertEquals(2, actionRequests.size)
        actionRequests.forEach { (prompt, _) ->
            val last = prompt.messages.last()
            assertFalse(last is Message.Assistant, "an action request ends with ${last.role}")
            assertEquals(REACT_ACTION_PROMPT, last.textContent())
        }
    }

    @Test
    fun testReasoningSeesToolsButCannotCallThemWhenTheModelSupportsToolChoice() = runTest {
        val executor = RecordingExecutor(text("Plan: answer directly."), text("Done."))

        agent(executor, model(LLMCapability.ToolChoice)).run("Say done")

        val (reasoning, reasoningTools) = executor.requests.first()
        assertEquals(LLMParams.ToolChoice.None, reasoning.params.toolChoice)
        assertEquals(listOf(CreateTool.name), reasoningTools.map { it.name })
        val (action, actionTools) = executor.requests.last()
        assertTrue(action.params.toolChoice != LLMParams.ToolChoice.None, "tool choice is restored for the action")
        assertEquals(listOf(CreateTool.name), actionTools.map { it.name })
    }

    @Test
    fun testReasoningWithoutToolChoiceSupportSendsNoTools() = runTest {
        val executor = RecordingExecutor(text("Plan: answer directly."), text("Done."))

        agent(executor, model()).run("Say done")

        val (reasoning, reasoningTools) = executor.requests.first()
        assertTrue(reasoningTools.isEmpty())
        assertEquals(null, reasoning.params.toolChoice)
    }

    @Test
    fun testMockExecutorAgentStillFinishes() = runTest {
        val executor = getMockExecutor {
            mockLLMAnswer("Done.").asDefaultResponse
        }

        assertEquals("Done.", agent(executor, model()).run("Say done"))
    }
}
