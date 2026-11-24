# Task List

**Last verified:** 2026-11-17

The task list is a visible checklist the agent maintains for a multi-step job. It exists because a long request ("book the train, then add it to the calendar, then message my wife") is only followable if the user can see what has been done and what is left — and because a model that writes its plan down is less likely to skip a step.

## Concepts

### To-Do List

An ordered list of short steps bound to one conversation. A conversation has at most one list; each list is capped so an over-eager plan cannot grow without end.

### Step

One row: a short sentence describing the step and its state. Rows have stable identity across rewrites, so a row that only changes state keeps its identity and the card can animate instead of redrawing.

### Step State

- **PENDING** — not started.
- **IN_PROGRESS** — being worked on now. The agent is told to keep exactly one row in this state.
- **COMPLETED** — done.

Unrecognised state words are read leniently: "done" and "complete" count as completed, "running" and "active" as in progress, anything else as pending. A model that writes a synonym should not silently lose the state.

## Behavior

- The agent calls `todo_write` with the **complete** list every time. A full replacement is idempotent and cannot drift out of sync the way incremental patches do; a row omitted from the call is deleted.
- Sending an empty list clears the card and stores nothing.
- Blank rows are dropped rather than stored.
- A row whose text is unchanged from the previous list keeps its previous identity; a row whose text changed gets a new one.
- The agent is told to re-send finished rows as completed instead of dropping them, so the card shows the whole job rather than only the remainder.
- The card is rendered inline in the chat, and a row can be ticked off by hand.
- Lists survive the app being killed; a conversation that never used the tool leaves nothing behind.
- A corrupt stored blob is treated as an empty list rather than crashing the chat screen.

## AI Tools

| Tool | Purpose |
|---|---|
| `todo_write` | Write or replace the visible task list for the current conversation |

## Key Files

| File | Purpose |
|---|---|
| `composeApp/src/commonMain/.../data/TodoStore.kt` | List model, states, per-conversation persistence, row cap, lenient state parsing |
| `composeApp/src/commonMain/.../data/AppSettings.kt` | Stored list map |
| `composeApp/src/commonMain/.../tools/TodoTools.kt` | `todo_write`, full-replacement semantics, row identity reuse |
| `composeApp/src/commonMain/.../ui/chat/composables/TodoCard.kt` | Inline task-list card |
