package com.kainext.duo.agents

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract tests for the tolerant planner/worker parsers.
 *
 * The wording of the prompt and the strictness of the parser are one design: the prompt asks for
 * bare JSON, and the parser accepts the ways a real model deviates from it (fences, prose prefix,
 * a summary appended afterwards, braces inside strings). Each test below pins one of those
 * deviations, because the failure mode is silent — a plan we cannot read degrades to a single
 * GENERAL step and the user never learns their request was misunderstood.
 */
class SwarmPromptsTest {

    @Test
    fun `planner prompt carries the request, the step cap and the output shape`() {
        val prompt = SwarmPrompts.buildPlannerPrompt(
            SwarmRequest(instruction = "open settings and enable wifi", maxSteps = 4),
        )
        assertTrue(prompt.contains("open settings and enable wifi"))
        assertTrue(prompt.contains("At most 4 steps"), "cap must be stated: $prompt")
        assertTrue(prompt.contains("UI (taps/typing/navigation)"))
        assertTrue(prompt.contains("\"dependsOn\":[]"), "the example must show the field: $prompt")
    }

    @Test
    fun `planner prompt omits the context section when there is no context`() {
        val prompt = SwarmPrompts.buildPlannerPrompt(SwarmRequest(instruction = "x"))
        assertFalse(prompt.contains("## Context"))
    }

    @Test
    fun `planner prompt includes and bounds the context section`() {
        val prompt = SwarmPrompts.buildPlannerPrompt(
            SwarmRequest(instruction = "x", context = "y".repeat(5_000)),
        )
        assertTrue(prompt.contains("## Context"))
        assertFalse(prompt.contains("y".repeat(2_001)), "context must be clipped to 2000 chars")
    }

    @Test
    fun `plan parses a plain json reply`() {
        val raw = """
            {"steps":[
              {"id":"s1","description":"open settings","worker":"UI","dependsOn":[]},
              {"id":"s2","description":"enable wifi","worker":"UI","dependsOn":["s1"]}
            ],"reasoning":"two taps"}
        """.trimIndent()

        val plan = assertNotNull(SwarmPrompts.parsePlan(raw, maxSteps = 12))
        assertFalse(plan.degraded)
        assertEquals("two taps", plan.reasoning)
        assertEquals(listOf("s1", "s2"), plan.steps.map { it.id })
        assertEquals(listOf(WorkerKind.UI, WorkerKind.UI), plan.steps.map { it.assignedWorker })
        assertEquals(listOf("s1"), plan.steps[1].dependsOn)
    }

    @Test
    fun `plan parses through a markdown fence and a prose prefix`() {
        val raw = """
            Sure, here is the plan:
            ```json
            {"steps":[{"id":"s1","description":"check disk","worker":"SYSTEM"}]}
            ```
            Let me know if you want more.
        """.trimIndent()

        val plan = assertNotNull(SwarmPrompts.parsePlan(raw, maxSteps = 12))
        assertEquals(1, plan.steps.size)
        assertEquals(WorkerKind.SYSTEM, plan.steps[0].assignedWorker)
    }

    @Test
    fun `braces inside a string do not end the object early`() {
        val raw = """{"steps":[{"id":"s1","description":"run echo \"}\" in a shell"}]}"""
        val plan = assertNotNull(SwarmPrompts.parsePlan(raw, maxSteps = 12))
        assertEquals("""run echo "}" in a shell""", plan.steps[0].description)
    }

    @Test
    fun `escaped quotes inside a description survive`() {
        val raw = """{"steps":[{"id":"s1","description":"he said \"hi\""}]}"""
        val plan = assertNotNull(SwarmPrompts.parsePlan(raw, maxSteps = 12))
        assertEquals("""he said "hi"""", plan.steps[0].description)
    }

    @Test
    fun `unknown worker names fall back to general`() {
        val raw = """{"steps":[{"id":"s1","description":"think","worker":"PHILOSOPHER"}]}"""
        val plan = assertNotNull(SwarmPrompts.parsePlan(raw, maxSteps = 12))
        assertEquals(WorkerKind.GENERAL, plan.steps[0].assignedWorker)
    }

    @Test
    fun `worker names are matched case-insensitively and trimmed`() {
        val raw = """{"steps":[
            {"id":"s1","description":"a","worker":" research "},
            {"id":"s2","description":"b","worker":"ui"}
        ]}"""
        val plan = assertNotNull(SwarmPrompts.parsePlan(raw, maxSteps = 12))
        assertEquals(listOf(WorkerKind.RESEARCH, WorkerKind.UI), plan.steps.map { it.assignedWorker })
    }

    @Test
    fun `missing ids are numbered in order`() {
        val raw = """{"steps":[{"description":"a"},{"description":"b"}]}"""
        val plan = assertNotNull(SwarmPrompts.parsePlan(raw, maxSteps = 12))
        assertEquals(listOf("s1", "s2"), plan.steps.map { it.id })
    }

    @Test
    fun `steps without a description are dropped rather than planned empty`() {
        val raw = """{"steps":[
            {"id":"s1","description":"  "},
            {"id":"s2","description":"real work"}
        ]}"""
        val plan = assertNotNull(SwarmPrompts.parsePlan(raw, maxSteps = 12))
        assertEquals(listOf("s2"), plan.steps.map { it.id })
    }

    @Test
    fun `the step cap is enforced after parsing`() {
        val steps = (1..10).joinToString(",") { """{"id":"s$it","description":"step $it"}""" }
        val plan = assertNotNull(SwarmPrompts.parsePlan("""{"steps":[$steps]}""", maxSteps = 3))
        assertEquals(3, plan.steps.size)
    }

    @Test
    fun `dependencies on ids that are not in the plan are dropped`() {
        val raw = """{"steps":[
            {"id":"s1","description":"a","dependsOn":["ghost","s2"]},
            {"id":"s2","description":"b"}
        ]}"""
        val plan = assertNotNull(SwarmPrompts.parsePlan(raw, maxSteps = 12))
        assertEquals(listOf("s2"), plan.steps[0].dependsOn)
    }

    @Test
    fun `a step cannot depend on itself`() {
        val raw = """{"steps":[{"id":"s1","description":"a","dependsOn":["s1"]}]}"""
        val plan = assertNotNull(SwarmPrompts.parsePlan(raw, maxSteps = 12))
        assertTrue(plan.steps[0].dependsOn.isEmpty())
    }

    @Test
    fun `blank dependency entries are dropped`() {
        val raw = """{"steps":[{"id":"s1","description":"a","dependsOn":["","  "]}]}"""
        val plan = assertNotNull(SwarmPrompts.parsePlan(raw, maxSteps = 12))
        assertTrue(plan.steps[0].dependsOn.isEmpty())
    }

    @Test
    fun `prose with no json is not a plan`() {
        assertNull(SwarmPrompts.parsePlan("I would rather not.", maxSteps = 12))
    }

    @Test
    fun `an empty steps array is not a plan`() {
        assertNull(SwarmPrompts.parsePlan("""{"steps":[]}""", maxSteps = 12))
    }

    @Test
    fun `malformed json is not a plan`() {
        assertNull(SwarmPrompts.parsePlan("""{"steps":[{"description":}]}""", maxSteps = 12))
    }

    @Test
    fun `a non-object steps field is not a plan`() {
        assertNull(SwarmPrompts.parsePlan("""{"steps":"soon"}""", maxSteps = 12))
    }

    @Test
    fun `worker prompt lists the tools and asks for the call shape`() {
        val prompt = SwarmPrompts.buildWorkerPrompt(
            step = PlanStep(id = "s1", description = "list the files"),
            tools = listOf("android_files: browse the filesystem", "android_shell: run a command"),
            context = "the project is at /root/projects/demo",
        )
        assertTrue(prompt.contains("list the files"))
        assertTrue(prompt.contains("android_files: browse the filesystem"))
        assertTrue(prompt.contains("the project is at /root/projects/demo"))
        assertTrue(prompt.contains("""{"tool":"tool_name","arguments":{...}}"""))
        assertTrue(prompt.contains("""{"tool":null}"""))
    }

    @Test
    fun `worker choice parses a tool and flattens its arguments to strings`() {
        val choice = assertNotNull(
            SwarmPrompts.parseWorkerChoice(
                """{"tool":"android_shell","arguments":{"command":"ls -la","timeout":30}}""",
            ),
        )
        assertEquals("android_shell", choice.toolName)
        assertEquals("ls -la", choice.arguments["command"])
        assertEquals("30", choice.arguments["timeout"], "numbers must arrive as strings")
    }

    @Test
    fun `a null tool means no tool is needed`() {
        assertNull(SwarmPrompts.parseWorkerChoice("""{"tool":null}"""))
    }

    @Test
    fun `a literal null string also means no tool is needed`() {
        assertNull(SwarmPrompts.parseWorkerChoice("""{"tool":"null"}"""))
    }

    @Test
    fun `an absent tool field means no tool is needed`() {
        assertNull(SwarmPrompts.parseWorkerChoice("""{"arguments":{}}"""))
    }

    @Test
    fun `a worker choice with no arguments yields an empty map`() {
        val choice = assertNotNull(SwarmPrompts.parseWorkerChoice("""{"tool":"android_context"}"""))
        assertTrue(choice.arguments.isEmpty())
    }

    @Test
    fun `a worker choice is read through prose and fences too`() {
        val choice = assertNotNull(
            SwarmPrompts.parseWorkerChoice(
                """I'll run the command. ```json
                {"tool":"android_shell","arguments":{"command":"pwd"}}
                ```""",
            ),
        )
        assertEquals("android_shell", choice.toolName)
    }
}
