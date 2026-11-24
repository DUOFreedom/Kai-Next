package com.kainext.duo.tools

import com.kainext.duo.data.AppSettings
import com.kainext.duo.data.ContextFoldStore
import com.kainext.duo.data.currentConversationIdOrNull
import com.kainext.duo.network.tools.ParameterSchema
import com.kainext.duo.network.tools.Tool
import com.kainext.duo.network.tools.ToolInfo
import com.kainext.duo.network.tools.ToolSchema
import kai.composeapp.generated.resources.Res
import kai.composeapp.generated.resources.tool_context_status_description
import kai.composeapp.generated.resources.tool_context_status_name
import kai.composeapp.generated.resources.tool_decompress_description
import kai.composeapp.generated.resources.tool_decompress_name
import kai.composeapp.generated.resources.tool_search_context_description
import kai.composeapp.generated.resources.tool_search_context_name
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * Reading back what compaction folded away: `search_context`, `decompress`, `context_status`.
 *
 * These three are the other half of folding. Compaction replaces older turns with a summary; these
 * tools are how the agent gets the detail back once it knows it needs it, instead of asking the user
 * to repeat themselves or guessing at what was said. The model of record is **billion-context**
 * (https://github.com/ranxianglei/billion-context, MIT), where `compress` / `decompress` /
 * `search_context` / `acp_status` are one family for exactly this reason.
 *
 * Design rules that are load-bearing:
 * - `search_context` returns snippets, not full blocks. Paying 20k chars for one lookup defeats the
 *   compression it is meant to complement; the model reads the snippet, then decides to decompress.
 * - `decompress` is a deliberate second step and reports its own cost, so an accidental call is
 *   visible in the token counter rather than silently doubling the prompt.
 * - `context_status` exists because a summary line is not proof that a block is stored. It reads the
 *   store, the same way billion-context insists on `acp_status` over the model's own `📦 [ACP]`
 *   claim (models do emit that marker unprompted).
 */
object ContextFoldTools {

    const val SEARCH_TOOL_ID = "search_context"
    const val DECOMPRESS_TOOL_ID = "decompress"
    const val STATUS_TOOL_ID = "context_status"

    val searchContextToolInfo = ToolInfo(
        id = SEARCH_TOOL_ID,
        name = "Search Folded Context",
        description = "Search earlier parts of this conversation that were condensed away",
        nameRes = Res.string.tool_search_context_name,
        descriptionRes = Res.string.tool_search_context_description,
    )

    val decompressToolInfo = ToolInfo(
        id = DECOMPRESS_TOOL_ID,
        name = "Read Folded Block",
        description = "Read the original text behind a folded conversation block",
        nameRes = Res.string.tool_decompress_name,
        descriptionRes = Res.string.tool_decompress_description,
    )

    val contextStatusToolInfo = ToolInfo(
        id = STATUS_TOOL_ID,
        name = "Context Status",
        description = "List the folded conversation blocks kept on this device",
        nameRes = Res.string.tool_context_status_name,
        descriptionRes = Res.string.tool_context_status_description,
    )

    fun getContextFoldTools(appSettings: AppSettings, store: ContextFoldStore): List<Tool> = buildList {
        if (appSettings.isToolEnabled(SEARCH_TOOL_ID)) add(searchContextTool(store))
        if (appSettings.isToolEnabled(DECOMPRESS_TOOL_ID)) add(decompressTool(store))
        if (appSettings.isToolEnabled(STATUS_TOOL_ID)) add(contextStatusTool(store))
    }

    fun searchContextTool(store: ContextFoldStore): Tool = object : Tool {
        override val schema = ToolSchema(
            name = SEARCH_TOOL_ID,
            description = "Search the earlier parts of this conversation that were summarised away. " +
                "Use it when you need a fact, file name, decision or number from before the " +
                "conversation was condensed and the summary does not carry it. Returns block ids; " +
                "call decompress on one of them to read its full text.",
            parameters = mapOf(
                "query" to ParameterSchema(
                    type = "string",
                    description = "Words to look for. All words must appear in the block; " +
                        "two or three distinctive terms work better than a sentence.",
                    required = true,
                ),
                "limit" to ParameterSchema(
                    type = "integer",
                    description = "Maximum hits (default ${ContextFoldStore.DEFAULT_SEARCH_LIMIT}, max 20).",
                    required = false,
                ),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val query = args["query"]?.toString()?.trim().orEmpty()
            if (query.isEmpty()) return mapOf("ok" to false, "error" to "query is required")
            val conversationId = currentConversationIdOrNull()
            val limit = ContextFoldStore.clampLimit((args["limit"] as? Number)?.toInt())
            val hits = store.search(conversationId, query, limit)
            if (hits.isEmpty()) {
                val total = store.forConversation(conversationId).size
                return mapOf(
                    "ok" to true,
                    "hits" to emptyList<Any>(),
                    "blocks_searched" to total,
                    "note" to if (total == 0) {
                        "Nothing has been folded yet in this conversation; the full history is still live."
                    } else {
                        "No block matched. Try fewer or different words."
                    },
                )
            }
            return mapOf(
                "ok" to true,
                "hits" to hits.map {
                    mapOf(
                        "block_id" to it.blockId,
                        "summary" to it.summary,
                        "snippet" to it.snippet,
                        "block_truncated" to it.truncated,
                    )
                },
                "blocks_searched" to store.forConversation(conversationId).size,
            )
        }
    }

    fun decompressTool(store: ContextFoldStore): Tool = object : Tool {
        override val schema = ToolSchema(
            name = DECOMPRESS_TOOL_ID,
            description = "Read the original text of a folded block, identified by the id that " +
                "search_context or context_status returned. The text is added to this turn only — " +
                "it does not permanently un-fold the conversation, so re-read it if you need it again.",
            parameters = mapOf(
                "block_id" to ParameterSchema(
                    type = "string",
                    description = "Block id such as b3.",
                    required = true,
                ),
                "max_chars" to ParameterSchema(
                    type = "integer",
                    description = "How much of the block to read (default 8000, max 20000). " +
                        "Ask for less when you only need one detail.",
                    required = false,
                ),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val blockId = args["block_id"]?.toString()?.trim().orEmpty()
            if (blockId.isEmpty()) return mapOf("ok" to false, "error" to "block_id is required")
            val conversationId = currentConversationIdOrNull()
            val block = store.get(conversationId, blockId)
                ?: return mapOf(
                    "ok" to false,
                    "error" to "No folded block '$blockId' in this conversation.",
                    "available" to store.forConversation(conversationId).map { it.id },
                )
            val maxChars = ((args["max_chars"] as? Number)?.toInt() ?: DEFAULT_DECOMPRESS_CHARS)
                .coerceIn(MIN_DECOMPRESS_CHARS, MAX_DECOMPRESS_CHARS)
            val content = block.transcript.take(maxChars)
            return mapOf(
                "ok" to true,
                "block_id" to block.id,
                "summary" to block.summary,
                "content" to content,
                "returned_chars" to content.length,
                "block_chars" to block.transcript.length,
                "truncated" to (content.length < block.transcript.length || block.truncated),
            )
        }
    }

    fun contextStatusTool(store: ContextFoldStore): Tool = object : Tool {
        override val schema = ToolSchema(
            name = STATUS_TOOL_ID,
            description = "List the folded conversation blocks stored for this conversation, with " +
                "their ids, sizes and creation times. Use it to check what is available to search " +
                "before deciding to decompress anything.",
            parameters = emptyMap(),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val conversationId = currentConversationIdOrNull()
            val blocks = store.forConversation(conversationId)
            return mapOf(
                "ok" to true,
                "count" to blocks.size,
                "blocks" to blocks.map { block ->
                    mapOf(
                        "block_id" to block.id,
                        "summary" to block.summary,
                        "messages_folded" to block.coveredMessages,
                        "chars" to block.transcript.length,
                        "truncated" to block.truncated,
                        "created" to formatTimestamp(block.createdAtMs),
                    )
                },
            )
        }
    }

    private fun formatTimestamp(epochMs: Long): String = try {
        Instant.fromEpochMilliseconds(epochMs)
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .toString()
            .take(16)
            .replace('T', ' ')
    } catch (_: Exception) {
        Clock.System.now().toString().take(16)
    }

    private const val DEFAULT_DECOMPRESS_CHARS = 8_000
    private const val MIN_DECOMPRESS_CHARS = 500
    private const val MAX_DECOMPRESS_CHARS = 20_000
}
