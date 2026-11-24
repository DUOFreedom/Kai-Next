# Tool Approval

**Last verified:** 2026-11-17

The agent runs unattended for many steps, so a wrong tool call can delete a file, send a message on the user's behalf, or spend their money before anyone notices. Tool approval is the confirmation gate between the model's decision and the side effect: a call that can do real damage pauses and asks first, and the user can grant a standing permission for one tool so it stops asking.

## Concepts

### Risk

How much damage a tool can do if the model gets it wrong. The useful line is not "which tool" but "which tool can lose data or act in the world":

- **LOW** — reads state only: search, fetch, list, read, clipboard read, clock. Never changes anything.
- **MEDIUM** — writes local app state the user can see and undo: memories, to-dos, files, clipboard write.
- **HIGH** — irreversible or externally visible: shell commands, device control, SMS, email, alarms, notifications, subagents.

An unknown tool is treated as HIGH. A capability the classification table has not seen yet is exactly the case where an unattended write would be most surprising, so the default fails safe.

### Approval Policy

Which tools pause for confirmation.

- **HIGH_RISK** — ask only for high-risk tools. This is the default.
- **ALWAYS** — ask before every tool call. Noisiest, safest.
- **NEVER** — never ask. For users who want the agent to run hands-off.

### Approval Request

One tool call waiting on a yes/no: the tool id, a human-readable name, the risk level, and the raw arguments. Arguments are shown so the user can read the actual command or path before allowing it; long arguments are cut to a bounded preview with an ellipsis.

### Standing Permission

"Always allow" is not a third answer but a modifier on yes: it means the same tool should stop asking for the rest of the install. Standing permissions are per tool id, survive restarts, and can be revoked from Settings. A call covered by a standing permission skips the dialog entirely, whatever the policy.

## Behavior

- The gate is consulted before the side effect, not after: a denied call never runs and returns a denial to the tool loop, so the agent can adapt instead of assuming success.
- Batch tool calls run in parallel, so two gated calls can arrive at the same moment. They are serialised into one dialog at a time; replacing the visible request would silently deny everything but the last one, which reads as the assistant refusing to work.
- The tool loop suspends while the dialog is open, with no polling: a slow decision is not a failure. The wait is bounded at 120 seconds, and an unanswered request counts as a denial. The deadline exists for runs with no chat screen attached — a heartbeat or scheduled task would otherwise wait forever for an answer nobody can give.
- Cancelling a run drops the visible request and denies it, and also denies callers still queued behind it — otherwise the next queued tool would pop a dialog for a run the user already stopped.
- Dismissing the dialog counts as a denial.
- The dialog offers three answers: allow, always allow, deny.

## Settings

- Settings → Tools has an approval-policy selector (ask always / ask for risky tools only / never ask) with the default shown.
- The same section lists the tools granted a standing permission and offers a single control to clear them all.
- The approval policy is stored by name, so the stored value stays readable if the enum order ever changes. An unrecognised stored value falls back to the default.

## Key Files

| File | Purpose |
|---|---|
| `composeApp/src/commonMain/.../data/ToolApproval.kt` | Risk and policy enums, request/outcome models, the shared gate between executor and UI |
| `composeApp/src/commonMain/.../data/ToolRiskRegistry.kt` | Tool id → risk classification, unknown tools default to high |
| `composeApp/src/commonMain/.../data/ToolExecutor.kt` | Consults the gate before executing, persists standing permissions |
| `composeApp/src/commonMain/.../data/AppSettings.kt` | Policy and standing-permission persistence |
| `composeApp/src/commonMain/.../ui/chat/composables/ToolApprovalDialog.kt` | The confirmation dialog |
| `composeApp/src/commonMain/.../ui/chat/ChatScreen.kt` | Hosts the dialog while a request is pending |
| `composeApp/src/commonMain/.../ui/settings/ToolsSettings.kt` | Policy selector and standing-permission management |
