package com.kainext.duo.ui.chat.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.kainext.duo.agents.PlanStep
import com.kainext.duo.agents.StepResult
import com.kainext.duo.agents.StepStatus
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * The team-mode run card: the plan the planner produced and the status of each step.
 *
 * This exists because a swarm run makes one model call per step, which can take minutes. Without a
 * visible plan the user cannot tell whether the agent is working on the right thing or stalled, and
 * cannot see which step failed when the run ends.
 */
@Composable
internal fun TeamPlanCard(
    plan: ImmutableList<PlanStep>,
    results: ImmutableList<StepResult>,
    summary: String,
    isRunning: Boolean,
    instruction: String = "",
    modifier: Modifier = Modifier,
) {
    if (plan.isEmpty() && summary.isBlank()) return

    val statuses = results.associate { result -> result.stepId to result.status }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = if (isRunning) "Team working…" else "Team plan",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (instruction.isNotBlank()) {
            // Team turns bypass the chat history, so this card is the only place the request shows.
            Text(
                text = instruction,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
        }
        plan.forEach { step ->
            val status = statuses[step.id] ?: StepStatus.PENDING
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = glyph(status),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = colorFor(status),
                    modifier = Modifier.padding(end = 6.dp),
                )
                Text(
                    text = "${step.assignedWorker.name.lowercase()} · ${step.description}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (summary.isNotBlank()) {
            Text(
                text = summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

private fun glyph(status: StepStatus): String = when (status) {
    StepStatus.PENDING -> "[ ]"
    StepStatus.RUNNING -> "[~]"
    StepStatus.DONE -> "[x]"
    StepStatus.FAILED -> "[!]"
    StepStatus.SKIPPED -> "[-]"
}

@Composable
private fun colorFor(status: StepStatus) = when (status) {
    StepStatus.DONE -> MaterialTheme.colorScheme.primary
    StepStatus.FAILED -> MaterialTheme.colorScheme.error
    StepStatus.RUNNING -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Empty list helper so callers do not need the immutable collections import at call sites. */
internal fun emptyTeamResults(): ImmutableList<StepResult> = persistentListOf()
