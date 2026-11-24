package com.kainext.duo.agents

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Contract tests for the orchestrator's scheduling rules.
 *
 * The dependency graph is what makes concurrency safe here, so the cases that matter are the
 * unhappy ones: a failed dependency must skip its dependents without spending a model call, a
 * cycle must stop instead of spinning, and the whole run must respect its budget. The parallel
 * cap is asserted through observed concurrency rather than by inspecting a counter, because the
 * cap exists to protect a rate-limited API — the only thing that matters is how many calls are
 * actually in flight at once.
 */
class SwarmOrchestratorTest {

    /** A worker that records every step it is handed and answers with a fixed result. */
    private class RecordingWorker(
        override val kind: WorkerKind,
        private val succeed: Boolean = true,
        private val onExecute: (suspend (PlanStep) -> Unit)? = null,
    ) : Worker {
        val seen = mutableListOf<String>()

        override suspend fun execute(step: PlanStep, blackboard: Blackboard): WorkerResult {
            seen += step.id
            onExecute?.invoke(step)
            return if (succeed) {
                WorkerResult(success = true, output = "did ${step.id}")
            } else {
                WorkerResult(success = false, error = "cannot do ${step.id}")
            }
        }
    }

    private fun orchestrator(
        plan: PlanResult,
        workers: Map<WorkerKind, Worker>,
        maxParallel: Int = 3,
        maxSteps: Int = 12,
        timeoutMs: Long = 300_000,
    ) = SwarmOrchestrator(
        planner = object : Planner {
            override suspend fun plan(request: SwarmRequest) = plan
        },
        workers = workers,
        blackboard = InMemoryBlackboard(),
        maxParallel = maxParallel,
        maxSteps = maxSteps,
        totalTimeoutMs = timeoutMs,
    )

    private fun step(
        id: String,
        dependsOn: List<String> = emptyList(),
        worker: WorkerKind = WorkerKind.GENERAL,
    ) = PlanStep(id = id, description = "do $id", assignedWorker = worker, dependsOn = dependsOn)

    @Test
    fun `steps run in plan order and each result is reported`() = runTest {
        val worker = RecordingWorker(WorkerKind.GENERAL)
        val seenResults = mutableListOf<StepResult>()

        val result = orchestrator(
            plan = PlanResult(steps = listOf(step("s1"), step("s2"))),
            workers = mapOf(WorkerKind.GENERAL to worker),
        ).run(SwarmRequest(instruction = "x"), onStep = { seenResults += it })

        assertTrue(result.success)
        assertEquals(listOf("s1", "s2"), worker.seen)
        assertEquals(listOf("s1", "s2"), seenResults.map { it.stepId })
        assertEquals("Completed 2/2 steps.", result.summary())
    }

    @Test
    fun `a dependent step waits for its dependency`() = runTest {
        val order = mutableListOf<String>()
        val worker = RecordingWorker(WorkerKind.GENERAL, onExecute = { order += it.id })

        orchestrator(
            plan = PlanResult(steps = listOf(step("s2", dependsOn = listOf("s1")), step("s1"))),
            workers = mapOf(WorkerKind.GENERAL to worker),
        ).run(SwarmRequest(instruction = "x"))

        assertEquals(listOf("s1", "s2"), order, "s2 must not start before s1 finishes")
    }

    @Test
    fun `independent steps run concurrently up to the cap`() = runTest {
        var inFlight = 0
        var peak = 0
        val worker = RecordingWorker(
            WorkerKind.GENERAL,
            onExecute = {
                inFlight++
                peak = maxOf(peak, inFlight)
                delay(50)
                inFlight--
            },
        )

        orchestrator(
            plan = PlanResult(steps = (1..6).map { step("s$it") }),
            workers = mapOf(WorkerKind.GENERAL to worker),
            maxParallel = 2,
        ).run(SwarmRequest(instruction = "x"))

        assertEquals(2, peak, "the cap must bound concurrency; peak was $peak")
    }

    @Test
    fun `the cap never exceeds one when set to one`() = runTest {
        var inFlight = 0
        var peak = 0
        val worker = RecordingWorker(
            WorkerKind.GENERAL,
            onExecute = {
                inFlight++
                peak = maxOf(peak, inFlight)
                delay(20)
                inFlight--
            },
        )

        orchestrator(
            plan = PlanResult(steps = (1..4).map { step("s$it") }),
            workers = mapOf(WorkerKind.GENERAL to worker),
            maxParallel = 1,
        ).run(SwarmRequest(instruction = "x"))

        assertEquals(1, peak)
    }

    @Test
    fun `a zero or negative cap is treated as one`() = runTest {
        var inFlight = 0
        var peak = 0
        val worker = RecordingWorker(
            WorkerKind.GENERAL,
            onExecute = {
                inFlight++
                peak = maxOf(peak, inFlight)
                delay(10)
                inFlight--
            },
        )

        orchestrator(
            plan = PlanResult(steps = (1..3).map { step("s$it") }),
            workers = mapOf(WorkerKind.GENERAL to worker),
            maxParallel = 0,
        ).run(SwarmRequest(instruction = "x"))

        assertEquals(1, peak, "a semaphore with zero permits would deadlock")
    }

    @Test
    fun `a failed dependency skips its dependent without a model call`() = runTest {
        val failing = RecordingWorker(WorkerKind.SYSTEM, succeed = false)
        val dependent = RecordingWorker(WorkerKind.GENERAL)

        val result = orchestrator(
            plan = PlanResult(
                steps = listOf(
                    step("s1", worker = WorkerKind.SYSTEM),
                    step("s2", dependsOn = listOf("s1")),
                ),
            ),
            workers = mapOf(WorkerKind.SYSTEM to failing, WorkerKind.GENERAL to dependent),
        ).run(SwarmRequest(instruction = "x"))

        assertFalse(result.success)
        assertEquals(listOf("s1"), failing.seen)
        assertTrue(dependent.seen.isEmpty(), "the dependent step must not be attempted")
        val skipped = assertNotNull(result.stepResults.firstOrNull { it.stepId == "s2" })
        assertEquals(StepStatus.SKIPPED, skipped.status)
        assertEquals("dependency s1 failed", skipped.error)
    }

    @Test
    fun `a dependency cycle is skipped rather than spinning`() = runTest {
        val worker = RecordingWorker(WorkerKind.GENERAL)

        val result = orchestrator(
            plan = PlanResult(
                steps = listOf(
                    step("s1", dependsOn = listOf("s2")),
                    step("s2", dependsOn = listOf("s1")),
                ),
            ),
            workers = mapOf(WorkerKind.GENERAL to worker),
        ).run(SwarmRequest(instruction = "x"))

        assertFalse(result.success)
        assertTrue(worker.seen.isEmpty())
        assertEquals(2, result.stepResults.size)
        assertTrue(result.stepResults.all { it.status == StepStatus.SKIPPED })
        assertTrue(result.stepResults.all { it.error == "unresolved dependency" })
    }

    @Test
    fun `a plan with no steps fails with a stop reason`() = runTest {
        val result = orchestrator(
            plan = PlanResult(steps = emptyList()),
            workers = mapOf(WorkerKind.GENERAL to RecordingWorker(WorkerKind.GENERAL)),
        ).run(SwarmRequest(instruction = "x"))

        assertFalse(result.success)
        assertEquals("planner produced no steps", result.stopReason)
        assertTrue(result.stepResults.isEmpty())
    }

    @Test
    fun `the step cap is applied after planning`() = runTest {
        val worker = RecordingWorker(WorkerKind.GENERAL)

        val result = orchestrator(
            plan = PlanResult(steps = (1..6).map { step("s$it") }),
            workers = mapOf(WorkerKind.GENERAL to worker),
            maxSteps = 2,
        ).run(SwarmRequest(instruction = "x"))

        assertEquals(listOf("s1", "s2"), worker.seen)
        assertEquals(2, result.plan.steps.size)
    }

    @Test
    fun `a missing worker falls back to general`() = runTest {
        val general = RecordingWorker(WorkerKind.GENERAL)

        val result = orchestrator(
            plan = PlanResult(steps = listOf(step("s1", worker = WorkerKind.RESEARCH))),
            workers = mapOf(WorkerKind.GENERAL to general),
        ).run(SwarmRequest(instruction = "x"))

        assertTrue(result.success)
        assertEquals(listOf("s1"), general.seen)
        assertEquals(WorkerKind.GENERAL, result.stepResults.single().workerKind)
    }

    @Test
    fun `with no worker at all the step fails rather than throwing`() = runTest {
        val result = orchestrator(
            plan = PlanResult(steps = listOf(step("s1", worker = WorkerKind.UI))),
            workers = emptyMap(),
        ).run(SwarmRequest(instruction = "x"))

        assertFalse(result.success)
        val failed = assertNotNull(result.stepResults.singleOrNull())
        assertEquals(StepStatus.FAILED, failed.status)
        assertEquals("no worker registered for UI", failed.error)
    }

    @Test
    fun `a worker that throws becomes a failed step`() = runTest {
        val throwing = object : Worker {
            override val kind = WorkerKind.GENERAL
            override suspend fun execute(step: PlanStep, blackboard: Blackboard): WorkerResult = error("boom")
        }

        val result = orchestrator(
            plan = PlanResult(steps = listOf(step("s1"))),
            workers = mapOf(WorkerKind.GENERAL to throwing),
        ).run(SwarmRequest(instruction = "x"))

        assertFalse(result.success)
        assertEquals("boom", assertNotNull(result.stepResults.singleOrNull()).error)
    }

    @Test
    fun `the run times out with a reason instead of hanging`() = runTest {
        val slow = RecordingWorker(WorkerKind.GENERAL, onExecute = { delay(10_000) })

        val result = orchestrator(
            plan = PlanResult(steps = listOf(step("s1"))),
            workers = mapOf(WorkerKind.GENERAL to slow),
            timeoutMs = 50,
        ).run(SwarmRequest(instruction = "x"))

        assertTrue(result.timedOut)
        assertFalse(result.success)
        assertEquals("timed out after 50ms", result.stopReason)
        assertTrue(result.summary().startsWith("Stopped after timing out"))
    }

    @Test
    fun `the blackboard collects one entry per executed step`() = runTest {
        val board = InMemoryBlackboard()
        val orchestrator = SwarmOrchestrator(
            planner = object : Planner {
                override suspend fun plan(request: SwarmRequest) = PlanResult(steps = listOf(step("s1"), step("s2")))
            },
            workers = mapOf(WorkerKind.GENERAL to RecordingWorker(WorkerKind.GENERAL)),
            blackboard = board,
        )

        val result = orchestrator.run(SwarmRequest(instruction = "x"))

        assertEquals(listOf("s1", "s2"), board.entries.value.map { it.stepId })
        assertTrue(result.blackboardSnapshot.contains("s1"))
        assertTrue(result.blackboardSnapshot.contains("did s1"))
    }

    @Test
    fun `onPlan fires once with the plan the run used`() = runTest {
        val plans = mutableListOf<PlanResult>()

        orchestrator(
            plan = PlanResult(steps = listOf(step("s1"))),
            workers = mapOf(WorkerKind.GENERAL to RecordingWorker(WorkerKind.GENERAL)),
        ).run(SwarmRequest(instruction = "x"), onPlan = { plans += it })

        assertEquals(1, plans.size)
        assertEquals(listOf("s1"), plans.single().steps.map { it.id })
    }

    @Test
    fun `summary counts done and failed steps`() = runTest {
        val mixed = mapOf<WorkerKind, Worker>(
            WorkerKind.SYSTEM to RecordingWorker(WorkerKind.SYSTEM, succeed = false),
            WorkerKind.GENERAL to RecordingWorker(WorkerKind.GENERAL),
        )

        val result = orchestrator(
            plan = PlanResult(
                steps = listOf(step("s1"), step("s2", worker = WorkerKind.SYSTEM)),
            ),
            workers = mixed,
        ).run(SwarmRequest(instruction = "x"))

        assertFalse(result.success)
        assertEquals("1/2 steps completed, 1 failed.", result.summary())
    }

    @Test
    fun `a nested plan with a diamond dependency runs each step once`() = runTest {
        val worker = RecordingWorker(WorkerKind.GENERAL)

        val result = orchestrator(
            plan = PlanResult(
                steps = listOf(
                    step("s1"),
                    step("s2", dependsOn = listOf("s1")),
                    step("s3", dependsOn = listOf("s1")),
                    step("s4", dependsOn = listOf("s2", "s3")),
                ),
            ),
            workers = mapOf(WorkerKind.GENERAL to worker),
            maxParallel = 4,
        ).run(SwarmRequest(instruction = "x"))

        assertTrue(result.success)
        assertEquals(4, worker.seen.size)
        assertEquals(4, worker.seen.toSet().size, "no step may run twice: ${worker.seen}")
        assertEquals("s4", worker.seen.last(), "s4 must come after both branches")
    }
}
