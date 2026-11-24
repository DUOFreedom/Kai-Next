package com.kainext.duo.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kainext.duo.data.DataRepository
import com.kainext.duo.data.ThinkingEffort
import com.kainext.duo.formatContextWindow
import com.kainext.duo.formatReleaseDate
import com.kainext.duo.ui.KaiOutlinedTextField
import com.kainext.duo.ui.components.KaiSearchField
import com.kainext.duo.ui.components.VerticalScrollbarForGrid
import com.kainext.duo.ui.handCursor
import kai.composeapp.generated.resources.Res
import kai.composeapp.generated.resources.ic_arrow_drop_down
import kai.composeapp.generated.resources.model_capabilities_title
import kai.composeapp.generated.resources.model_capability_arena_score
import kai.composeapp.generated.resources.model_capability_audio
import kai.composeapp.generated.resources.model_capability_context_window
import kai.composeapp.generated.resources.model_capability_function_calling
import kai.composeapp.generated.resources.model_capability_max_output
import kai.composeapp.generated.resources.model_capability_no
import kai.composeapp.generated.resources.model_capability_parameter_count
import kai.composeapp.generated.resources.model_capability_release_date
import kai.composeapp.generated.resources.model_capability_thinking
import kai.composeapp.generated.resources.model_capability_unknown
import kai.composeapp.generated.resources.model_capability_vision
import kai.composeapp.generated.resources.model_capability_yes
import kai.composeapp.generated.resources.model_details_title
import kai.composeapp.generated.resources.model_filter_free
import kai.composeapp.generated.resources.model_free_badge
import kai.composeapp.generated.resources.model_free_empty
import kai.composeapp.generated.resources.model_override_auto
import kai.composeapp.generated.resources.model_override_reset
import kai.composeapp.generated.resources.model_override_thinking_effort
import kai.composeapp.generated.resources.model_overrides_description
import kai.composeapp.generated.resources.model_overrides_title
import kai.composeapp.generated.resources.model_sort_context
import kai.composeapp.generated.resources.model_sort_date
import kai.composeapp.generated.resources.model_sort_score
import kai.composeapp.generated.resources.settings_model_label
import kai.composeapp.generated.resources.settings_model_search
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelSelection(
    currentSelectedModel: SettingsModel?,
    models: ImmutableList<SettingsModel>,
    onClick: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var detailsModel by remember { mutableStateOf<SettingsModel?>(null) }
    var detailsExpanded by remember { mutableStateOf(false) }

    // Provider list only — synthetic manual entries stay out of the picker grid.
    val pickerModels = remember(models) { models.filter { !it.isManualEntry } }
    if (pickerModels.isNotEmpty()) {
        Box(
            modifier = Modifier.fillMaxWidth(),
        ) {
            KaiOutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = currentSelectedModel?.let { it.displayName ?: it.id } ?: "",
                onValueChange = {},
                readOnly = true,
                label = {
                    Text(
                        stringResource(Res.string.settings_model_label),
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                },
                trailingIcon = {
                    Icon(
                        modifier = Modifier.handCursor(),
                        imageVector = vectorResource(Res.drawable.ic_arrow_drop_down),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                },
            )
            // Transparent overlay to capture clicks reliably on all platforms
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .handCursor()
                    .clickable { expanded = true },
            )
        }
        if (expanded) {
            ModalBottomSheet(
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                onDismissRequest = {
                    expanded = false
                },
            ) {
                var searchQuery by remember { mutableStateOf("") }
                val hasFreeModels = remember(pickerModels) { pickerModels.any { it.isFreeTier } }
                var freeFilterOnly by remember { mutableStateOf(false) }
                // Reset free filter when the service has no free models.
                LaunchedEffect(hasFreeModels) {
                    if (!hasFreeModels) freeFilterOnly = false
                }
                val filteredModels = remember(pickerModels, searchQuery, freeFilterOnly) {
                    pickerModels.filter { model ->
                        val matchesFree = !freeFilterOnly || model.isFreeTier
                        val matchesSearch = searchQuery.isBlank() ||
                            model.id.contains(searchQuery, ignoreCase = true) ||
                            model.subtitle.contains(searchQuery, ignoreCase = true) ||
                            model.displayName?.contains(searchQuery, ignoreCase = true) == true
                        matchesFree && matchesSearch
                    }
                }
                if (pickerModels.size > 6) {
                    KaiSearchField(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        placeholder = stringResource(Res.string.settings_model_search),
                    )
                }
                var sortOption by remember { mutableStateOf(ModelSortOption.Score) }
                val sortedModels = remember(filteredModels, sortOption, searchQuery) {
                    val base = filteredModels.sortedWith(sortOption.comparator)
                    if (searchQuery.isBlank()) {
                        base
                    } else {
                        base.sortedBy { model ->
                            val name = model.displayName ?: model.id
                            when {
                                name.contains(searchQuery, ignoreCase = true) -> 0
                                model.subtitle.contains(searchQuery, ignoreCase = true) -> 1
                                else -> 2
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ModelSortOption.entries.forEach { option ->
                        FilterChip(
                            selected = sortOption == option,
                            onClick = { sortOption = option },
                            label = { Text(stringResource(option.labelRes)) },
                            modifier = Modifier.handCursor(),
                        )
                    }
                    if (hasFreeModels) {
                        FilterChip(
                            selected = freeFilterOnly,
                            onClick = { freeFilterOnly = !freeFilterOnly },
                            label = { Text(stringResource(Res.string.model_filter_free)) },
                            modifier = Modifier.handCursor(),
                        )
                    }
                }
                val gridState = rememberLazyGridState()
                LaunchedEffect(sortOption, freeFilterOnly) {
                    gridState.requestScrollToItem(0)
                }
                if (sortedModels.isEmpty() && freeFilterOnly) {
                    Text(
                        text = stringResource(Res.string.model_free_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                } else {
                    Box {
                        LazyVerticalGrid(
                            GridCells.Adaptive(300.dp),
                            state = gridState,
                            contentPadding = PaddingValues(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(sortedModels, key = { it.id }) { model ->
                                ModelCard(
                                    model = model,
                                    isSelected = currentSelectedModel?.id == model.id,
                                    onClick = {
                                        onClick(model.id)
                                        expanded = false
                                    },
                                    onDetailsClick = {
                                        detailsModel = model
                                        detailsExpanded = true
                                    },
                                )
                            }
                        }
                        VerticalScrollbarForGrid(
                            gridState = gridState,
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                        )
                    }
                }
            }
        }
        // Model details bottom sheet
        detailsModel?.let { model ->
            ModalBottomSheet(
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                onDismissRequest = {
                    detailsExpanded = false
                    detailsModel = null
                },
            ) {
                ModelDetailsSheet(
                    model = model,
                    onDismiss = {
                        detailsExpanded = false
                        detailsModel = null
                    },
                )
            }
        }
    }
}

private enum class ModelSortOption(
    val labelRes: StringResource,
    val comparator: Comparator<SettingsModel>,
) {
    Date(Res.string.model_sort_date, compareByDescending<SettingsModel> { it.releaseDate }.thenBy { it.id }),
    Score(Res.string.model_sort_score, compareByDescending<SettingsModel> { it.arenaScore }.thenBy { it.id }),
    Ctx(Res.string.model_sort_context, compareByDescending<SettingsModel> { it.contextWindow }.thenBy { it.id }),
}

@Composable
private fun ModelCard(
    model: SettingsModel,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDetailsClick: () -> Unit,
) {
    val displayName = model.displayName?.takeIf { it.isNotBlank() && it != model.id }
    val title = displayName ?: model.id
    val secondary = if (displayName == null && model.subtitle.isNotBlank()) model.subtitle else null
    val contextText = model.contextWindow?.let { formatContextWindow(it) }
    val releaseText = model.releaseDate?.let { formatReleaseDate(it) }
    val detailText = listOfNotNull(releaseText, model.parameterCount, contextText)
        .joinToString("  ·  ").ifEmpty { null }

    val primaryColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val secondaryColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .handCursor()
            .clip(CardDefaults.shape),
        shape = CardDefaults.shape,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = primaryColor,
                    modifier = Modifier.weight(1f),
                )
                if (model.isFreeTier) {
                    Spacer(Modifier.width(8.dp))
                    FreeTierBadge(isSelected = isSelected)
                }
                model.arenaScore?.let { score ->
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "$score",
                        style = MaterialTheme.typography.labelSmall,
                        color = arenaScoreColor(score),
                    )
                }
                // Details button
                IconButton(
                    onClick = onDetailsClick,
                    modifier = Modifier.handCursor(),
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = stringResource(Res.string.model_details_title),
                        tint = secondaryColor,
                    )
                }
            }
            secondary?.let {
                Text(
                    text = it,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = secondaryColor,
                )
            }
            detailText?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = secondaryColor,
                )
            }
        }
    }
}

private fun arenaScoreColor(score: Int): Color = when {
    score >= 1400 -> Color(0xFF2E7D32)
    score >= 1350 -> Color(0xFF558B2F)
    score >= 1300 -> Color(0xFF9E9D24)
    score >= 1250 -> Color(0xFFF9A825)
    else -> Color(0xFFEF6C00)
}

@Composable
private fun FreeTierBadge(isSelected: Boolean) {
    val background = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        Color(0xFF1B5E20)
    }
    val content = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        Color.White
    }
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = background,
    ) {
        Text(
            text = stringResource(Res.string.model_free_badge),
            style = MaterialTheme.typography.labelMedium,
            color = content,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelDetailsSheet(
    model: SettingsModel,
    onDismiss: () -> Unit,
) {
    val repository = koinInject<DataRepository>()
    val displayName = model.displayName?.takeIf { it.isNotBlank() && it != model.id }
    val title = displayName ?: model.id
    val releaseText = model.releaseDate?.let { formatReleaseDate(it) }
    val catalogContextText = model.contextWindow?.let { formatContextWindow(it) }
        ?: stringResource(Res.string.model_capability_unknown)
    val catalogMaxOutputText = model.maxOutputTokens?.let { formatContextWindow(it) }
        ?: stringResource(Res.string.model_capability_unknown)

    // Overrides are re-read per model id, so switching models never carries a stale value over.
    // Empty means "no override" — the request path then falls back to the catalog default.
    var contextInput by remember(model.id) {
        mutableStateOf(repository.getModelContextTokens(model.id).takeIf { it > 0 }?.toString().orEmpty())
    }
    var maxOutputInput by remember(model.id) {
        mutableStateOf(repository.getModelMaxOutputTokens(model.id).takeIf { it > 0 }?.toString().orEmpty())
    }
    var effort by remember(model.id) { mutableStateOf(repository.getThinkingEffort(model.id)) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 620.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        // Title row with close button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.model_details_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.handCursor(),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        // Model ID
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = model.id,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))

        // ---- Editable overrides ----
        Text(
            text = stringResource(Res.string.model_overrides_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(Res.string.model_overrides_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        OverrideField(
            label = stringResource(Res.string.model_capability_context_window),
            value = contextInput,
            autoValue = catalogContextText,
            onValueChange = { raw ->
                val cleaned = raw.filter { it.isDigit() }.take(9)
                contextInput = cleaned
                repository.setModelContextTokens(model.id, cleaned.toIntOrNull() ?: 0)
            },
            onReset = {
                contextInput = ""
                repository.setModelContextTokens(model.id, 0)
            },
        )
        Spacer(Modifier.height(12.dp))
        OverrideField(
            label = stringResource(Res.string.model_capability_max_output),
            value = maxOutputInput,
            autoValue = catalogMaxOutputText,
            onValueChange = { raw ->
                val cleaned = raw.filter { it.isDigit() }.take(6)
                maxOutputInput = cleaned
                repository.setModelMaxOutputTokens(model.id, cleaned.toIntOrNull() ?: 0)
            },
            onReset = {
                maxOutputInput = ""
                repository.setModelMaxOutputTokens(model.id, 0)
            },
        )
        Spacer(Modifier.height(16.dp))

        Text(
            text = stringResource(Res.string.model_override_thinking_effort),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ThinkingEffort.entries.forEach { option ->
                FilterChip(
                    selected = effort == option,
                    onClick = {
                        effort = option
                        repository.setThinkingEffort(model.id, option)
                    },
                    label = {
                        Text(
                            text = option.value.replaceFirstChar { it.uppercaseChar() },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    modifier = Modifier.handCursor(),
                )
            }
        }
        Spacer(Modifier.height(20.dp))

        // ---- Capabilities (read-only) ----
        Text(
            text = stringResource(Res.string.model_capabilities_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        CapabilityRow(
            label = stringResource(Res.string.model_capability_vision),
            value = if (model.supportsVision) stringResource(Res.string.model_capability_yes) else stringResource(Res.string.model_capability_no),
            isSupported = model.supportsVision,
        )
        CapabilityRow(
            label = stringResource(Res.string.model_capability_function_calling),
            value = if (model.supportsFunctionCalling) stringResource(Res.string.model_capability_yes) else stringResource(Res.string.model_capability_no),
            isSupported = model.supportsFunctionCalling,
        )
        CapabilityRow(
            label = stringResource(Res.string.model_capability_thinking),
            value = if (model.supportsThinking) stringResource(Res.string.model_capability_yes) else stringResource(Res.string.model_capability_no),
            isSupported = model.supportsThinking,
        )
        CapabilityRow(
            label = stringResource(Res.string.model_capability_audio),
            value = if (model.supportsAudio) stringResource(Res.string.model_capability_yes) else stringResource(Res.string.model_capability_no),
            isSupported = model.supportsAudio,
        )
        Spacer(Modifier.height(16.dp))

        // Metadata section
        Text(
            text = "Metadata",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        MetadataRow(
            label = stringResource(Res.string.model_capability_context_window),
            value = catalogContextText,
        )
        MetadataRow(
            label = stringResource(Res.string.model_capability_max_output),
            value = catalogMaxOutputText,
        )
        releaseText?.let {
            MetadataRow(
                label = stringResource(Res.string.model_capability_release_date),
                value = it,
            )
        }
        model.parameterCount?.let {
            MetadataRow(
                label = stringResource(Res.string.model_capability_parameter_count),
                value = it,
            )
        }
        model.arenaScore?.let { score ->
            MetadataRow(
                label = stringResource(Res.string.model_capability_arena_score),
                value = score.toString(),
            )
        }
        model.isFreeTier?.let { isFree ->
            if (isFree) {
                MetadataRow(
                    label = "Free tier",
                    value = stringResource(Res.string.model_capability_yes),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * One numeric override with the model's own default spelled out as the placeholder, plus a reset
 * action. Typing writes through immediately, so the value the next request uses is always the one
 * on screen — there is no separate save step to forget.
 */
@Composable
private fun OverrideField(
    label: String,
    value: String,
    autoValue: String,
    onValueChange: (String) -> Unit,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
    ) {
        KaiOutlinedTextField(
            modifier = Modifier.weight(1f),
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            label = { Text(label) },
            placeholder = {
                Text(
                    text = stringResource(Res.string.model_override_auto) + " · " + autoValue,
                    style = MaterialTheme.typography.bodySmall,
                )
            },
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(Res.string.model_override_reset),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .handCursor()
                .clickable { onReset() }
                .padding(horizontal = 8.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun CapabilityRow(
    label: String,
    value: String,
    isSupported: Boolean,
) {
    val valueColor = if (isSupported) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
        )
    }
    Divider(
        color = MaterialTheme.colorScheme.outlineVariant,
        thickness = 1.dp,
    )
}

@Composable
private fun MetadataRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
    Divider(
        color = MaterialTheme.colorScheme.outlineVariant,
        thickness = 1.dp,
    )
}
