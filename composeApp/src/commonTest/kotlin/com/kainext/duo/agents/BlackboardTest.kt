package com.kainext.duo.agents

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract tests for the shared blackboard.
 *
 * Two bounds matter and both are asserted here: per-entry clipping and total digest clipping.
 * Without them one verbose tool result would dominate every subsequent worker prompt, which is
 * exactly the failure the blackboard is meant to avoid.
 */
class BlackboardTest {

    private fun entry(
        stepId: String = "s1",
        content: String = "found the settings screen",
        success: Boolean = true,
        worker: WorkerKind = WorkerKind.UI,
    ) = BlackboardEntry(stepId = stepId, workerKind = worker, content = content, success = success)

    @Test
    fun `an empty board has an empty snapshot`() {
        assertEquals("", InMemoryBlackboard().snapshot())
    }

    @Test
    fun `entries are kept oldest first`() = runTest {
        val board = InMemoryBlackboard()
        board.write(entry(stepId = "s1", content = "first"))
        board.write(entry(stepId = "s2", content = "second"))

        assertEquals(listOf("s1", "s2"), board.entries.value.map { it.stepId })
    }

    @Test
    fun `latest returns the newest entry for that step only`() = runTest {
        val board = InMemoryBlackboard()
        board.write(entry(stepId = "s1", content = "old"))
        board.write(entry(stepId = "s2", content = "other"))
        board.write(entry(stepId = "s1", content = "new"))

        assertEquals("new", board.latest("s1")?.content)
        assertEquals("other", board.latest("s2")?.content)
        assertNull(board.latest("s9"))
    }

    @Test
    fun `the snapshot names the step, its worker and its content`() = runTest {
        val board = InMemoryBlackboard()
        board.write(entry(stepId = "s1", content = "opened settings", worker = WorkerKind.UI))

        assertEquals("- s1 (ui): opened settings", board.snapshot())
    }

    @Test
    fun `a failed entry is marked in the snapshot`() = runTest {
        val board = InMemoryBlackboard()
        board.write(entry(stepId = "s1", content = "permission denied", success = false))

        assertTrue(board.snapshot().contains("[FAILED]"), "a failure must be visible: ${board.snapshot()}")
    }

    @Test
    fun `a multi-line entry is flattened to one line`() = runTest {
        val board = InMemoryBlackboard()
        board.write(entry(content = "line one\nline two"))

        val snapshot = board.snapshot()
        assertEquals(1, snapshot.lines().size, "a newline in content would break the digest: $snapshot")
        assertTrue(snapshot.contains("line one line two"))
    }

    @Test
    fun `a long entry is clipped on write`() = runTest {
        val board = InMemoryBlackboard()
        board.write(entry(content = "x".repeat(5_000)))

        val stored = assertNotNull(board.entries.value.singleOrNull())
        assertEquals(InMemoryBlackboard.MAX_ENTRY_CHARS, stored.content.length)
    }

    @Test
    fun `the digest is clipped to its budget`() = runTest {
        val board = InMemoryBlackboard(maxEntries = 50)
        repeat(20) { index ->
            board.write(entry(stepId = "s$index", content = "y".repeat(InMemoryBlackboard.MAX_ENTRY_CHARS)))
        }

        val snapshot = board.snapshot()
        assertTrue(
            snapshot.length <= InMemoryBlackboard.MAX_SNAPSHOT_CHARS,
            "digest was ${snapshot.length} chars",
        )
    }

    @Test
    fun `the digest says when earlier entries were dropped`() = runTest {
        val board = InMemoryBlackboard(maxEntries = 50)
        repeat(20) { index ->
            board.write(entry(stepId = "s$index", content = "y".repeat(InMemoryBlackboard.MAX_ENTRY_CHARS)))
        }

        assertTrue(
            board.snapshot().contains("earlier entries omitted"),
            "a clipped digest must not look complete: ${board.snapshot().takeLast(120)}",
        )
    }

    @Test
    fun `only the newest entries survive the entry cap`() = runTest {
        val board = InMemoryBlackboard(maxEntries = 3)
        repeat(5) { index -> board.write(entry(stepId = "s$index", content = "entry $index")) }

        assertEquals(listOf("s2", "s3", "s4"), board.entries.value.map { it.stepId })
    }
}
