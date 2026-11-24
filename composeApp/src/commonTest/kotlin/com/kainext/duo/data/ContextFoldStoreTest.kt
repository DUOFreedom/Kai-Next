package com.kainext.duo.data

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContextFoldStoreTest {

    private fun store(settings: MapSettings = MapSettings()) = ContextFoldStore(AppSettings(settings))

    @Test
    fun `append assigns increasing block ids`() {
        val store = store()

        val first = store.append("c1", "summary one", "transcript one", coveredMessages = 4)
        val second = store.append("c1", "summary two", "transcript two", coveredMessages = 3)

        assertEquals("b1", first.id)
        assertEquals("b2", second.id)
        assertEquals(1, first.generation)
        assertEquals(2, second.generation)
        assertEquals(4, first.coveredMessages)
        assertEquals(listOf(first, second), store.forConversation("c1"))
    }

    @Test
    fun `blocks are per conversation`() {
        val store = store()
        store.append("c1", "a", "ta", 1)
        store.append("c2", "b", "tb", 1)

        assertEquals(1, store.forConversation("c1").size)
        assertEquals(1, store.forConversation("c2").size)
        assertTrue(store.forConversation("c3").isEmpty())
    }

    @Test
    fun `a null conversation id is treated as the empty conversation`() {
        val store = store()
        store.append(null, "a", "ta", 1)

        assertEquals(1, store.forConversation(null).size)
        assertEquals(1, store.forConversation("").size)
    }

    @Test
    fun `get finds a block by id and returns null otherwise`() {
        val store = store()
        val block = store.append("c1", "summary", "transcript", 2)

        assertNotNull(store.get("c1", block.id))
        assertNull(store.get("c1", "b99"))
        assertNull(store.get("other", block.id))
    }

    @Test
    fun `the transcript is capped and the loss is recorded`() {
        val store = store()
        val long = "y".repeat(ContextFoldStore.MAX_TRANSCRIPT_CHARS + 5_000)

        val block = store.append("c1", "summary", long, 1)

        assertEquals(ContextFoldStore.MAX_TRANSCRIPT_CHARS, block.transcript.length)
        assertTrue(block.truncated)
    }

    @Test
    fun `a transcript under the cap is not marked truncated`() {
        val store = store()

        val block = store.append("c1", "summary", "short", 1)

        assertEquals("short", block.transcript)
        assertFalse(block.truncated)
    }

    @Test
    fun `the summary is capped independently`() {
        val store = store()

        val block = store.append("c1", "s".repeat(ContextFoldStore.MAX_SUMMARY_CHARS + 100), "t", 1)

        assertEquals(ContextFoldStore.MAX_SUMMARY_CHARS, block.summary.length)
    }

    @Test
    fun `only the newest blocks are kept per conversation`() {
        val store = store()
        repeat(ContextFoldStore.MAX_BLOCKS_PER_CONVERSATION + 3) { store.append("c1", "s$it", "t$it", 1) }

        val blocks = store.forConversation("c1")

        assertEquals(ContextFoldStore.MAX_BLOCKS_PER_CONVERSATION, blocks.size)
        assertEquals("b${ContextFoldStore.MAX_BLOCKS_PER_CONVERSATION + 3}", blocks.last().id)
    }

    @Test
    fun `search matches summaries and transcripts case insensitively`() {
        val store = store()
        store.append("c1", "Decided on Postgres", "we compared postgres and mysql", 3)

        assertEquals(1, store.search("c1", "postgres").size)
        assertEquals(1, store.search("c1", "POSTGRES").size)
        assertEquals(0, store.search("c1", "sqlite").size)
    }

    @Test
    fun `every term must match`() {
        val store = store()
        store.append("c1", "database choice", "we picked postgres", 3)

        assertEquals(1, store.search("c1", "database postgres").size)
        assertEquals(0, store.search("c1", "database sqlite").size)
    }

    @Test
    fun `a blank query returns nothing instead of everything`() {
        val store = store()
        store.append("c1", "anything", "text", 1)

        assertTrue(store.search("c1", "").isEmpty())
        assertTrue(store.search("c1", "   ").isEmpty())
    }

    @Test
    fun `search results are limited`() {
        val store = store()
        repeat(8) { store.append("c1", "needle $it", "needle body $it", 1) }

        assertEquals(3, store.search("c1", "needle", limit = 3).size)
        assertEquals(8, store.search("c1", "needle", limit = 100).size)
    }

    @Test
    fun `clampLimit keeps the requested limit inside the supported range`() {
        assertEquals(ContextFoldStore.DEFAULT_SEARCH_LIMIT, ContextFoldStore.clampLimit(null))
        assertEquals(1, ContextFoldStore.clampLimit(0))
        assertEquals(1, ContextFoldStore.clampLimit(-5))
        assertEquals(20, ContextFoldStore.clampLimit(1_000))
    }

    @Test
    fun `a hit snippet is centred on the matching term`() {
        val store = store()
        val transcript = "z".repeat(500) + " the api key rotated " + "z".repeat(500)
        store.append("c1", "no keyword here", transcript, 1)

        val hit = store.search("c1", "api").single()

        assertTrue(hit.snippet.contains("api key rotated"))
        // Cut from the middle of the transcript, so the snippet is marked as a fragment on both ends.
        assertTrue(hit.snippet.startsWith("…"))
        assertTrue(hit.snippet.endsWith("…"))
        assertTrue(hit.snippet.length < transcript.length)
    }

    @Test
    fun `a hit reports whether its transcript was truncated`() {
        val store = store()
        store.append("c1", "keyword", "k".repeat(ContextFoldStore.MAX_TRANSCRIPT_CHARS + 10), 1)

        assertTrue(store.search("c1", "keyword").single().truncated)
    }

    @Test
    fun `clear drops one conversation only`() {
        val store = store()
        store.append("c1", "a", "ta", 1)
        store.append("c2", "b", "tb", 1)

        store.clear("c1")

        assertTrue(store.forConversation("c1").isEmpty())
        assertEquals(1, store.forConversation("c2").size)
    }

    @Test
    fun `blocks survive a new store instance`() {
        val settings = MapSettings()
        val block = ContextFoldStore(AppSettings(settings)).append("c1", "summary", "transcript", 5)

        val reloaded = ContextFoldStore(AppSettings(settings)).forConversation("c1").single()

        assertEquals(block.id, reloaded.id)
        assertEquals("summary", reloaded.summary)
        assertEquals("transcript", reloaded.transcript)
        assertEquals(5, reloaded.coveredMessages)
    }

    @Test
    fun `a corrupt stored blob degrades to no blocks`() {
        val settings = MapSettings()
        AppSettings(settings).setContextFoldsJson("{not a map")

        assertTrue(ContextFoldStore(AppSettings(settings)).forConversation("c1").isEmpty())
    }

    @Test
    fun `newBlockId skips past the highest existing id`() {
        val existing = listOf(
            ContextFoldBlock(id = "b2", summary = "s", transcript = "t"),
            ContextFoldBlock(id = "b7", summary = "s", transcript = "t"),
        )

        assertEquals("b8", ContextFoldStore.newBlockId(existing))
        assertEquals("b1", ContextFoldStore.newBlockId(emptyList()))
    }

    @Test
    fun `the number of tracked conversations is bounded`() {
        val store = store()
        repeat(ContextFoldStore.MAX_CONVERSATIONS + 4) { store.append("c$it", "s", "t", 1) }

        assertEquals(ContextFoldStore.MAX_CONVERSATIONS, store.blocks.value.size)
    }
}
