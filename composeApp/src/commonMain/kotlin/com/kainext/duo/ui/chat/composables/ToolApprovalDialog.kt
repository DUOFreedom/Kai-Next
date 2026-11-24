package com.kainext.duo.ui.chat.composables

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kainext.duo.data.ToolApprovalRequest
import com.kainext.duo.data.ToolRisk
import kai.composeapp.generated.resources.Res
import kai.composeapp.generated.resources.tool_approval_allow
import kai.composeapp.generated.resources.tool_approval_allow_always
import kai.composeapp.generated.resources.tool_approval_arguments
import kai.composeapp.generated.resources.tool_approval_deny
import kai.composeapp.generated.resources.tool_approval_risk_high
import kai.composeapp.generated.resources.tool_approval_risk_low
import kai.composeapp.generated.resources.tool_approval_risk_medium
import kai.composeapp.generated.resources.tool_approval_title
import org.jetbrains.compose.resources.stringResource

/**
 * Blocking confirmation for a tool the policy considers risky.
 *
 * The dialog is deliberately modal and argument-visible: the point of the gate is that the user
 * sees the exact payload (a shell command, an SMS body) before it leaves the device, so a
 * "the assistant wants to run something" prompt with a hidden argument would defeat it.
 */
@Composable
internal fun ToolApprovalDialog(
    request: ToolApprovalRequest?,
    onRespond: (Boolean) -> Unit,
    onAlwaysAllow: () -> Unit,
) {
    if (request == null) return

    AlertDialog(
        onDismissRequest = { onRespond(false) },
        title = {
            Column {
                Text(
                    text = stringResource(Res.string.tool_approval_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${request.displayName} · ${riskLabel(request.risk)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = when (request.risk) {
                        ToolRisk.HIGH -> MaterialTheme.colorScheme.error
                        ToolRisk.MEDIUM -> MaterialTheme.colorScheme.primary
                        ToolRisk.LOW -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(Res.string.tool_approval_arguments),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                // Long payloads (file writes, HTML bodies) would otherwise push the buttons off
                // screen, so the preview scrolls inside a bounded box.
                Text(
                    text = request.argumentsPreview,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onRespond(true) }) {
                Text(stringResource(Res.string.tool_approval_allow))
            }
        },
        dismissButton = {
            // "Always" sits beside "Deny" rather than beside "Allow": both are answers that close
            // the run's question, while "Allow once" is the repeat-next-time choice.
            Row {
                TextButton(onClick = onAlwaysAllow) {
                    Text(stringResource(Res.string.tool_approval_allow_always))
                }
                TextButton(onClick = { onRespond(false) }) {
                    Text(stringResource(Res.string.tool_approval_deny))
                }
            }
        },
    )
}

@Composable
private fun riskLabel(risk: ToolRisk): String = stringResource(
    when (risk) {
        ToolRisk.LOW -> Res.string.tool_approval_risk_low
        ToolRisk.MEDIUM -> Res.string.tool_approval_risk_medium
        ToolRisk.HIGH -> Res.string.tool_approval_risk_high
    },
)
