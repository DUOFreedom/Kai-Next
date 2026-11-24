# Goals

**Last verified:** 2026-11-17

A goal is a long-running objective the agent carries across turns on its own. Instead of stopping after one reply and waiting to be asked again, an active goal makes the assistant continue working until it reports the objective done or gets stuck — with a visible round budget so the work cannot run away.

## Concepts

### Goal

One objective bound to one conversation: the objective sentence, a lifecycle status, how many autonomous rounds have already run, and the round budget. A conversation has at most one goal; starting a new one replaces the previous objective rather than queuing beside it, because the newest request is the one the user is actually waiting for.

### Status

- **ACTIVE** — the agent keeps working on its own. This is the only status that triggers a continuation round.
- **PAUSED** — stopped by the user. Resumable without losing the round count.
- **COMPLETED** — the agent reported the objective as achieved.
- **BLOCKED** — the agent reported it cannot continue, or the round budget ran out. A blocked goal carries a reason so the stop is explained instead of the assistant simply going quiet.

### Round

One autonomous continuation turn. Each round spends one unit of the budget and is persisted, so restarting the app resumes the same count instead of resetting it.

### Round Budget

The maximum number of autonomous rounds a goal may spend before it is marked blocked. Configured once in Settings → Tools and used as the default for new goals; the agent may request a smaller or larger cap per goal, clamped to the supported range.

## Lifecycle

1. The agent calls `create_goal` with a one-sentence objective once a request clearly needs many steps.
2. The goal is created ACTIVE with a fresh round count and persisted.
3. A goal card appears in the chat showing the objective, status, and `round N of M`.
4. When a reply ends with the goal still ACTIVE, the runtime sends a continuation instruction: carry on from where you stopped, do not repeat finished work, report completion when done.
5. Each continuation spends one round and updates the card.
6. The agent ends the goal by calling `update_goal` with `completed`, `blocked`, or `paused`.
7. If the budget runs out first, the goal is marked BLOCKED with a round-limit reason — never silently abandoned.

## Behavior

- Continuation instructions are labelled as automatic rounds and state the current round number, so the agent can tell an automatic nudge from a user message.
- Resuming a goal whose budget is already spent grants a fresh budget; otherwise the resume would immediately block again.
- Clearing or deleting a conversation drops its goal. A conversation that never had a goal stores nothing.
- Goals are capped per device and the oldest are dropped when the cap is exceeded, so the stored blob cannot grow without end.
- A corrupt stored blob is treated as "no goals" rather than crashing the chat screen; the next write replaces it.
- Objective text longer than the supported length is truncated, not rejected.

## AI Tools

| Tool | Purpose |
|---|---|
| `create_goal` | Start or replace the goal of this conversation with an objective and an optional round cap |
| `update_goal` | Report the goal as completed, blocked, paused, or active (to resume) |

## Key Files

| File | Purpose |
|---|---|
| `composeApp/src/commonMain/.../data/GoalStore.kt` | Goal model, statuses, persistence, round budget, per-conversation cap |
| `composeApp/src/commonMain/.../data/AppSettings.kt` | Stored goal map and the default round budget |
| `composeApp/src/commonMain/.../tools/GoalTools.kt` | `create_goal` and `update_goal` |
| `composeApp/src/commonMain/.../ui/chat/ChatViewModel.kt` | Continuation loop, budget exhaustion, resume-with-fresh-budget |
| `composeApp/src/commonMain/.../ui/chat/composables/GoalCard.kt` | Goal card with status accent and round counter |
| `composeApp/src/commonMain/.../ui/settings/ToolsSettings.kt` | Default round budget setting |
