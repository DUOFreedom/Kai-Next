package com.kainext.duo.agents

import com.kainext.duo.network.tools.ParameterSchema
import com.kainext.duo.network.tools.Tool
import com.kainext.duo.network.tools.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Contract tests for the two worker kinds.
 *
 * The behaviour worth pinning is what happens when the model does not do the expected thing: it
 * declines to pick a tool, names a tool the worker does not have, or the provider throws. None of
 * those may fail a step that could still answer, and none may swallow a cancellation.
 */
class WorkerTest {

    /** A tool that records the arguments it was called with. */
    private class FakeTool(
        override val schema: ToolSchema,
        private val result: Any = mapOf("ok" to true),
        private val failWith: Throwable? = null,
    ) : Tool {
        var calls = 0
        var lastArgs: Map<String, Any>? = null

        override suspend fun execute(args: Map<String, Any>): Any {
            calls++
            lastArgs = args
            failWith?.let { throw it }
            return result
        }
    }

    private fun tool(name: String) = FakeTool(
        ToolSchema(
            name = name,
            description = "$name does things",
            parameters = mapOf(
                "command" to ParameterSchema(type = "string", description = "what to run", required = true),
            ),
        ),
    )

    private fun step(description: String = "list the files") = PlanStep(id = "s1", description = description, assignedWorker = WorkerKind.SYSTEM)

    @Test
    fun `the worker prompt lists its own tools only`() = runTest {
        var prompt = ""
        val worker = ToolBackedWorker(WorkerKind.SYSTEM, listOf(tool("android_shell"))) {
            prompt = it
            """{"tool":null}"""
        }

        worker.execute(step(), InMemoryBlackboard())

        assertTrue(prompt.contains("android_shell: android_shell does things"))
    }

    @Test
    fun `a chosen tool is executed with the model's arguments`() = runTest {
        val shell = tool("android_shell")
        val worker = ToolBackedWorker(WorkerKind.SYSTEM, listOf(shell)) {
            """{"tool":"android_shell","arguments":{"command":"ls -la"}}"""
        }

        val result = worker.execute(step(), InMemoryBlackboard())

        assertTrue(result.success)
        assertEquals(1, shell.calls)
        assertEquals("ls -la", shell.lastArgs?.get("command"))
        assertEquals(listOf("android_shell"), result.toolCalls)
    }

    @Test
    fun `declining a tool still succeeds with a placeholder answer`() = runTest {
        val worker = ToolBackedWorker(WorkerKind.GENERAL, listOf(tool("android_shell"))) {
            """{"tool":null}"""
        }

        val result = worker.execute(step("think about it"), InMemoryBlackboard())

        assertTrue(result.success, "a reasoning step must not fail for not calling a tool")
        assertEquals("(no tool needed)", result.output)
        assertTrue(result.toolCalls.isEmpty())
    }

    @Test
    fun `an unknown tool name fails with the available list`() = runTest {
        val worker = ToolBackedWorker(WorkerKind.SYSTEM, listOf(tool("android_shell"))) {
            """{"tool":"rm_rf","arguments":{}}"""
        }

        val result = worker.execute(step(), InMemoryBlackboard())

        assertFalse(result.success)
        val error = assertNotNull(result.error)
        assertTrue(error.contains("unknown tool 'rm_rf'"), error)
        assertTrue(error.contains("android_shell"), "the error must name what was available: $error")
    }

    @Test
    fun `a tool that throws fails the step with the tool name`() = runTest {
        val broken = FakeTool(
            schema = ToolSchema(name = "android_shell", description = "d", parameters = emptyMap()),
            failWith = IllegalStateException("permission denied"),
        )
        val worker = ToolBackedWorker(WorkerKind.SYSTEM, listOf(broken)) {
            """{"tool":"android_shell","arguments":{}}"""
        }

        val result = worker.execute(step(), InMemoryBlackboard())

        assertFalse(result.success)
        assertEquals("android_shell failed: permission denied", result.error)
    }

    @Test
    fun `a model error during tool selection fails the step`() = runTest {
        val worker = ToolBackedWorker(WorkerKind.SYSTEM, listOf(tool("android_shell"))) {
            error("HTTP 500")
        }

        val result = worker.execute(step(), InMemoryBlackboard())

        assertFalse(result.success)
        assertEquals("tool selection failed: HTTP 500", result.error)
    }

    @Test
    fun `cancellation during tool selection is rethrown`() = runTest {
        val worker = ToolBackedWorker(WorkerKind.SYSTEM, listOf(tool("android_shell"))) {
            throw CancellationException("stopped")
        }

        assertFailsWith<CancellationException> { worker.execute(step(), InMemoryBlackboard()) }
    }

    @Test
    fun `cancellation inside a tool is rethrown`() = runTest {
        val cancelling = FakeTool(
            schema = ToolSchema(name = "android_shell", description = "d", parameters = emptyMap()),
            failWith = CancellationException("stopped"),
        )
        val worker = ToolBackedWorker(WorkerKind.SYSTEM, listOf(cancelling)) {
            """{"tool":"android_shell","arguments":{}}"""
        }

        assertFailsWith<CancellationException> { worker.execute(step(), InMemoryBlackboard()) }
    }

    @Test
    fun `the worker prompt carries what earlier steps found`() = runTest {
        val board = InMemoryBlackboard()
        board.write(
            BlackboardEntry(
                stepId = "s0",
                workerKind = WorkerKind.RESEARCH,
                content = "the config lives in /root/projects/demo/.env",
            ),
        )
        var prompt = ""
        val worker = ToolBackedWorker(WorkerKind.SYSTEM, listOf(tool("android_shell"))) {
            prompt = it
            """{"tool":null}"""
        }

        worker.execute(step(), board)

        assertTrue(prompt.contains("the config lives in /root/projects/demo/.env"))
    }

    @Test
    fun `a direct answer worker returns the model text`() = runTest {
        val worker = DirectAnswerWorker(WorkerKind.GENERAL) { "the disk is 40 percent full" }

        val result = worker.execute(step("how full is the disk"), InMemoryBlackboard())

        assertTrue(result.success)
        assertEquals("the disk is 40 percent full", result.output)
    }

    @Test
    fun `a direct answer worker prefixes the blackboard as context`() = runTest {
        val board = InMemoryBlackboard()
        board.write(BlackboardEntry(stepId = "s0", workerKind = WorkerKind.SYSTEM, content = "df says 40%"))
        var prompt = ""
        val worker = DirectAnswerWorker(WorkerKind.GENERAL) {
            prompt = it
            "ok"
        }

        worker.execute(step("summarise"), board)

        assertTrue(prompt.contains("summarise"))
        assertTrue(prompt.contains("Context from earlier steps:"))
        assertTrue(prompt.contains("df says 40%"))
    }

    @Test
    fun `a direct answer worker omits the context header on an empty board`() = runTest {
        var prompt = ""
        val worker = DirectAnswerWorker(WorkerKind.GENERAL) {
            prompt = it
            "ok"
        }

        worker.execute(step("summarise"), InMemoryBlackboard())

        assertFalse(prompt.contains("Context from earlier steps:"))
    }

    @Test
    fun `a direct answer worker turns a provider error into a failed result`() = runTest {
        val worker = DirectAnswerWorker(WorkerKind.GENERAL) { error("connection reset") }

        val result = worker.execute(step(), InMemoryBlackboard())

        assertFalse(result.success)
        assertEquals("connection reset", result.error)
    }

    @Test
    fun `a direct answer worker rethrows cancellation`() = runTest {
        val worker = DirectAnswerWorker(WorkerKind.GENERAL) { throw CancellationException("stopped") }

        assertFailsWith<CancellationException> { worker.execute(step(), InMemoryBlackboard()) }
    }

    @Test
    fun `the worker kind is reported as declared`() = runTest {
        val worker = ToolBackedWorker(WorkerKind.RESEARCH, listOf(tool("web_search"))) {
            """{"tool":null}"""
        }
        assertEquals(WorkerKind.RESEARCH, worker.kind)
    }
}
