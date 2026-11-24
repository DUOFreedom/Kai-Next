package com.kainext.duo.ui.chat.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kainext.duo.data.TodoItem
import com.kainext.duo.data.TodoStatus
import com.kainext.duo.ui.handCursor
import kotlinx.collections.immutable.ImmutableList

/**
 * The checklist the model maintains through the `todo_write` tool.
 *
 * A long agentic run is otherwise opaque: the transcript only shows tool calls, so the user cannot
 * tell how much of the job is done. Showing the list above the composer keeps the plan in sight
 * without stealing space from the conversation.
 */
@Composable
internal fun TodoCard(
    todos: ImmutableList<TodoItem>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (todos.isEmpty()) return

    val completed = todos.count { it.status == TodoStatus.COMPLETED }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = "Tasks $completed/${todos.size}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(6.dp))
        todos.forEach { todo ->
            TodoRow(todo = todo, onToggle = { onToggle(todo.id) })
        }
    }
}

@Composable
private fun TodoRow(todo: TodoItem, onToggle: () -> Unit) {
    val isDone = todo.status == TodoStatus.COMPLETED
    val isActive = todo.status == TodoStatus.IN_PROGRESS
    val marker = when {
        isDone -> "✓"
        isActive -> "▶"
        else -> "○"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .handCursor()
            .clickable { onToggle() }
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A dedicated glyph renders the same on every platform, unlike a Material checkbox, and
        // stays readable at the small size this strip uses.
        Text(
            text = marker,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = when {
                isDone -> MaterialTheme.colorScheme.primary
                isActive -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = todo.text,
            style = MaterialTheme.typography.bodySmall,
            color = if (isDone) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            textDecoration = if (isDone) TextDecoration.LineThrough else null,
        )
    }
}
