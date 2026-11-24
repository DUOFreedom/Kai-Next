package com.kainext.duo.agents

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contract tests for planner degradation.
 *
 * Every failure path here is a *degraded plan*, not an exception: a model that returns prose, a
 * provider that times out, or an empty reply all still produce one GENERAL step carrying the
 * original instruction. That is the difference between a bad turn and a lost request.
 */
class PlannerTest {

    private fun request(instruction: String = "check the disk") = SwarmRequest(instruction = instruction)

    @Test
    fun `a good reply becomes a multi-step plan`() = runTest {
        val planner = LlmPlanner {
            """{"steps":[{"id":"s1","description":"open settings","worker":"UI"},{"id":"s2","description":"enable wifi","worker":"UI","dependsOn":["s1"]}]}"""
        }
        val plan = planner.plan(request())
        assertFalse(plan.degraded)
        assertEquals(2, plan.steps.size)
    }

    @Test
    fun `an empty instruction degrades without calling the model`() = runTest {
        var called = false
        val planner = LlmPlanner {
            called = true
            """{"steps":[{"description":"x"}]}"""
        }

        val plan = planner.plan(request(instruction = "   "))

        assertFalse(called, "no model call should be spent on an empty instruction")
        assertTrue(plan.degraded)
        assertEquals("empty instruction", plan.reasoning)
        assertEquals(1, plan.steps.size)
        assertEquals(WorkerKind.GENERAL, plan.steps[0].assignedWorker)
    }

    @Test
    fun `a provider error degrades with the error text`() = runTest {
        val planner = LlmPlanner { error("HTTP 503") }

        val plan = planner.plan(request())

        assertTrue(plan.degraded)
        assertEquals("planner call failed: HTTP 503", plan.reasoning)
        assertEquals("check the disk", plan.steps[0].description, "the request must survive degradation")
    }

    @Test
    fun `unparseable prose degrades`() = runTest {
        val planner = LlmPlanner { "I'd rather describe the plan in words." }

        val plan = planner.plan(request())

        assertTrue(plan.degraded)
        assertEquals("planner reply was not a usable plan", plan.reasoning)
    }

    @Test
    fun `an empty steps array degrades`() = runTest {
        val planner = LlmPlanner { """{"steps":[]}""" }

        val plan = planner.plan(request())

        assertTrue(plan.degraded)
        assertEquals("planner reply was not a usable plan", plan.reasoning)
    }

    @Test
    fun `cancellation is never turned into a plan`() = runTest {
        val planner = LlmPlanner { throw CancellationException("user stopped") }

        assertFailsWith<CancellationException> { planner.plan(request()) }
    }

    @Test
    fun `the degraded plan keeps the original instruction verbatim`() = runTest {
        val instruction = "rename /root/projects/demo to demo2 and reopen it"
        val planner = LlmPlanner { "nope" }

        val plan = planner.plan(request(instruction))

        assertEquals(instruction, plan.steps.single().description)
        assertEquals("s1", plan.steps.single().id)
        assertTrue(plan.steps.single().dependsOn.isEmpty())
    }

    @Test
    fun `the request cap is passed through to the parser`() = runTest {
        val steps = (1..8).joinToString(",") { """{"id":"s$it","description":"step $it"}""" }
        val planner = LlmPlanner { """{"steps":[$steps]}""" }

        val plan = planner.plan(SwarmRequest(instruction = "x", maxSteps = 2))

        assertEquals(2, plan.steps.size)
    }

    @Test
    fun `a single step planner returns the requested worker kind`() = runTest {
        val plan = SingleStepPlanner(WorkerKind.SYSTEM).plan(request())

        assertFalse(plan.degraded)
        assertEquals("check the disk", plan.steps.single().description)
        assertEquals(WorkerKind.SYSTEM, plan.steps.single().assignedWorker)
    }
}
