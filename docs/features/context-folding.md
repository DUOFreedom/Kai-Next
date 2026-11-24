# Context Folding

**Last verified:** 2026-11-17

A long conversation eventually exceeds the model's context window. Folding condenses the oldest stretch into a summary so the live history stays small — but unlike plain compaction it keeps the text the summary replaced, so nothing is lost: the agent can search the folded text and read a block back later.

## Concepts

### Folded Block

One condensed stretch of a conversation: a summary plus the transcript it replaced, an id, how many history messages it covered, a generation number, and whether the kept text was truncated.

Keeping the transcript is what makes folding reversible. Compaction used to replace older turns with a single summary line and throw the originals away, so a fact the model needed three turns later was simply gone — the only recovery was to ask the user again.

### Generation

A monotonic per-conversation counter. A later block is recognised as newer, not as a correction of the earlier one.

### Truncation

The stored transcript is capped rather than unbounded: a phone cannot hold every word of a month-long session, and a block that lost its tail still answers most searches. The head is kept because the beginning of a stretch is where the task and the decisions were stated. When the cap bites, the block records it so a later reader is not misled into thinking it is complete.

## When Folding Happens

1. Before a request, the runtime estimates the size of the history plus the active system prompt against the selected model's context window.
2. Nothing happens while the history is under the threshold — a conversation that fits is never condensed.
3. Past the threshold, the older messages are split off from the most recent few user exchanges, which stay verbatim.
4. The older stretch is sent to the model for a summary.
5. The summary is stored as a folded block, and the live history is replaced by a single summary entry that names the block id, followed by the recent messages.

If summarising fails (offline, rate limited), nothing is folded while the history still fits: a transient error must not erase the conversation. Only when the history no longer fits at all are the older turns dropped so the chat can continue.

## Reading Folded Text Back

### Search

A keyword search over the summaries and the folded transcripts. Matching is case-insensitive and every word must appear, so a two-word query narrows the result instead of flooding it with either word. Each hit carries the block id, the summary, a snippet centred on the matching word, and whether the block was truncated. A snippet is cut from the middle of the transcript, so it is marked as a fragment on both ends.

### Decompress

Reads the original text of one block by id. The text is added to the current turn only; it does not permanently un-fold the conversation, so a fact needed twice must be read twice.

### Status

Lists the blocks kept for this conversation with their ids, summaries, sizes, fold counts, and creation times, so the agent can check what is available before reading anything.

## Behavior

- Folding is incremental: each block covers a stretch no live message covers any more.
- The live history keeps only summaries, so the per-request payload shrinks; nothing is destroyed, so the agent can go back.
- A conversation keeps a bounded number of blocks; older ones fall off once it has folded enough times. The live history has already moved past them, so dropping the tail costs search depth, not the chat.
- The number of tracked conversations is bounded as well, so the stored blob cannot grow without end.
- Blocks survive the app being killed. A conversation that is deleted simply stops being read.
- A corrupt stored blob is treated as "nothing folded" rather than crashing the chat.
- A summary longer than the supported length is truncated; an empty search query returns nothing instead of everything.

## AI Tools

| Tool | Purpose |
|---|---|
| `search_context` | Search the folded parts of this conversation and return block ids |
| `decompress` | Read the original text of one folded block |
| `context_status` | List the folded blocks kept for this conversation |

## Key Files

| File | Purpose |
|---|---|
| `composeApp/src/commonMain/.../data/ContextFoldStore.kt` | Block model, append, search, snippet extraction, caps, persistence |
| `composeApp/src/commonMain/.../data/AppSettings.kt` | Stored block map |
| `composeApp/src/commonMain/.../data/RemoteDataRepository.kt` | Threshold check, summarise call, live-history replacement |
| `composeApp/src/commonMain/.../tools/ContextFoldTools.kt` | `search_context`, `decompress`, `context_status` |
| `composeApp/src/commonTest/.../data/ContextFoldStoreTest.kt` | Block ids, generations, both clipping caps, search semantics, persistence |
| `composeApp/src/commonTest/.../tools/ContextFoldToolsTest.kt` | Tool contract: snippet not block, empty-store note, miss listing, cost reporting |
