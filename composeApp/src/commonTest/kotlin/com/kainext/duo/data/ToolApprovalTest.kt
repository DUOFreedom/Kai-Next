package com.kainext.duo.data

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolRiskRegistryTest {

    @Test
    fun `read-only tools are low risk`() {
        assertEquals(ToolRisk.LOW, ToolRiskRegistry.riskOf("web_search"))
        assertEquals(ToolRisk.LOW, ToolRiskRegistry.riskOf("read_file"))
        assertEquals(ToolRisk.LOW, ToolRiskRegistry.riskOf("get_clipboard"))
    }

    @Test
    fun `reversible local writes are medium risk`() {
        assertEquals(ToolRisk.MEDIUM, ToolRiskRegistry.riskOf("todo_write"))
        assertEquals(ToolRisk.MEDIUM, ToolRiskRegistry.riskOf("memory_store"))
        assertEquals(ToolRisk.MEDIUM, ToolRiskRegistry.riskOf("write_file"))
    }

    @Test
    fun `irreversible or externally visible tools are high risk`() {
        assertEquals(ToolRisk.HIGH, ToolRiskRegistry.riskOf("execute_shell_command"))
        assertEquals(ToolRisk.HIGH, ToolRiskRegistry.riskOf("send_sms"))
        assertEquals(ToolRisk.HIGH, ToolRiskRegistry.riskOf("device_control"))
        assertEquals(ToolRisk.HIGH, ToolRiskRegistry.riskOf("run_subagent"))
    }

    @Test
    fun `an unknown tool is gated as high risk`() {
        // A capability the table has not seen yet must never silently bypass the gate.
        assertEquals(ToolRisk.HIGH, ToolRiskRegistry.riskOf("some_mcp_tool_added_later"))
    }

    @Test
    fun `HIGH_RISK policy asks only for high risk tools`() {
        assertTrue(ToolRiskRegistry.requiresApproval("send_sms", ToolApprovalPolicy.HIGH_RISK))
        assertFalse(ToolRiskRegistry.requiresApproval("web_search", ToolApprovalPolicy.HIGH_RISK))
        assertFalse(ToolRiskRegistry.requiresApproval("todo_write", ToolApprovalPolicy.HIGH_RISK))
    }

    @Test
    fun `ALWAYS policy asks for everything and NEVER asks for nothing`() {
        assertTrue(ToolRiskRegistry.requiresApproval("web_search", ToolApprovalPolicy.ALWAYS))
        assertTrue(ToolRiskRegistry.requiresApproval("send_sms", ToolApprovalPolicy.ALWAYS))
        assertFalse(ToolRiskRegistry.requiresApproval("send_sms", ToolApprovalPolicy.NEVER))
        assertFalse(ToolRiskRegistry.requiresApproval("web_search", ToolApprovalPolicy.NEVER))
    }
}

class ToolApprovalRequestTest {

    @Test
    fun `short arguments are shown verbatim`() {
        val request = request(arguments = """{"command":"ls"}""")
        assertEquals("""{"command":"ls"}""", request.argumentsPreview)
    }

    @Test
    fun `long arguments are cut to the preview budget with an ellipsis`() {
        val request = request(arguments = "x".repeat(ToolApprovalRequest.MAX_PREVIEW_CHARS + 400))

        assertEquals(ToolApprovalRequest.MAX_PREVIEW_CHARS + 1, request.argumentsPreview.length)
        assertTrue(request.argumentsPreview.endsWith("…"))
    }

    private fun request(arguments: String) = ToolApprovalRequest(
        id = "r1",
        toolId = "execute_shell_command",
        displayName = "Shell",
        arguments = arguments,
        risk = ToolRisk.HIGH,
    )
}

class ToolApprovalControllerTest {

    @AfterTest
    fun tearDown() {
        // A denied answer is delivered to whoever is waiting, and the generation bump makes sure
        // the next test starts from a clean queue.
        ToolApprovalController.cancelPending()
    }

    @Test
    fun `requestApproval publishes the request until the user answers`() = runTest {
        val request = request("execute_shell_command")

        val answer = async { ToolApprovalController.requestApproval(request) }
        // Let the caller reach the point where it publishes and suspends.
        testScheduler.advanceUntilIdle()

        assertEquals(request, ToolApprovalController.pending.value)

        ToolApprovalController.respond(approved = true)

        assertTrue(answer.await().allowed)
        assertNull(ToolApprovalController.pending.value)
    }

    @Test
    fun `denying the request resolves as not allowed`() = runTest {
        val answer = async { ToolApprovalController.requestApproval(request("send_sms")) }
        testScheduler.advanceUntilIdle()

        ToolApprovalController.respond(approved = false)

        val outcome = answer.await()
        assertFalse(outcome.allowed)
        assertFalse(outcome.alwaysAllow)
    }

    @Test
    fun `always allow is a yes with a durable grant flag`() = runTest {
        val answer = async { ToolApprovalController.requestApproval(request("set_alarm")) }
        testScheduler.advanceUntilIdle()

        ToolApprovalController.respondAlwaysAllow()

        val outcome = answer.await()
        assertTrue(outcome.allowed)
        assertTrue(outcome.alwaysAllow)
    }

    @Test
    fun `a cancel denies the pending request and drains the queue`() = runTest {
        val first = async { ToolApprovalController.requestApproval(request("execute_shell_command")) }
        testScheduler.advanceUntilIdle()
        val second = async { ToolApprovalController.requestApproval(request("send_sms")) }
        testScheduler.advanceUntilIdle()

        ToolApprovalController.cancelPending()

        assertFalse(first.await().allowed)
        // The second call never reached the dialog, so it must not hang waiting for an answer.
        assertFalse(second.await().allowed)
        assertNull(ToolApprovalController.pending.value)
    }

    @Test
    fun `concurrent requests are serialised into one dialog at a time`() = runTest {
        val first = async { ToolApprovalController.requestApproval(request("execute_shell_command")) }
        testScheduler.advanceUntilIdle()
        val second = async { ToolApprovalController.requestApproval(request("send_sms")) }
        testScheduler.advanceUntilIdle()

        // Only the first request is on screen; the second is queued behind the mutex.
        assertEquals("execute_shell_command", ToolApprovalController.pending.value?.toolId)

        ToolApprovalController.respond(approved = true)
        assertTrue(first.await().allowed)
        testScheduler.advanceUntilIdle()

        // Once the first is answered the second takes the dialog.
        assertEquals("send_sms", ToolApprovalController.pending.value?.toolId)
        ToolApprovalController.respond(approved = false)
        assertFalse(second.await().allowed)
    }

    private fun request(toolId: String) = ToolApprovalRequest(
        id = "r-$toolId",
        toolId = toolId,
        displayName = toolId,
        arguments = "{}",
        risk = ToolRiskRegistry.riskOf(toolId),
    )
}

class ToolApprovalPolicySettingsTest {

    @Test
    fun `policy defaults to HIGH_RISK`() {
        assertEquals(
            ToolApprovalPolicy.HIGH_RISK,
            AppSettings(MapSettings()).getToolApprovalPolicy(),
        )
    }

    @Test
    fun `policy round-trips through settings`() {
        val appSettings = AppSettings(MapSettings())

        appSettings.setToolApprovalPolicy(ToolApprovalPolicy.NEVER)
        assertEquals(ToolApprovalPolicy.NEVER, appSettings.getToolApprovalPolicy())

        appSettings.setToolApprovalPolicy(ToolApprovalPolicy.ALWAYS)
        assertEquals(ToolApprovalPolicy.ALWAYS, appSettings.getToolApprovalPolicy())
    }

    @Test
    fun `an unrecognised stored policy falls back to the default`() {
        val settings = MapSettings()
        settings.putString("tool_approval_policy", "SOMETHING_ELSE")

        assertEquals(
            ToolApprovalPolicy.HIGH_RISK,
            AppSettings(settings).getToolApprovalPolicy(),
        )
    }

    @Test
    fun `always allowed tools accumulate and can be revoked`() {
        val appSettings = AppSettings(MapSettings())
        assertTrue(appSettings.getAlwaysAllowedTools().isEmpty())

        appSettings.setToolAlwaysAllowed("execute_shell_command", true)
        appSettings.setToolAlwaysAllowed("send_sms", true)
        assertEquals(
            setOf("execute_shell_command", "send_sms"),
            appSettings.getAlwaysAllowedTools(),
        )

        appSettings.setToolAlwaysAllowed("send_sms", false)
        assertEquals(setOf("execute_shell_command"), appSettings.getAlwaysAllowedTools())

        appSettings.clearAlwaysAllowedTools()
        assertTrue(appSettings.getAlwaysAllowedTools().isEmpty())
    }

    @Test
    fun `always allowed tools survive a new AppSettings instance`() {
        val settings = MapSettings()
        AppSettings(settings).setToolAlwaysAllowed("set_alarm", true)

        assertNotNull(AppSettings(settings).getAlwaysAllowedTools().firstOrNull { it == "set_alarm" })
    }
}
