package ai.koog.agents.features.acp

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.testing.tools.DummyTool
import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.utils.time.KoogClock
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.transport.StdioTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals

class AcpAgentTest {
    private val model = LLModel(
        provider = LLMProvider.OpenAI,
        id = "test-model",
        capabilities = listOf(LLMCapability.Completion, LLMCapability.Tools),
        contextLength = 128_000,
    )

    @Test
    fun testToolCallIsAnnouncedOnceThenUpdated() = runTest {
        val tool = DummyTool()
        val executor = getMockExecutor {
            mockLLMToolCall(tool, DummyTool.Args("test"), toolCallId = "call-1") onRequestEquals "Use the tool"
            mockLLMAnswer("Done") onRequestContains tool.result
        }
        // The protocol is only handed to the feature; nothing is sent over this transport.
        val protocol = Protocol(backgroundScope, StdioTransport(backgroundScope, Dispatchers.IO, Buffer(), Buffer(), "test"))

        val events = channelFlow {
            val producer = this
            AIAgent(
                promptExecutor = executor,
                strategy = singleRunStrategy(),
                agentConfig = AIAgentConfig(prompt("test") { system("You are a test agent.") }, model, maxAgentIterations = 10),
                toolRegistry = ToolRegistry { tool(tool) },
            ) {
                install(AcpAgent) {
                    sessionId = "session"
                    this.protocol = protocol
                    eventsProducer = producer
                }
            }.run("Use the tool")
        }.toList()

        val toolUpdates = events.filterIsInstance<Event.SessionUpdateEvent>().map { it.update }.filter {
            (it as? SessionUpdate.ToolCall)?.toolCallId?.value == "call-1" ||
                (it as? SessionUpdate.ToolCallUpdate)?.toolCallId?.value == "call-1"
        }
        assertEquals(
            listOf(
                "tool_call" to ToolCallStatus.PENDING,
                "tool_call_update" to ToolCallStatus.IN_PROGRESS,
                "tool_call_update" to ToolCallStatus.COMPLETED,
            ),
            toolUpdates.map {
                when (it) {
                    is SessionUpdate.ToolCall -> "tool_call" to it.status
                    is SessionUpdate.ToolCallUpdate -> "tool_call_update" to it.status
                    else -> error("unexpected $it")
                }
            },
        )
    }

    @Test
    fun testToolCallWithoutAModelResponseIsAnnouncedWhenItStarts() = runTest {
        val tool = DummyTool()
        val executor = getMockExecutor {
            mockLLMAnswer("Done").asDefaultResponse
        }
        val protocol = Protocol(backgroundScope, StdioTransport(backgroundScope, Dispatchers.IO, Buffer(), Buffer(), "test"))
        val strategy = strategy<String, String>("direct") {
            val callTool by node<String, String> { input ->
                environment.executeTool(MessagePart.Tool.Call(id = "direct-1", tool = tool.name, args = """{"dummy":"$input"}""")).output
            }
            edge(nodeStart forwardTo callTool)
            edge(callTool forwardTo nodeFinish)
        }

        val events = channelFlow {
            val producer = this
            AIAgent(
                promptExecutor = executor,
                strategy = strategy,
                agentConfig = AIAgentConfig(prompt("test") { system("You are a test agent.") }, model, maxAgentIterations = 10),
                toolRegistry = ToolRegistry { tool(tool) },
            ) {
                install(AcpAgent) {
                    sessionId = "session"
                    this.protocol = protocol
                    eventsProducer = producer
                }
            }.run("test")
        }.toList()

        val first = events.filterIsInstance<Event.SessionUpdateEvent>().map { it.update }.first {
            it is SessionUpdate.ToolCall || it is SessionUpdate.ToolCallUpdate
        }
        assertEquals("direct-1", (first as SessionUpdate.ToolCall).toolCallId.value, "a call nobody announced is announced as it starts")
        assertEquals(ToolCallStatus.IN_PROGRESS, first.status)
    }

    @Test
    fun testBlankReasoningAndTextAreNotSent() {
        val response = Message.Assistant(
            parts = listOf(
                MessagePart.Reasoning(content = listOf("", "Weighing the options")),
                MessagePart.Text(""),
                MessagePart.Text("Hello"),
            ),
            metaInfo = ResponseMetaInfo.create(KoogClock.System),
        )

        val updates = response.toAcpEvents().map { it.update }

        assertEquals(
            listOf("thought: Weighing the options", "message: Hello"),
            updates.map {
                when (it) {
                    is SessionUpdate.AgentThoughtChunk -> "thought: ${(it.content as ContentBlock.Text).text}"
                    is SessionUpdate.AgentMessageChunk -> "message: ${(it.content as ContentBlock.Text).text}"
                    else -> error("unexpected $it")
                }
            },
        )
    }
}
