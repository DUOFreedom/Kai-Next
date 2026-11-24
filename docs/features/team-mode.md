# Team Mode

**Last verified:** 2026-11-18

Team mode answers one request with a short plan instead of a single reply. A planner splits the request into ordered steps, each step is handed to a specialist worker that owns one family of tools, and the results are folded back together — so a task that needs a screenshot, a shell command and a web lookup does not have to happen one user message at a time.

## Concepts

### Plan

An ordered list of steps produced from the request, capped by a step budget. Each step has a short id (`s1`, `s2`), a one-line description of the action, the worker kind that should perform it, and the ids of steps that must finish first. The plan is shown as a card the moment it exists, before any step runs, because a team turn makes one model call per step and can take minutes.

### Worker

A specialist that executes one step. Four kinds exist, and the split is deliberate: each kind sees only its own tool family, which keeps its prompt small and its tool choice unambiguous.

- **UI** — drives the device: taps, typing, navigation.
- **SYSTEM** — runs shell commands in the sandbox.
- **RESEARCH** — reads the web and fetches URLs.
- **GENERAL** — reasoning with no tool family, and the fallback when a plan names a kind that is not wired.

### Blackboard

The shared context every step writes to and reads from. A step often needs what an earlier step found rather than what its immediate predecessor returned — step three may need the package name discovered in step one — so results go to one place instead of being threaded through every call. Both the per-entry size and the whole digest are capped, because one verbose tool result would otherwise dominate every later prompt.

### Degraded Plan

When the planner's reply cannot be read — prose instead of JSON, a provider error, an empty answer — the run does not fail. It collapses to a single GENERAL step carrying the original instruction, and the plan card reports that the plan was degraded. A one-step plan still does the work; failing the request over a formatting problem would throw away a good request.

### Dependency

Steps declare what must finish before they start. Independent steps run at the same time; a step never starts before the steps it declares are done. A step whose dependency failed is skipped without spending a model call, since its premise is already false.

## Behavior

- Team mode is switched from the Team/Solo control in the chat stats strip. The switch is always offered; the choice is per session and not persisted.
- A team turn does not write to the chat history: the plan card carries the original request, so the request is visible even though the transcript does not hold it.
- The planner is asked for one action per step and told that fewer is better. A request that is a single action comes back as one step rather than being padded into a plan.
- Step ids are short and stable, because they appear in prompts and on the blackboard; a model references them without transcription errors.
- The planner's reply is read tolerantly: markdown fences, a prose prefix, a summary appended afterwards, and braces inside strings all parse. A dependency on an id that is not in the plan is dropped, since it would otherwise wait forever for a step that will never run.
- Every step gets the blackboard digest as context, and each result is written back to it. A worker that declines to call a tool succeeds with a plain answer — that is the correct outcome for a reasoning step.
- A tool named by the model that the worker does not have fails that step and names the tools that were available.
- Concurrency is bounded by a permit queue rather than by slicing the ready steps into fixed chunks, so a freed slot goes straight to the next step instead of idling behind a slow neighbour.
- The whole run has a time budget. A plan that cannot finish inside it stops with a timeout reason rather than hanging, and the card shows how many steps had completed.
- A plan with no steps, a dependency cycle, and a step with no worker registered are all reported as failures with a reason, never as a silent stop.
- The card shows one glyph per step — pending, running, done, failed, skipped — so a failed step is identifiable when the run ends.
- Cancelling the turn cancels every in-flight step; no step outlives the turn that started it.

## Limitations

- The planner sees the request and whatever context the caller supplies, not the chat transcript. A follow-up that depends on an earlier message has to be restated.
- Workers do not see each other's raw output, only the blackboard digest, which is clipped. A step that needs the full text of an earlier result should re-fetch it.
- A degraded plan is a single step: the request is still attempted, but it loses the parallel and specialist routing that team mode exists for.
- Team mode spends one model call per step plus one for planning, so it is more expensive than a normal turn. It is not the default.
- The run has a fixed total budget. Raising it is a code change, not a setting.

## Key Files

| Path | Role |
| --- | --- |
| `composeApp/src/commonMain/kotlin/com/kainext/duo/agents/SwarmModels.kt` | Plan, step, worker-kind and result types; the one-line run summary |
| `composeApp/src/commonMain/kotlin/com/kainext/duo/agents/SwarmPrompts.kt` | Prompt wording and the tolerant JSON parser for plans and tool choices |
| `composeApp/src/commonMain/kotlin/com/kainext/duo/agents/Planner.kt` | Turns a request into a plan; collapses to one step when the reply is unusable |
| `composeApp/src/commonMain/kotlin/com/kainext/duo/agents/Worker.kt` | The tool-backed and direct-answer workers |
| `composeApp/src/commonMain/kotlin/com/kainext/duo/agents/Blackboard.kt` | Shared step context, with per-entry and digest caps |
| `composeApp/src/commonMain/kotlin/com/kainext/duo/agents/SwarmOrchestrator.kt` | Dependency scheduling, concurrency cap, timeout and per-step callbacks |
| `composeApp/src/commonMain/kotlin/com/kainext/duo/ui/chat/composables/TeamPlanCard.kt` | The plan card: request, per-step status glyphs and the run summary |
| `composeApp/src/commonMain/kotlin/com/kainext/duo/ui/chat/ChatViewModel.kt` | `runTeamTurn`: wires the planner, the four workers and the card state |
| `composeApp/src/commonTest/kotlin/com/kainext/duo/agents/SwarmPromptsTest.kt` | Parser contract: fences, prose, braces in strings, dangling dependencies |
| `composeApp/src/commonTest/kotlin/com/kainext/duo/agents/PlannerTest.kt` | Degradation contract: empty instruction, provider error, unparseable reply |
| `composeApp/src/commonTest/kotlin/com/kainext/duo/agents/WorkerTest.kt` | Tool choice, unknown tool, no-tool answer, cancellation |
| `composeApp/src/commonTest/kotlin/com/kainext/duo/agents/BlackboardTest.kt` | Both clipping bounds and the "earlier entries omitted" marker |
| `composeApp/src/commonTest/kotlin/com/kainext/duo/agents/SwarmOrchestratorTest.kt` | Dependency order, concurrency cap, cycle, timeout, failure propagation |
