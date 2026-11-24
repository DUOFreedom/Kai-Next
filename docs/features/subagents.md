# Subagents

**Last verified:** 2026-11-17

A subagent is a throwaway agent the assistant can spawn to handle one side errand and report only its conclusion. Its tool calls and intermediate reasoning never enter the user's transcript, so an investigation that would otherwise leave a dozen tool rows behind costs one result.

## Concepts

### Subagent

A short-lived agent with its own private work log and a bounded tool budget. It sees the task text it was given and nothing else of the conversation, so the parent must state every fact the child needs.

### Work Log

The child's private record of what it has done so far: each step, the tool called, its arguments, and the observed result. The log is trimmed from the oldest end once it grows too large — the newest observations are what the next decision needs, and an unbounded log would crowd out the tool catalogue itself.

### Step Budget

How many tool calls the child may make before it must summarise. Configurable per call within a supported range. When the budget is exhausted the child is asked once more for a best-effort answer, so the parent gets something usable instead of a bare "out of steps".

## Behavior

- The child replies in JSON: either a tool call or a final answer. A reply that is prose instead of JSON is still accepted as the answer — failing it would throw away a usable result over a formatting mistake.
- The child gets the user's enabled tool family minus the ability to spawn its own children. That exclusion is the recursion guard: subagents cannot nest.
- A repeated identical tool call with identical arguments three times in a row is treated as a loop and reported as an error, so the remaining budget is not burned on no new information.
- A model or tool failure inside the child is reported back as a failed result rather than thrown, so the parent can adapt instead of unwinding the whole turn. Cancellation is the one exception and always propagates.
- The returned answer is capped; a longer answer is truncated with an ellipsis.
- A child may spend several tool calls, each with its own timeout, so the parent's own timeout envelope for this call is generous — the child's step budget is what actually bounds the work.
- Failures and unknown tool names come back with enough detail (available tool names, the failing message) for the parent to retry sensibly.
- The subagent's work is not shown in the chat; only the parent's summary of it is.

## AI Tools

| Tool | Purpose |
|---|---|
| `run_subagent` | Delegate a self-contained task to a subagent that reports only its conclusion |

## Key Files

| File | Purpose |
|---|---|
| `composeApp/src/commonMain/.../agents/SubagentRunner.kt` | Child loop, work log, step budget, loop detection, JSON extraction, failure reporting |
| `composeApp/src/commonMain/.../tools/SubagentTools.kt` | `run_subagent`, recursion guard, step clamping, result shaping |
