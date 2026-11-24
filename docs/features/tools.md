# Tools

**Last verified:** 2026-11-17

Kai's tools feature allows the AI to execute external functions during conversations — web search, notifications, calendar events, shell commands, memory operations, and more. Tools are defined with a schema, executed with safety guards, and managed through per-tool toggles in settings.

## Concepts

### Tool

An executable function the AI can invoke during a conversation. Each tool declares a schema (name, description, parameters), a timeout (default 30 seconds), and an execute method that receives parsed arguments and returns a result.

### Tool Schema

The machine-readable definition sent to the AI provider so it knows which tools are available and how to call them. Contains the tool name, a natural-language description, and a map of parameter definitions (type, description, required flag).

### Tool Info

Display metadata used in the settings UI. Contains an id, human-readable name and description (with optional localized string resources), the current enabled state, and a flag for whether the tool gets its own switch in the Tools tab. Returned by the platform layer for all tools regardless of whether they are currently enabled.

Every tool a platform can execute must have a definition, including tools no per-tool switch controls. Chat resolves a tool's display name by looking its id up in this list, so a missing definition surfaces the raw id (`check_notifications`) instead of a name. Tools whose availability is decided elsewhere — a master toggle in Settings → Agent, or a platform capability — are marked as not user-toggleable at their declaration, which is what keeps them out of the Tools tab without hiding them from the name lookup.

### Tool Executor

The component that looks up a tool by name, parses JSON arguments into a typed map, runs the tool with its declared timeout, catches errors, and truncates oversized results. Acts as the bridge between raw AI tool-call JSON and the typed tool implementations.

An argument explicitly sent as JSON `null` is treated as not provided, so the tool falls back to its default instead of receiving the text "null". Failures (unknown tool, unparseable arguments, timeout, exception) come back to the AI as a JSON object with `success: false` and an `error` message, built as proper JSON so messages containing quotes or newlines stay valid.

## Available Tools

### Common (cross-platform)

| Tool | Description | Default |
|---|---|---|
| `web_search` | Search the web for current information | Enabled |
| `get_local_time` | Get the current local date and time | Enabled |
| `get_location_from_ip` | Get estimated location from IP address | Enabled |
| `open_url` | Open a URL, link, or local file on the device | Enabled |
| `fetch_url` | Fetch an http(s) URL and return the response body to the agent (GET, POST, HEAD). Blocks loopback, private, link-local and CGNAT addresses (including IPv4-mapped IPv6 and numeric shorthands like `2130706433`), checked again on every redirect hop (GET/HEAD follow up to 5 redirects; POST never redirects). Hostnames that only resolve to private addresses are not detected. Reads at most 512 KB of the body, which is then subject to the global 20K tool-result truncation. Used for reading pages and acting on links from emails (e.g. RFC 8058 one-click unsubscribe). | Enabled |

#### open_url platform behavior

The `open_url` tool accepts both web URLs and `file://` URIs, opened with an `ACTION_VIEW` intent. `file://` URIs are limited to the Linux sandbox's `/root` (e.g. `file:///root/site/index.html`) and resolved exactly like `open_file`, then served through FileProvider so the file opens in the appropriate app (e.g. `.html` files open in the browser). Any other local path is refused, so the model can't hand app-private files (settings, databases) to another app.

### Agent work (cross-platform)

Tools for the shape of the work itself rather than the outside world: what the agent is trying to finish, which steps are left, what got condensed out of the conversation, and handing a side errand to a child agent. None of them follow a master toggle in Settings → Agent — each has its own switch in the Tools tab.

| Tool | Description | Default |
|---|---|---|
| `todo_write` | Show the user a task list for a multi-step job and keep it up to date | Enabled |
| `create_goal` | Record a long-running objective so the agent keeps working on it across replies | Enabled |
| `update_goal` | Report whether the active goal is done, paused, or blocked | Enabled |
| `run_subagent` | Delegate a self-contained investigation to a subagent that reports only its conclusion (Android) | Enabled |
| `search_context` | Search the summaries of folded conversation segments | Enabled |
| `decompress` | Read the original transcript behind one folded block, by block id | Enabled |
| `context_status` | List the folded blocks kept for this conversation and what they cover | Enabled |

These four families are deliberately ungated by the memory, scheduling or email switches, because they describe the conversation rather than reach outside it: a multi-step turn needs somewhere to show progress, a job that outlives one reply needs to say so, and a compaction that condenses history away needs a way to read behind the summary. See [task-list.md](task-list.md), [goals.md](goals.md), [subagents.md](subagents.md) and [context-folding.md](context-folding.md).

`run_subagent` is registered by the Android platform layer after the rest of the tool family is assembled, so the child inherits exactly the tools the user has enabled. The child is never offered `run_subagent` itself, which is what bounds the tree.

### Memory

| Tool | Description |
|---|---|
| `memory_store` | Store or update a memory with a descriptive key |
| `memory_forget` | Delete a stored memory by its exact key |
| `memory_learn` | Store a structured learning with a category |
| `memory_reinforce` | Reinforce a stored memory by incrementing its hit count |

Memory tools cannot be individually disabled. They are available whenever the memory feature is enabled (the default). See [memories.md](memories.md).

### Scheduling & Heartbeat

Scheduling tools and heartbeat tools are available when the scheduling feature is enabled (the default). See [tasks.md](tasks.md) and [heartbeat.md](heartbeat.md).

| Tool | Description |
|---|---|
| `schedule_task` | Schedule a prompt to run later, on a recurring schedule, or on every heartbeat |
| `list_tasks` | List scheduled tasks with their ids and status |
| `cancel_task` | Cancel a scheduled task by id |
| `promote_learning` | Append a well-reinforced memory to the soul/system prompt and remove the memory. Callable from any conversation, not only heartbeat runs |

### Email

Email tools are available whenever the email feature is enabled (the default), including before any account is connected, so the assistant can connect one from chat.

| Tool | Description |
|---|---|
| `setup_email` | Connect an email account (auto-detects server settings for common providers) |
| `check_email` | List emails that arrived since Kai last surfaced new mail |
| `read_email` | Read the full body of an email |
| `search_email` | Search the whole inbox by sender, subject, or date |
| `reply_email` | Reply to an email with proper threading |
| `compose_email` | Compose and send a new email |

Tools that operate on a specific account accept either the internal account ID or the account's email address, and the account reference can be omitted entirely when only one account is connected. When a reference doesn't match any connected account, the error lists the connected accounts so the assistant can correct itself instead of concluding that no account is set up.

After a reply or a newly composed email is sent, a copy of the outgoing message is saved to the account's Sent folder on the mail server so sent mail stays auditable from any mail client. The folder is resolved in order: the folder configured during account setup, the Sent mailbox the server itself advertises (which also covers localized folder names), then common Sent folder names. If none exists — typical for freshly created mailboxes — the folder is created and the copy saved there. Gmail accounts are skipped because Gmail stores sent messages itself. Saving the copy is best-effort — if it fails, the send still succeeds and the tool result carries a warning instead.

### SMS and notifications (Android, FOSS build only)

SMS tools (`check_sms`, `read_sms`, `search_sms`, `send_sms`, `reply_sms`) and notification tools (`check_notifications`, `read_notification`, `search_notifications`) exist only on the FOSS Android build. They follow their opt-in toggles in Settings → Agent plus the matching system permission or listener access, and have no per-tool switches. See [sms.md](sms.md) and [notifications.md](notifications.md).

### Platform-specific (Android)

| Tool | Description | Default |
|---|---|---|
| `send_notification` | Send a push notification to the device | Enabled |
| `create_calendar_event` | Create a calendar event on the device | Enabled |
| `set_alarm` | Set an alarm or countdown timer | Enabled |
| `execute_shell_command` | Execute a shell command in the Linux sandbox | Follows the sandbox switch; no switch of its own |
| `manage_process` | Manage background shell processes (list, log, kill, remove) | Follows the sandbox switch; no switch of its own |
| `ssh_configure_host` | Register a named SSH host alias for the Linux sandbox so subsequent shell calls can use `ssh <alias>`. Rides along whenever the sandbox is installed and enabled. SSH multiplexing (ControlMaster) is intentionally not enabled — Android blocks the `link()` syscall OpenSSH uses for control sockets, so every `ssh` call does a full TCP and authentication handshake. | Follows the sandbox switch; no switch of its own |
| `open_file` | Open a file from the sandbox in an Android app (browser, image viewer, etc.) | Enabled |

#### Linux Sandbox (Android)

When the Linux Sandbox is set up and enabled, `execute_shell_command` routes commands through proot into a Debian or Alpine rootfs instead of running them in Android's native shell. This provides:

- A full Linux userland with bash, coreutils, and standard utilities
- Package management via `apt` (Debian) or `apk` (Alpine)
- Optional Python installation (`python3`, pip)
- Network access (shares the host network stack)

The tool's description is composed per distribution, so the model is told the right package manager, the right distribution name and the right pre-installed set. Handing it "Alpine" and an `apk add` example while a Debian rootfs is mounted costs turns on commands that do not exist.

**Setup flow:** The sandbox requires a one-time setup that downloads a rootfs — Debian (~150 MB, the default and the one Kai Build shares) or Alpine (~4 MB). Proot is bundled in the APK as a native library. After setup, users can optionally install the extra package bundle. The distribution can be changed at any time afterwards; each keeps its own install, so switching re-points the tool rather than reinstalling anything, and the tool's description follows the newly selected system.

**Mirror fallback (Alpine):** The downloader tries the primary Alpine CDN first, then falls back through a list of official mirrors (kernel.org, RWTH Aachen, ETH Zürich, Waterloo, Tsinghua) so setup succeeds in regions where the primary CDN is unreachable. The same mirror list is also used to pick `/etc/apk/repositories` during setup — `apk update` is retried against each mirror until one succeeds, so later `apk add` calls resolve through a reachable mirror. Debian resolves a single architecture-matched URL from the Linux Containers image index.

**Architecture:** Proot is a user-space chroot implementation that intercepts syscalls via ptrace. No root access is required. The rootfs and tmp directory live in the app's internal files directory — one directory per distribution, which is what lets both exist and be switched between — and `/root` is part of the rootfs so binaries installed into the home directory can actually be executed. `/root/projects` is bind-mounted from the externally-visible app directory. FileProvider is configured for both areas, so `open_file` works anywhere in the tree. Sandboxes installed before the Debian option existed keep their older layout, with the whole of `/root` bound in from external storage.

**Settings:** The sandbox section appears in Settings > Linux Sandbox on Android and contains a single card — titled with the installed distribution, or the one about to be installed — with a distribution picker (before install only), the install / install-basic-packages / uninstall actions and the "use sandbox vs native shell" toggle. Day-to-day usage (terminal, file browser, packages) is **not** in Settings — it lives behind the chat-bar shortcut.

**Chat-bar toggle:** A terminal icon next to the new-chat button in the chat top bar (Android only) toggles the chat body between the conversation view and the inline sandbox view — no navigation, no separate screen. The icon adopts a primary-tinted "selected" pill while the sandbox is open, and the message-input bar is hidden so the terminal/file browser have full vertical space. The other top-bar buttons (settings, history, +, TTS) stay visible and operational; tapping **+** or selecting a saved chat from the history sheet auto-collapses the sandbox view so the user lands on the chat they just chose. When the sandbox is ready the inline view hosts three sub-tabs — **Terminal** (interactive shell, default), **Files** (built-in file browser starting at `/root` — tap files to open in the user's default Android app via the same FileProvider/Intent path as `open_file`, or fall back to a built-in editable text editor with a Save action; the listing refreshes on its own each time the tab becomes visible, so files the assistant created or changed through the shell show up without any user action; an import action copies a file from device storage into the directory on screen, which is the only way to get a file into the sandbox without going through the agent), and **Packages** (search / install / uninstall / upgrade through whichever package manager is installed — see the sandbox doc for the search ranking rules). When the sandbox isn't installed yet, the inline view shows the install button so users don't have to dive into Settings before they can start.

#### Open File (Android)

The `open_file` tool fires an `ACTION_VIEW` Intent for a single file at `path` (relative to `/root`). Browser opens HTML, image viewer opens PNG/JPG, PDF viewer opens PDF, etc. Uses the existing FileProvider declared in the main manifest. Path is rejected if it contains a leading slash or `..` segments; the resolved path is canonicalized and verified to stay under the sandbox home root before the Intent is fired.

The Intent's MIME type is resolved from the file extension. Android's built-in extension table is the primary source, but it is overridden where it is wrong or absent for a Linux sandbox: `.apk` has no entry at all (so the intent used to go out as a wildcard and the package installer never appeared in the chooser), and `.ts` resolves to an MPEG transport stream when in this context it is nearly always TypeScript. Source and config extensions that Android does not know — the bulk of what the sandbox produces — fall back to plain text so a viewer is offered instead of an empty chooser.

Handing an `.apk` to the system package installer additionally requires the "install unknown apps" permission, which Play Store policy restricts to app stores, file managers and browsers. It is therefore declared in the FOSS flavor only, matching how SMS and notification reading are handled. On the Play Store build, opening an apk reports that the build cannot install apps rather than launching an installer that would refuse anyway. On the FOSS build the user still grants the permission once through the system prompt.

`open_file` operates on a single file. FileProvider grants access only to the specific URI in the Intent, so it does not work for multi-file content (e.g. an HTML page that loads sibling `styles.css` / `script.js`). For HTML, write self-contained files with inline CSS and JavaScript; the shell and `open_file` tool descriptions tell the agent to do this.

#### Shell command parameters

These parameters are supported:

| Parameter | Type | Description |
|---|---|---|
| `command` | string (required) | The shell command to execute |
| `timeout` | integer | Timeout in seconds (default 30, max 60) |
| `working_dir` | string | Working directory for the command |
| `env` | object | Environment variables to set as key-value pairs |
| `background` | boolean | Run in background and return a session_id immediately |
| `fresh` | boolean (Android only) | Run this command in a one-shot proot invocation isolated from the conversation's persistent bash session |

When `background=true`, the command starts asynchronously and returns a `session_id`. The AI then uses the `manage_process` tool to check status, retrieve output, or terminate the process.

#### Persistent bash session (Android)

On Android, each conversation gets its own persistent bash session inside the Linux sandbox. This is the default execution mode for `execute_shell_command`: `cd`, environment variable exports, shell functions, and other in-shell state carry across calls within the same conversation, so the AI can build up working context the same way a human terminal user does. Passing `fresh: true` opts out of the persistent session for a single call — that command runs in a one-shot proot invocation with no shared state, useful when the AI wants a clean environment without disturbing the ongoing session.

The tool description is composed per distribution, so the AI knows which package manager and which pre-installed set it is talking to.

#### Process management

The `manage_process` tool is automatically available when the shell command tool is enabled. It supports these actions:

| Action | Description |
|---|---|
| `list` | Show all running and finished background processes |
| `log` | Get output from a process (with offset/limit for paging) |
| `kill` | Terminate a running process |
| `remove` | Remove a finished process from the list |

#### Output handling

Command output uses smart middle truncation: when output exceeds the limit, both the beginning and end are preserved with a truncation marker in the middle. This ensures error messages (typically at the end) are not lost.

Output limits: 15,000 chars per stream. Final tool results are truncated at 20,000 chars.

## Execution Flow

1. The AI responds with one or more tool calls (name + JSON arguments)
2. For each call, a TOOL_EXECUTING entry is added to chat history (shows a spinning icon in the UI)
3. All tool calls in the response are executed in parallel using coroutine async/await
4. Each execution goes through the tool executor: find tool by name, parse arguments, run with timeout, truncate result
5. TOOL_EXECUTING entries are removed and replaced with TOOL result entries (tool call id, tool name, result string)
6. The updated history (including tool results) is sent back to the AI
7. The AI may respond with more tool calls — repeat from step 1
8. When the AI responds with no tool calls, the final text is returned to the user

Pressing stop cancels in-flight tool executions and removes the executing indicators from the chat; a cancelled tool is not reported to the AI as a failed result.

The loop supports OpenAI-compatible, Gemini, and Anthropic provider formats, with provider-specific serialization of tool calls and results. GPT-family models on OpenAI's own endpoint that require it go through OpenAI's Responses API instead of Chat Completions; the same tool loop drives both.

## Safety Guards

### Iteration limit

The tool loop runs a maximum number of rounds per message — 100 by default, adjustable from 3 to 200 in Settings → Tools. If the limit is exceeded, the AI is asked to respond with the best answer it has so far, with tools removed from the request.

The limit is read once per turn, so a change in Settings applies from the next message rather than mid-turn. Installs that never touched the setting are migrated once from the older default of 30 to the current 100, so a long task that used to be cut off now finishes in one message.

### Repeated call detection

Each tool call produces a signature from its name and arguments hash. If the same signature pattern appears a configurable number of consecutive times — 5 by default, adjustable from 2 to 20 in Settings → Tools — the loop is stopped and the AI is asked to respond with what it has.

### Parallelism

Batch tool calls run concurrently, up to a configurable number of permits — 20 by default, adjustable from 1 to 32 in Settings → Tools. Unbounded fan-out on a phone means a dozen shell probes and file reads competing for the same cores and memory, which is slower and can end in the process being killed. The limit applies per batch, not per conversation.

### Timeout

Each tool has a configurable timeout defaulting to 30 seconds. If execution exceeds the timeout, the call is cancelled and an error is returned as the tool result. The shell tool enforces its own per-call timeout (up to 60 s), so its outer limit sits above that maximum.

Cancelling a shell call — the user pressing stop, or the outer timeout — stops the command instead of abandoning it. The persistent shell interrupts the foreground command (SIGINT → SIGTERM → SIGKILL) and resets the session if that fails, before the next call can start, so a stale command's output never leaks into the next result.

### Result truncation

Tool results longer than 20,000 characters are truncated with a note indicating the original length.

### Context trimming

Before every tool loop request, the message history sent to the model is trimmed to fit within the model's context window. All three providers (OpenAI-compatible, Gemini, Anthropic) trim per request. Only the outgoing copy is trimmed: the conversation shown in the chat and saved to disk keeps every message. Context window sizes are estimated per model (e.g. Gemini 2.5 = 1M tokens, Claude = 200K, GPT-4o = 128K, small local models = 8–32K) and oldest messages are dropped first while preserving the system prompt.

Trimming preserves the tool-call pairing required by strict OpenAI-compatible providers (e.g. DeepSeek via OpenCode Zen): an assistant turn that requested tool calls is dropped together with the tool responses that answer it, never split. A tool result is never kept without the assistant message that requested it; on Gemini and Anthropic, tool results left at the start of the trimmed history are dropped.

### Tool-call message sanitization (OpenAI-compatible)

Strict OpenAI-compatible providers reject a request when an assistant message carrying `tool_calls` is not immediately followed by one tool response per `tool_call_id`, when a tool response has no preceding `tool_calls`, or when a `tool_calls` entry references a tool that is not also declared in the request's `tools[]` array (HTTP 400). Before every OpenAI-compatible request, the outgoing message list is sanitized to enforce these invariants: each assistant tool-call turn is paired with the tool responses that follow it, any tool call referencing a tool not declared on the current request (e.g. because the user toggled the tool off mid-conversation, or the request makes a final tools-less bailout call) is stripped along with its paired response, any tool call left unanswered (e.g. by an interrupted run or aggressive trimming) is stripped, orphan tool responses are dropped, and an assistant turn left with neither text nor tool calls is removed. Gemini and Anthropic use their own native serialization and are unaffected by this pass.

### Context window overflow protection

When the fallback chain is active, each fallback service is checked before use. If the current conversation exceeds a fallback model's estimated context window, that service is skipped. If no service in the chain has a large enough window, an error message is shown to the user.

### Chat history compaction

When conversation history exceeds 70% of the primary model's context window, an AI-powered compaction runs before the next API call. Older messages are summarized into a single compact entry via a separate LLM call, while the most recent 4 user exchanges are kept verbatim. If the summarization call fails, the history is left intact while it still fits the context window; older messages are dropped only when it no longer fits at all. Stopping the request during compaction never changes the history.

Each compaction keeps the text it replaced in a folded block on the device, so the summary is not a dead end: `search_context` finds the block a topic was condensed into, `decompress` reads the original transcript back by block id, and `context_status` lists what is stored. See [context-folding.md](context-folding.md).

### Approval gate

Before a tool with side effects runs, the executor consults the approval policy. High-risk tools — shell commands, device control, SMS, email, alarms, notifications, subagents — pause and ask the user first under the default policy; a standing permission for that tool skips the dialog. See [tool-approval.md](tool-approval.md).

## MCP Servers

See [mcp.md](mcp.md) for the full MCP feature spec.

## Tool Enablement

Tool availability is controlled at multiple levels:

- **Feature-level gates** — memory tools require memory enabled, scheduling/heartbeat tools require scheduling enabled, email tools require email enabled
- **Sandbox install gate (Android)** — `execute_shell_command`, `manage_process`, and `ssh_configure_host` are surfaced only when the Linux sandbox is actually installed (Ready) *and* the sandbox toggle is on. Until the sandbox is installed these tools are not sent to the model at all; the sandbox toggle itself is hidden until install completes, so there is no state in which they ride along without a working sandbox behind them
- **Per-tool toggles** — individual tools can be enabled or disabled in settings, persisted with a `tool_enabled_` key prefix
- **Default state** — most tools default to enabled. The shell has no per-tool switch on Android — it follows the sandbox switch, which is on by default once the sandbox is installed
- **Master-toggle-only** — memory, scheduling, heartbeat, email, SMS, and notification tools have no individual per-tool toggle; they are on whenever their master switch in Settings → Agent is on (heartbeat is bundled with the scheduling switch). The sandbox tools are gated the same way — by the sandbox switch — and likewise carry no switch of their own
- **On-device (LiteRT) allowlist** — when the active model is an on-device LiteRT model, only a small allowlist of tools is exposed regardless of which other tools are enabled. The current allowlist is: `get_local_time`, `get_location_from_ip`, `web_search`, `open_url`, `memory_store`, `memory_forget`, `memory_reinforce`, and `execute_shell_command`. Memory tools beyond the three listed, email tools, scheduling tools, and heartbeat tools are not surfaced to local models even when their master switches are on.

The platform layer assembles the final list of available tools by checking all gates and per-tool settings, and only enabled tools are sent to the AI provider.

## Settings UI

The tools tab in settings displays a responsive grid of toggle cards:

- 3 columns when the screen is at least 800dp wide
- 2 columns when at least 500dp wide
- 1 column on narrow screens

Each card shows the tool name, a short description, and a toggle switch. Clicking anywhere on the card toggles the tool. Cards use a semi-transparent surface variant background (transparent with an outline border in the OLED theme).

The grid sits below the MCP servers section and, on Android, the Skills section (see [skills.md](skills.md)).

Only individually toggleable tools appear in the grid. Tools whose only control is a master toggle in Settings → Agent (memory, scheduling, heartbeat, email, SMS, notifications) are not listed here — they appear and disappear with their feature switch. The same applies to tools gated by a capability rather than a setting: the sandbox tools follow the sandbox switch.

### Tool call limits

A section above the grid carries the three per-install knobs the tool loop reads: max tool rounds per message, the repeat threshold that stops a stuck loop, and how many tools in one batch run at once. Each is a stepper showing its current value and default, with a single control that restores all three to their defaults. A change applies from the next message onward, since the loop reads the values once per turn.

### Approval policy

The same tab holds the confirmation policy (ask always / ask for risky tools only / never ask) and the list of tools granted a standing permission, with one control to clear them all. See [tool-approval.md](tool-approval.md).

## Chat UI

### Shared pulsing status indicator

The standard chat uses a shared pulsing status indicator that shows:
- A pulsing dot (scale 0.6→1.0, alpha 0.4→1.0, 800ms reverse animation)
- Cycling status text ("Thinking…", "Working…", "Brewing…" rotating every 3 seconds with AnimatedContent fade)
- An optional inline tool summary separated by " · ":
  - **1 tool executing**: shows the tool's display name (e.g., "Thinking… · Learn Memory")
  - **Multiple tools executing**: shows a grouped count (e.g., "Working… · 2 Tools")
  - **No tools**: shows only the cycling status text

The indicator accepts styling parameters (dot size, colors, text style).

### Waiting response row (standard chat)

When loading, a chip appears at the bottom of the chat list containing the pulsing status indicator with surface variant colors, a 16dp dot, and `bodyMedium` text style. The chip uses `animateContentSize` (300ms) for smooth text transitions.

### Interactive mode loading feedback

The interactive-mode top bar shows only the static title — loading is surfaced closer to the user's point of action instead:
- **Clicking a kai-ui action button**: the clicked button keeps its label and pulses (scale/alpha animation) until the response arrives; other buttons in the same message become disabled.
- **First load** (no assistant response yet): a centered waiting row is shown.
- **Typed-and-sent input**: the trailing send icon swaps to a stop icon in the input, as in standard chat.

### Common behavior

- TOOL_EXECUTING entries are not rendered as separate list items
- Completed tool results (TOOL role) are not shown in the UI

## Key Files

| File | Purpose |
|---|---|
| `composeApp/src/commonMain/.../network/tools/Tool.kt` | Tool interface, ToolSchema, ParameterSchema |
| `composeApp/src/commonMain/.../network/tools/ToolInfo.kt` | Display metadata for settings |
| `composeApp/src/commonMain/.../data/ToolExecutor.kt` | Execution, JSON parsing, timeout, truncation |
| `composeApp/src/commonMain/.../data/RemoteDataRepository.kt` | Tool loop (OpenAI-compatible, Responses API, Gemini, Anthropic), parallel execution, context trimming, on-device allowlist |
| `composeApp/src/commonMain/.../data/providers/OpenAIMessages.kt` | OpenAI-compatible message building + tool-call pairing sanitization |
| `composeApp/src/commonMain/.../tools/CommonTools.kt` | Common tool implementations and the cross-platform definition list |
| `composeApp/src/commonMain/.../tools/AgentToolSet.kt` | Shared gating the available-tools list is built from |
| `composeApp/src/commonMain/.../tools/TodoTools.kt` | `todo_write` |
| `composeApp/src/commonMain/.../tools/GoalTools.kt` | `create_goal`, `update_goal` |
| `composeApp/src/commonMain/.../tools/SubagentTools.kt` | `run_subagent` |
| `composeApp/src/commonMain/.../tools/ContextFoldTools.kt` | `search_context`, `decompress`, `context_status` |
| `composeApp/src/commonMain/.../data/ToolApproval.kt` | Approval request/outcome models and the shared gate between executor and UI |
| `composeApp/src/commonMain/.../data/ToolRiskRegistry.kt` | Tool id → risk classification |
| `composeApp/src/commonMain/.../tools/WebSearchTool.kt` | `web_search` |
| `composeApp/src/commonMain/.../tools/FetchUrlTool.kt` | `fetch_url` with private-address and redirect checks |
| `composeApp/src/commonMain/.../tools/SchedulingTools.kt` | `schedule_task`, `list_tasks`, `cancel_task` |
| `composeApp/src/commonMain/.../tools/HeartbeatTools.kt` | `promote_learning` |
| `composeApp/src/commonMain/.../tools/EmailTools.kt` | Email tools |
| `composeApp/src/commonMain/.../tools/SmsTools.kt` | SMS tools (FOSS Android) |
| `composeApp/src/commonMain/.../tools/NotificationTools.kt` | Notification tools (FOSS Android) |
| `composeApp/src/androidMain/.../tools/ShellCommandTool.kt` | Android sandbox shell tool (per-distribution description) |
| `composeApp/src/androidMain/.../tools/SshConfigureHostTool.kt` | `ssh_configure_host` |
| `composeApp/src/androidMain/.../sandbox/PersistentSandboxShell.kt` | Per-conversation persistent bash session and command interruption |
| `composeApp/src/commonMain/.../Platform.kt` | Platform expect declarations for available tools |
| `composeApp/src/androidMain/.../Platform.android.kt` | Android tool gating and the Android definition list |
| `composeApp/src/androidMain/.../tools/SendNotificationTool.kt` | Android notification-posting tool |
| `composeApp/src/androidMain/.../tools/CreateCalendarEventTool.kt` | Android calendar-event tool |
| `composeApp/src/androidMain/.../tools/SetAlarmTool.kt` | Android alarm / countdown-timer tool |
| `composeApp/src/androidMain/.../sandbox/LinuxSandboxManager.kt` | Sandbox lifecycle, setup, proot management |
| `composeApp/src/androidMain/.../sandbox/ProotExecutor.kt` | Proot command building and execution |
| `composeApp/src/androidMain/.../linux/` | Shared Linux runtime: distro specs, rootfs download and extraction, installer, proot launcher, guest-path resolution |
| `composeApp/src/commonMain/.../linux/LinuxDistro.kt` | The distributions, their package sets, and their package-manager commands and parsers |
| `composeApp/src/jvmShared/.../tools/ProcessManagerTool.kt` | Process management tool shared with the Android platform layer |
| `composeApp/src/androidMain/.../tools/ProcessManager.kt` | Android background process tracking (proot sandbox) |
| `composeApp/src/androidMain/.../tools/OpenFileTool.kt` | Open sandbox file in an Android app via FileProvider Intent |
| `composeApp/src/androidMain/.../sandbox/SandboxFiles.kt` | Path translation, MIME resolution, text-vs-binary reads, streamed imports, and the FileProvider open helper — all shared with the file browser |
| `androidApp/src/main/res/xml/file_paths.xml` | FileProvider path config (includes `sandbox-home/`) |
| `composeApp/src/commonMain/.../SandboxController.kt` | Cross-platform sandbox interface |
| `composeApp/src/commonMain/.../ui/sandbox/SandboxTabsContent.kt` | Terminal/Files/Packages sub-tab UI rendered inline inside the chat screen body |
| `composeApp/src/commonMain/.../ui/sandbox/SandboxFileBrowserScreen.kt` | User-facing file browser UI, pointed at either Linux environment |
| `composeApp/src/commonMain/.../ui/sandbox/SandboxFileBrowserViewModel.kt` | State for browsing and editing sandbox files |
| `composeApp/src/commonMain/.../ui/settings/ToolsSettings.kt` | Tools tab: MCP and Skills sections plus the per-tool toggle grid |
| `composeApp/src/commonMain/.../ui/settings/SandboxSettings.kt` | Linux Sandbox settings card |
| `composeApp/src/commonMain/.../ui/chat/composables/ToolMessage.kt` | Executing/completed UI indicators |
| `composeApp/src/commonMain/.../data/AppSettings.kt` | Tool enabled state persistence |
