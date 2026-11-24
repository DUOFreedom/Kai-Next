package com.kainext.duo.data

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * One folded stretch of a conversation: a summary plus the text it replaced.
 *
 * The point of keeping [transcript] is that folding stops being a one-way door. Compaction used to
 * replace older turns with a single summary line and throw the originals away, so a fact the model
 * needed three turns later was simply gone — the only recovery was to ask the user again. A block
 * keeps the text it summarised, which is what makes `search_context` and `decompress` possible.
 *
 * [transcript] is capped rather than unbounded: a phone cannot hold every word of a month-long
 * session, and a block that lost its tail still answers most searches. When the cap bites, the
 * block records it in [truncated] so a later reader is not misled into thinking it is complete.
 */
@Immutable
@Serializable
data class ContextFoldBlock(
    val id: String,
    val summary: String,
    val transcript: String,
    /** How many history messages this block replaced — shown in status, not used for lookup. */
    val coveredMessages: Int = 0,
    /** Monotonic per conversation; lets a later block be recognised as newer, not as a correction. */
    val generation: Int = 1,
    val truncated: Boolean = false,
    val createdAtMs: Long = 0L,
)

/**
 * Folded blocks, one list per conversation.
 *
 * Design follows the compression model of **billion-context**
 * (https://github.com/ranxianglei/billion-context, MIT): compression is incremental — each fold
 * covers a stretch that no live message covers any more — and reversible, because the folded text
 * stays addressable by block id. The live history keeps only summaries, so the per-request payload
 * shrinks; nothing is destroyed, so the agent can go back.
 *
 * Persistence is one JSON map in [AppSettings], keyed by conversation id, so blocks survive the app
 * being killed. A conversation that is deleted simply stops being read.
 */
@OptIn(ExperimentalUuidApi::class)
class ContextFoldStore(private val appSettings: AppSettings) {

    private val json = Json { ignoreUnknownKeys = true }

    private val serializer = MapSerializer(
        String.serializer(),
        ListSerializer(ContextFoldBlock.serializer()),
    )

    private val _blocks = MutableStateFlow(load())

    /** Conversation id (empty string for "no conversation yet") to its folded blocks, oldest first. */
    val blocks: StateFlow<Map<String, List<ContextFoldBlock>>> = _blocks.asStateFlow()

    fun forConversation(conversationId: String?): List<ContextFoldBlock> = _blocks.value[conversationId.orEmpty()].orEmpty()

    /**
     * Records a fold. The returned block's id is what the summary line in the history refers to,
     * so the model can call `decompress` on it later.
     */
    fun append(
        conversationId: String?,
        summary: String,
        transcript: String,
        coveredMessages: Int,
    ): ContextFoldBlock {
        val key = conversationId.orEmpty()
        val existing = _blocks.value[key].orEmpty()
        val capped = capTranscript(transcript)
        val block = ContextFoldBlock(
            id = newBlockId(existing),
            summary = summary.take(MAX_SUMMARY_CHARS),
            transcript = capped.text,
            coveredMessages = coveredMessages,
            generation = (existing.maxOfOrNull { it.generation } ?: 0) + 1,
            truncated = capped.truncated,
            createdAtMs = kotlin.time.Clock.System.now().toEpochMilliseconds(),
        )
        // Oldest blocks fall off the end once a conversation has folded enough times; the live
        // history has already moved past them, so dropping the tail costs search depth, not the chat.
        val updated = (existing + block).takeLast(MAX_BLOCKS_PER_CONVERSATION)
        update(key, updated)
        return block
    }

    fun get(conversationId: String?, blockId: String): ContextFoldBlock? = forConversation(conversationId).firstOrNull { it.id == blockId }

    /**
     * Keyword search over summaries and folded transcripts. Case-insensitive, all terms must match
     * (AND) so a two-word query narrows instead of flooding the result with either word.
     */
    fun search(conversationId: String?, query: String, limit: Int = DEFAULT_SEARCH_LIMIT): List<FoldSearchHit> {
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return emptyList()
        val hits = mutableListOf<FoldSearchHit>()
        for (block in forConversation(conversationId)) {
            val summaryLower = block.summary.lowercase()
            val transcriptLower = block.transcript.lowercase()
            if (!terms.all { it in summaryLower || it in transcriptLower }) continue
            hits += FoldSearchHit(
                blockId = block.id,
                summary = block.summary,
                // Prefer a line that contains the first term: a snippet from the middle of a
                // 20k-char transcript with no visible match is useless for deciding relevance.
                snippet = snippetAround(block.transcript, terms.first()),
                truncated = block.truncated,
            )
            if (hits.size >= limit) break
        }
        return hits
    }

    fun clear(conversationId: String?) {
        val key = conversationId.orEmpty()
        if (key !in _blocks.value) return
        update(key, emptyList())
    }

    private fun update(conversationId: String, blocks: List<ContextFoldBlock>) {
        val next = _blocks.value.toMutableMap().apply {
            if (blocks.isEmpty()) remove(conversationId) else this[conversationId] = blocks
        }.trimToBudget()
        _blocks.value = next
        persist(next)
    }

    /** Keeps the newest conversations when the app has many, so the blob cannot grow without end. */
    private fun Map<String, List<ContextFoldBlock>>.trimToBudget(): Map<String, List<ContextFoldBlock>> {
        if (size <= MAX_CONVERSATIONS) return this
        return entries
            .sortedByDescending { entry -> entry.value.lastOrNull()?.createdAtMs ?: 0L }
            .take(MAX_CONVERSATIONS)
            .associate { it.key to it.value }
    }

    private fun load(): Map<String, List<ContextFoldBlock>> {
        val raw = appSettings.getContextFoldsJson() ?: return emptyMap()
        return try {
            json.decodeFromString(serializer, raw)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun persist(blocks: Map<String, List<ContextFoldBlock>>) {
        appSettings.setContextFoldsJson(json.encodeToString(serializer, blocks))
    }

    private data class Capped(val text: String, val truncated: Boolean)

    private fun capTranscript(transcript: String): Capped {
        if (transcript.length <= MAX_TRANSCRIPT_CHARS) return Capped(transcript, false)
        // Keep the head: the beginning of a stretch is where the task and the decisions were stated,
        // and a search hit near the end is still useful via its summary.
        return Capped(transcript.take(MAX_TRANSCRIPT_CHARS), true)
    }

    private fun snippetAround(text: String, term: String): String {
        val index = text.lowercase().indexOf(term)
        if (index < 0) return text.take(SNIPPET_CHARS)
        val start = (index - SNIPPET_LEAD).coerceAtLeast(0)
        val end = (index + SNIPPET_CHARS).coerceAtMost(text.length)
        return (if (start > 0) "…" else "") + text.substring(start, end).trim() +
            (if (end < text.length) "…" else "")
    }

    companion object {
        /** A conversation that has folded more than this has almost certainly been compacted too
         * aggressively; keeping the newest is more useful than keeping the oldest. */
        const val MAX_BLOCKS_PER_CONVERSATION = 40
        const val MAX_CONVERSATIONS = 20
        const val MAX_TRANSCRIPT_CHARS = 20_000
        const val MAX_SUMMARY_CHARS = 4_000
        const val DEFAULT_SEARCH_LIMIT = 5
        private const val MAX_SEARCH_LIMIT = 20
        private const val SNIPPET_CHARS = 320
        private const val SNIPPET_LEAD = 80

        fun clampLimit(raw: Int?): Int = (raw ?: DEFAULT_SEARCH_LIMIT).coerceIn(1, MAX_SEARCH_LIMIT)

        /** `b1`, `b2`, … — short enough to be quoted in a summary line and cheap in a prompt. */
        fun newBlockId(existing: List<ContextFoldBlock>): String {
            val highest = existing.mapNotNull { it.id.removePrefix("b").toIntOrNull() }.maxOrNull() ?: 0
            return "b${highest + 1}"
        }

        fun newEmptyId(): String = Uuid.random().toString()
    }
}

/** One search result: enough to decide whether to decompress, without paying for the whole block. */
@Immutable
data class FoldSearchHit(
    val blockId: String,
    val summary: String,
    val snippet: String,
    val truncated: Boolean,
)
