package com.kainext.duo.ui.chat.composables

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kainext.duo.data.AgentGoal
import com.kainext.duo.data.AgentGoalStatus
import kai.composeapp.generated.resources.Res
import kai.composeapp.generated.resources.goal_blocked_round_limit
import kai.composeapp.generated.resources.goal_card_title
import kai.composeapp.generated.resources.goal_complete
import kai.composeapp.generated.resources.goal_pause
import kai.composeapp.generated.resources.goal_resume
import kai.composeapp.generated.resources.goal_rounds
import kai.composeapp.generated.resources.goal_status_active
import kai.composeapp.generated.resources.goal_status_blocked
import kai.composeapp.generated.resources.goal_status_completed
import kai.composeapp.generated.resources.goal_status_paused
import org.jetbrains.compose.resources.stringResource

/**
 * The conversation's long-running goal, shown above the composer.
 *
 * A goal outlives a single answer: the agent keeps taking rounds until it calls
 * `update_goal`. That makes the goal a piece of state the user must be able to see and
 * steer — otherwise the assistant appears to work on its own with no way to stop it.
 */
@Composable
internal fun GoalCard(
    goal: AgentGoal,
    onSetStatus: (AgentGoalStatus) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = when (goal.status) {
        AgentGoalStatus.ACTIVE -> MaterialTheme.colorScheme.primary
        AgentGoalStatus.PAUSED -> MaterialTheme.colorScheme.onSurfaceVariant
        AgentGoalStatus.COMPLETED -> MaterialTheme.colorScheme.tertiary
        AgentGoalStatus.BLOCKED -> MaterialTheme.colorScheme.error
    }
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(Res.string.goal_card_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = statusLabel(goal.status),
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(Res.string.goal_rounds, goal.rounds.toString(), goal.maxRounds.toString()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = goal.objective,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            goal.blockedReason?.takeIf { it.isNotBlank() }?.let { reason ->
                Spacer(Modifier.height(4.dp))
                Text(
                    // "round_limit" is a machine token so the wording stays localisable;
                    // anything else is the model's own explanation and is shown verbatim.
                    text = if (reason == GOAL_ROUND_LIMIT_TOKEN) {
                        stringResource(Res.string.goal_blocked_round_limit)
                    } else {
                        reason
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(2.dp))
            BoxWithConstraints {
                val compact = maxWidth < 320.dp
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (compact) Arrangement.spacedBy(0.dp) else Arrangement.spacedBy(4.dp),
                ) {
                    when (goal.status) {
                        AgentGoalStatus.ACTIVE -> {
                            TextButton(onClick = { onSetStatus(AgentGoalStatus.PAUSED) }) {
                                Text(stringResource(Res.string.goal_pause), style = MaterialTheme.typography.labelMedium)
                            }
                            TextButton(onClick = { onSetStatus(AgentGoalStatus.COMPLETED) }) {
                                Text(stringResource(Res.string.goal_complete), style = MaterialTheme.typography.labelMedium)
                            }
                        }

                        AgentGoalStatus.PAUSED, AgentGoalStatus.BLOCKED -> {
                            TextButton(onClick = { onSetStatus(AgentGoalStatus.ACTIVE) }) {
                                Text(stringResource(Res.string.goal_resume), style = MaterialTheme.typography.labelMedium)
                            }
                            TextButton(onClick = { onSetStatus(AgentGoalStatus.COMPLETED) }) {
                                Text(stringResource(Res.string.goal_complete), style = MaterialTheme.typography.labelMedium)
                            }
                        }

                        // A finished goal is a record, not a control: no buttons, just the outcome.
                        AgentGoalStatus.COMPLETED -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun statusLabel(status: AgentGoalStatus): String = when (status) {
    AgentGoalStatus.ACTIVE -> stringResource(Res.string.goal_status_active)
    AgentGoalStatus.PAUSED -> stringResource(Res.string.goal_status_paused)
    AgentGoalStatus.COMPLETED -> stringResource(Res.string.goal_status_completed)
    AgentGoalStatus.BLOCKED -> stringResource(Res.string.goal_status_blocked)
}

/** Mirrors `GOAL_BLOCKED_ROUND_LIMIT` in ChatViewModel; kept in sync by the compiler-visible literal. */
private const val GOAL_ROUND_LIMIT_TOKEN = "round_limit"
