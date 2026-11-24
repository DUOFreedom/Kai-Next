package com.kainext.duo.tools

import com.kainext.duo.data.AppSettings
import com.kainext.duo.data.ContextFoldStore
import com.kainext.duo.data.ConversationIdElement
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Contract tests for the three tools that read folded context back.
 *
 * These tools are the return path of compaction, so the contract that matters is that they never
 * pretend to have found something they did not: an empty store must say "nothing has been folded",
 * a miss must say so and list what does exist, and a partial read must report itself as partial.
 * The conversation id comes from the coroutine context, exactly as it does in a real tool call.
 */
class ContextFoldToolsTest {

    private fun store(): ContextFoldStore = ContextFoldStore(AppSettings(MapSettings()))

    private fun fold(
        store: ContextFoldStore,
        conversationId: String,
        summary: String = "discussed the disk layout",
        transcript: String = "We agreed the projects live under /root/projects and the shell has no PTY.",
    ) = store.append(conversationId, summary, transcript, coveredMessages = 4)

    private suspend fun <T> inConversation(id: String, block: suspend () -> T): T = withContext(ConversationIdElement(id)) { block() }

    @Test
    fun `search returns a snippet and the block id, not the whole transcript`() = runTest {
        val store = store()
        // Long enough that a snippet has to be cut out of it: below SNIPPET_CHARS the snippet is
        // legitimately the whole block, so a short fixture would not prove anything.
        val long = ("filler sentence about layouts. ".repeat(60)) +
            "We agreed the shell has no PTY, which is why geometry is recorded but not applied."
        fold(store, "c1", transcript = long)

        val result = inConversation("c1") {
            ContextFoldTools.searchContextTool(store).execute(mapOf("query" to "PTY")) as Map<*, *>
        }

        assertEquals(true, result["ok"])
        val hits = result["hits"] as List<*>
        assertEquals(1, hits.size)
        val hit = hits.single() as Map<*, *>
        assertEquals("b1", hit["block_id"])
        val snippet = hit["snippet"] as String
        assertTrue(snippet.contains("PTY"))
        assertTrue(
            snippet.length < long.length,
            "a hit on a long block must be a snippet, not the block (${snippet.length} of ${long.length})",
        )
    }

    @Test
    fun `search on an empty store explains that nothing was folded`() = runTest {
        val result = inConversation("c1") {
            ContextFoldTools.searchContextTool(store()).execute(mapOf("query" to "anything")) as Map<*, *>
        }

        assertEquals(true, result["ok"])
        assertTrue((result["hits"] as List<*>).isEmpty())
        assertEquals(0, result["blocks_searched"])
        assertTrue((result["note"] as String).contains("Nothing has been folded"))
    }

    @Test
    fun `a miss on a populated store suggests different words`() = runTest {
        val store = store()
        fold(store, "c1")

        val result = inConversation("c1") {
            ContextFoldTools.searchContextTool(store).execute(mapOf("query" to "unrelated")) as Map<*, *>
        }

        assertTrue((result["hits"] as List<*>).isEmpty())
        assertEquals(1, result["blocks_searched"])
        assertTrue((result["note"] as String).contains("No block matched"))
    }

    @Test
    fun `search requires a query`() = runTest {
        val result = inConversation("c1") {
            ContextFoldTools.searchContextTool(store()).execute(mapOf("query" to "  ")) as Map<*, *>
        }

        assertEquals(false, result["ok"])
        assertEquals("query is required", result["error"])
    }

    @Test
    fun `search is scoped to the current conversation`() = runTest {
        val store = store()
        fold(store, "c1", transcript = "alpha secret")
        fold(store, "c2", transcript = "beta secret")

        val result = inConversation("c2") {
            ContextFoldTools.searchContextTool(store).execute(mapOf("query" to "secret")) as Map<*, *>
        }

        val hits = result["hits"] as List<*>
        assertEquals(1, hits.size)
        assertEquals("b1", (hits.single() as Map<*, *>)["block_id"])
        assertEquals(1, result["blocks_searched"], "only this conversation's blocks count")
    }

    @Test
    fun `search honours the limit argument`() = runTest {
        val store = store()
        repeat(4) { fold(store, "c1", transcript = "needle $it") }

        val result = inConversation("c1") {
            ContextFoldTools.searchContextTool(store)
                .execute(mapOf("query" to "needle", "limit" to 2)) as Map<*, *>
        }

        assertEquals(2, (result["hits"] as List<*>).size)
        assertEquals(4, result["blocks_searched"])
    }

    @Test
    fun `decompress returns the full transcript when it fits`() = runTest {
        val store = store()
        val block = fold(store, "c1")

        val result = inConversation("c1") {
            ContextFoldTools.decompressTool(store).execute(mapOf("block_id" to block.id)) as Map<*, *>
        }

        assertEquals(true, result["ok"])
        assertEquals(block.transcript, result["content"])
        assertEquals(block.transcript.length, result["returned_chars"])
        assertEquals(block.transcript.length, result["block_chars"])
        assertEquals(false, result["truncated"])
    }

    @Test
    fun `decompress reports its own cost when it has to cut the block`() = runTest {
        val store = store()
        val block = fold(store, "c1", transcript = "z".repeat(5_000))

        val result = inConversation("c1") {
            ContextFoldTools.decompressTool(store)
                .execute(mapOf("block_id" to block.id, "max_chars" to 1_000)) as Map<*, *>
        }

        assertEquals(1_000, result["returned_chars"])
        assertEquals(5_000, result["block_chars"])
        assertEquals(true, result["truncated"])
    }

    @Test
    fun `decompress clamps max_chars to its own bounds`() = runTest {
        val store = store()
        val block = fold(store, "c1", transcript = "z".repeat(5_000))

        val tooSmall = inConversation("c1") {
            ContextFoldTools.decompressTool(store)
                .execute(mapOf("block_id" to block.id, "max_chars" to 10)) as Map<*, *>
        }
        val tooBig = inConversation("c1") {
            ContextFoldTools.decompressTool(store)
                .execute(mapOf("block_id" to block.id, "max_chars" to 999_999)) as Map<*, *>
        }

        assertEquals(500, tooSmall["returned_chars"], "the floor keeps a read useful")
        assertEquals(5_000, tooBig["returned_chars"], "the ceiling keeps a read bounded")
    }

    @Test
    fun `decompress requires a block id`() = runTest {
        val result = inConversation("c1") {
            ContextFoldTools.decompressTool(store()).execute(emptyMap()) as Map<*, *>
        }

        assertEquals(false, result["ok"])
        assertEquals("block_id is required", result["error"])
    }

    @Test
    fun `a missing block names what is available`() = runTest {
        val store = store()
        fold(store, "c1")

        val result = inConversation("c1") {
            ContextFoldTools.decompressTool(store).execute(mapOf("block_id" to "b9")) as Map<*, *>
        }

        assertEquals(false, result["ok"])
        assertTrue((result["error"] as String).contains("b9"))
        assertEquals(listOf("b1"), result["available"])
    }

    @Test
    fun `context status lists every block with its size and folded message count`() = runTest {
        val store = store()
        fold(store, "c1", summary = "first", transcript = "aaa")
        fold(store, "c1", summary = "second", transcript = "bbbb")

        val result = inConversation("c1") {
            ContextFoldTools.contextStatusTool(store).execute(emptyMap()) as Map<*, *>
        }

        assertEquals(true, result["ok"])
        assertEquals(2, result["count"])
        val blocks = result["blocks"] as List<*>
        val first = blocks[0] as Map<*, *>
        assertEquals("b1", first["block_id"])
        assertEquals("first", first["summary"])
        assertEquals(3, first["chars"])
        assertEquals(4, first["messages_folded"])
        assertEquals(false, first["truncated"])
        assertNotNull(first["created"])
    }

    @Test
    fun `context status on an empty store reports zero`() = runTest {
        val result = inConversation("c1") {
            ContextFoldTools.contextStatusTool(store()).execute(emptyMap()) as Map<*, *>
        }

        assertEquals(true, result["ok"])
        assertEquals(0, result["count"])
        assertTrue((result["blocks"] as List<*>).isEmpty())
    }

    @Test
    fun `a truncated block is marked in both status and decompress`() = runTest {
        val store = store()
        val block = fold(store, "c1", transcript = "z".repeat(25_000))

        val status = inConversation("c1") {
            ContextFoldTools.contextStatusTool(store).execute(emptyMap()) as Map<*, *>
        }
        val read = inConversation("c1") {
            ContextFoldTools.decompressTool(store).execute(mapOf("block_id" to block.id)) as Map<*, *>
        }

        assertEquals(true, ((status["blocks"] as List<*>).single() as Map<*, *>)["truncated"])
        assertEquals(true, read["truncated"], "a clipped block must not read as complete")
    }

    @Test
    fun `the tools are registered with the expected ids`() {
        assertEquals("search_context", ContextFoldTools.SEARCH_TOOL_ID)
        assertEquals("decompress", ContextFoldTools.DECOMPRESS_TOOL_ID)
        assertEquals("context_status", ContextFoldTools.STATUS_TOOL_ID)
        assertEquals("Search Folded Context", ContextFoldTools.searchContextToolInfo.name)
        assertEquals("Read Folded Block", ContextFoldTools.decompressToolInfo.name)
        assertEquals("Context Status", ContextFoldTools.contextStatusToolInfo.name)
    }

    @Test
    fun `a disabled tool is not offered`() {
        val settings = AppSettings(MapSettings())
        val store = ContextFoldStore(settings)

        assertEquals(3, ContextFoldTools.getContextFoldTools(settings, store).size)

        settings.setToolEnabled(ContextFoldTools.DECOMPRESS_TOOL_ID, false)
        val remaining = ContextFoldTools.getContextFoldTools(settings, store).map { it.schema.name }

        assertFalse(ContextFoldTools.DECOMPRESS_TOOL_ID in remaining)
        assertTrue(ContextFoldTools.SEARCH_TOOL_ID in remaining)
        assertTrue(ContextFoldTools.STATUS_TOOL_ID in remaining)
    }
}
