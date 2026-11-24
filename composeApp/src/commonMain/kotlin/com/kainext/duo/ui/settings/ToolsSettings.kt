package com.kainext.duo.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kainext.duo.data.AppSettings
import com.kainext.duo.data.GoalStore
import com.kainext.duo.data.ToolApprovalPolicy
import com.kainext.duo.mcp.PopularMcpServer
import com.kainext.duo.network.tools.ToolInfo
import com.kainext.duo.skills.CreativeModeManager
import com.kainext.duo.skills.RegistrySkillEntry
import com.kainext.duo.skills.SkillManifest
import com.kainext.duo.tools.AppPermission
import com.kainext.duo.tools.PermissionController
import com.kainext.duo.ui.handCursor
import com.kainext.duo.ui.kaiAdaptiveCardBorder
import com.kainext.duo.ui.kaiAdaptiveCardColors
import kai.composeapp.generated.resources.Res
import kai.composeapp.generated.resources.settings_goal_rounds_hint
import kai.composeapp.generated.resources.settings_goal_rounds_label
import kai.composeapp.generated.resources.settings_tool_approval_allowed
import kai.composeapp.generated.resources.settings_tool_approval_always
import kai.composeapp.generated.resources.settings_tool_approval_clear
import kai.composeapp.generated.resources.settings_tool_approval_description
import kai.composeapp.generated.resources.settings_tool_approval_high_risk
import kai.composeapp.generated.resources.settings_tool_approval_never
import kai.composeapp.generated.resources.settings_tool_approval_title
import kai.composeapp.generated.resources.settings_tool_limits_description
import kai.composeapp.generated.resources.settings_tool_limits_iterations
import kai.composeapp.generated.resources.settings_tool_limits_iterations_hint
import kai.composeapp.generated.resources.settings_tool_limits_parallel
import kai.composeapp.generated.resources.settings_tool_limits_parallel_hint
import kai.composeapp.generated.resources.settings_tool_limits_repeats
import kai.composeapp.generated.resources.settings_tool_limits_repeats_hint
import kai.composeapp.generated.resources.settings_tool_limits_reset
import kai.composeapp.generated.resources.settings_tool_limits_title
import kai.composeapp.generated.resources.settings_tools_description
import kai.composeapp.generated.resources.settings_tools_none_available
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

@Composable
internal fun ToolsContent(
    tools: ImmutableList<ToolInfo>,
    onToggleTool: (String, Boolean) -> Unit,
    mcpServers: ImmutableList<McpServerUiState>,
    onAddMcpServer: (String, String, Map<String, String>) -> Unit,
    onRemoveMcpServer: (String) -> Unit,
    onToggleMcpServer: (String, Boolean) -> Unit,
    onRefreshMcpServer: (String) -> Unit,
    showAddMcpServerDialog: Boolean,
    onShowAddMcpServerDialog: (Boolean) -> Unit,
    onAddPopularMcpServer: (PopularMcpServer) -> Unit,
    skills: ImmutableList<SkillManifest>,
    onUninstallSkill: (String) -> Unit,
    showAddSkillDialog: Boolean,
    onShowAddSkillDialog: (Boolean) -> Unit,
    onInstallGitHubSkill: (String) -> Unit,
    onInstallBrowsedSkill: (RegistrySkillEntry) -> Unit,
    isInstallingSkill: Boolean,
    skillInstallError: String?,
    browsableSkills: ImmutableList<RegistrySkillEntry>,
    isBrowsingSkills: Boolean,
    browseSkillsFailed: Boolean,
    showSkills: Boolean,
    isSandboxInstalled: Boolean,
    onNavigateToSandbox: () -> Unit,
    creativeManager: CreativeModeManager = koinInject(),
) {
    // 创造模式弹窗的展开/收起状态（本地管理，不穿透到上层 ViewModel）。
    var showCreativeMode by remember { mutableStateOf(false) }
    val onShowCreativeMode = { show: Boolean -> showCreativeMode = show }
    // 技能列表由外部传入，保存后下次进入设置会自动刷新，这里无需额外动作。
    val onSkillCreated = { _: String -> }

    Column(modifier = Modifier.fillMaxWidth()) {
        // MCP Servers section
        McpServersSection(
            mcpServers = mcpServers,
            onAddMcpServer = onAddMcpServer,
            onRemoveMcpServer = onRemoveMcpServer,
            onToggleMcpServer = onToggleMcpServer,
            onRefreshMcpServer = onRefreshMcpServer,
            onToggleTool = onToggleTool,
            showAddDialog = showAddMcpServerDialog,
            onShowAddDialog = onShowAddMcpServerDialog,
            onAddPopularMcpServer = onAddPopularMcpServer,
        )

        // Skills section — sandbox-backed, so Android only.
        if (showSkills) {
            Spacer(Modifier.height(24.dp))
            SkillsSection(
                skills = skills,
                onUninstallSkill = onUninstallSkill,
                showAddDialog = showAddSkillDialog,
                onShowAddDialog = onShowAddSkillDialog,
                onInstallGitHub = onInstallGitHubSkill,
                onInstallBrowsed = onInstallBrowsedSkill,
                isInstalling = isInstallingSkill,
                installError = skillInstallError,
                browsableSkills = browsableSkills,
                isBrowsing = isBrowsingSkills,
                browseFailed = browseSkillsFailed,
                isSandboxInstalled = isSandboxInstalled,
                onNavigateToSandbox = onNavigateToSandbox,
                showCreativeMode = showCreativeMode,
                onShowCreativeMode = onShowCreativeMode,
                onSkillCreated = onSkillCreated,
                creativeManager = creativeManager,
            )
        }

        Spacer(Modifier.height(24.dp))

        // Native tools section
        Text(
            text = stringResource(Res.string.settings_tools_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))

        if (tools.isEmpty()) {
            Text(
                text = stringResource(Res.string.settings_tools_none_available),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val columns = when {
                    maxWidth >= 800.dp -> 3
                    maxWidth >= 500.dp -> 2
                    else -> 1
                }
                val rows = tools.chunked(columns)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    rows.forEach { rowTools ->
                        Row(
                            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            rowTools.forEach { tool ->
                                ToolItem(
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                    tool = tool,
                                    onToggle = { enabled -> onToggleTool(tool.id, enabled) },
                                )
                            }
                            // Fill empty slots so last row items don't stretch
                            repeat(columns - rowTools.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))

        ToolLimitsSection(settings = koinInject())

        Spacer(Modifier.height(16.dp))

        ToolApprovalSection(settings = koinInject())
        Spacer(Modifier.height(16.dp))
        GoalRoundsSection(settings = koinInject())
    }
}

@Composable
private fun ToolItem(
    tool: ToolInfo,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .clip(CardDefaults.shape)
            .clickable { onToggle(!tool.isEnabled) }
            .handCursor(),
        colors = kaiAdaptiveCardColors(),
        border = kaiAdaptiveCardBorder(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tool.nameRes?.let { stringResource(it) } ?: tool.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = tool.descriptionRes?.let { stringResource(it) } ?: tool.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.width(16.dp))

            Switch(
                checked = tool.isEnabled,
                onCheckedChange = onToggle,
            )
        }
    }
}

@Composable
private fun ToolLimitsSection(settings: AppSettings) {
    var iterations by remember { mutableIntStateOf(settings.getMaxToolIterations()) }
    var repeats by remember { mutableIntStateOf(settings.getMaxRepeatedToolCalls()) }
    var parallel by remember { mutableIntStateOf(settings.getToolParallelLimit()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = kaiAdaptiveCardColors(),
        border = kaiAdaptiveCardBorder(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = stringResource(Res.string.settings_tool_limits_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(Res.string.settings_tool_limits_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))

            LimitsStepper(
                label = stringResource(Res.string.settings_tool_limits_iterations),
                hint = stringResource(Res.string.settings_tool_limits_iterations_hint),
                value = iterations,
                step = 5,
                range = AppSettings.MIN_TOOL_ITERATIONS..AppSettings.MAX_TOOL_ITERATIONS_LIMIT,
                onChange = { next ->
                    iterations = next
                    settings.setMaxToolIterations(next)
                },
            )

            LimitsStepper(
                label = stringResource(Res.string.settings_tool_limits_repeats),
                hint = stringResource(Res.string.settings_tool_limits_repeats_hint),
                value = repeats,
                step = 1,
                range = AppSettings.MIN_REPEATED_TOOL_CALLS..AppSettings.MAX_REPEATED_TOOL_CALLS_LIMIT,
                onChange = { next ->
                    repeats = next
                    settings.setMaxRepeatedToolCalls(next)
                },
            )

            LimitsStepper(
                label = stringResource(Res.string.settings_tool_limits_parallel),
                hint = stringResource(Res.string.settings_tool_limits_parallel_hint),
                value = parallel,
                step = 1,
                range = AppSettings.MIN_TOOL_PARALLEL_LIMIT..AppSettings.MAX_TOOL_PARALLEL_LIMIT,
                onChange = { next ->
                    parallel = next
                    settings.setToolParallelLimit(next)
                },
            )

            TextButton(
                onClick = {
                    iterations = AppSettings.DEFAULT_MAX_TOOL_ITERATIONS
                    repeats = AppSettings.DEFAULT_MAX_REPEATED_TOOL_CALLS
                    parallel = AppSettings.DEFAULT_TOOL_PARALLEL_LIMIT
                    settings.setMaxToolIterations(AppSettings.DEFAULT_MAX_TOOL_ITERATIONS)
                    settings.setMaxRepeatedToolCalls(AppSettings.DEFAULT_MAX_REPEATED_TOOL_CALLS)
                    settings.setToolParallelLimit(AppSettings.DEFAULT_TOOL_PARALLEL_LIMIT)
                },
            ) {
                Text(text = stringResource(Res.string.settings_tool_limits_reset))
            }
        }
    }
}

@Composable
private fun LimitsStepper(
    label: String,
    hint: String,
    value: Int,
    step: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.width(8.dp))

        TextButton(
            onClick = { onChange((value - step).coerceIn(range.first, range.last)) },
            enabled = value > range.first,
        ) {
            Text(text = "−")
        }
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 36.dp),
        )
        TextButton(
            onClick = { onChange((value + step).coerceIn(range.first, range.last)) },
            enabled = value < range.last,
        ) {
            Text(text = "+")
        }
    }
}

/**
 * Where the confirmation gate sits. Default is [ToolApprovalPolicy.HIGH_RISK]: read-only tools stay
 * friction-free, while anything that sends a message, runs a shell command or drives the UI asks
 * first. "Never" is offered because a trusted personal setup should not be nagged, but it is a
 * deliberate opt-out rather than the default.
 */
@Composable
private fun ToolApprovalSection(settings: AppSettings) {
    var policy by remember { mutableStateOf(settings.getToolApprovalPolicy()) }
    var allowedTools by remember { mutableStateOf(settings.getAlwaysAllowedTools()) }
    val options = listOf(
        ToolApprovalPolicy.ALWAYS to Res.string.settings_tool_approval_always,
        ToolApprovalPolicy.HIGH_RISK to Res.string.settings_tool_approval_high_risk,
        ToolApprovalPolicy.NEVER to Res.string.settings_tool_approval_never,
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = kaiAdaptiveCardColors(),
        border = kaiAdaptiveCardBorder(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = stringResource(Res.string.settings_tool_approval_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(Res.string.settings_tool_approval_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = policy == value,
                        onClick = {
                            policy = value
                            settings.setToolApprovalPolicy(value)
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    ) {
                        Text(
                            text = stringResource(label),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    }
                }
            }

            if (allowedTools.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(
                            Res.string.settings_tool_approval_allowed,
                            allowedTools.size.toString(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            settings.clearAlwaysAllowedTools()
                            allowedTools = emptySet()
                        },
                    ) {
                        Text(text = stringResource(Res.string.settings_tool_approval_clear))
                    }
                }
            }
        }
    }
}

/**
 * How many automatic rounds a goal may take before Kai stops on its own.
 *
 * The budget is what keeps "keep going until it's done" from becoming an unbounded bill,
 * so it belongs next to the other loop limits rather than hidden in code.
 */
@Composable
private fun GoalRoundsSection(settings: AppSettings) {
    var maxRounds by remember { mutableIntStateOf(settings.getGoalMaxRounds()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = kaiAdaptiveCardColors(),
        border = kaiAdaptiveCardBorder(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            LimitsStepper(
                label = stringResource(Res.string.settings_goal_rounds_label),
                hint = stringResource(Res.string.settings_goal_rounds_hint),
                value = maxRounds,
                step = 1,
                range = GoalStore.MIN_MAX_ROUNDS..GoalStore.MAX_MAX_ROUNDS,
                onChange = { next ->
                    maxRounds = next
                    settings.setGoalMaxRounds(next)
                },
            )
        }
    }
}
