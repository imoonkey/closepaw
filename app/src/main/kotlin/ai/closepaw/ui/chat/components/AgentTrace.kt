package ai.closepaw.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import ai.closepaw.ui.chat.model.ActionCardData
import ai.closepaw.ui.chat.model.ActionState
import ai.closepaw.ui.chat.model.AgentMessageState
import ai.closepaw.ui.chat.model.ContentBlock
import ai.closepaw.ui.theme.closePaw

/**
 * AgentTrace — UXFB-4 ThoughtGroup layout.
 *
 * A turn emits one Thought + ≥1 Actions (TurnPlanningPhaseRunner.kt:217-238).
 * Each [ContentBlock.Thought] opens a group; subsequent [ContentBlock.Action]
 * (and any mid-stream [ContentBlock.Text]) belong to it until the next Thought.
 * Actions before the first Thought form a header-less preface group — still
 * gets the left rule.
 */

internal data class ThoughtGroup(
    val thought: ContentBlock.Thought?,
    val items: List<ContentBlock>,
)

internal fun groupTrace(blocks: List<ContentBlock>): List<ThoughtGroup> {
    val groups = mutableListOf<ThoughtGroup>()
    var thought: ContentBlock.Thought? = null
    var items = mutableListOf<ContentBlock>()
    fun flush() {
        if (thought != null || items.isNotEmpty()) {
            groups += ThoughtGroup(thought, items.toList())
        }
        thought = null
        items = mutableListOf()
    }
    for (block in blocks) {
        when (block) {
            is ContentBlock.Thought -> {
                flush()
                thought = block
            }
            is ContentBlock.Action, is ContentBlock.Text -> items += block
            is ContentBlock.FinalText -> Unit
        }
    }
    flush()
    return groups
}

@Composable
internal fun ExpandedTrace(blocks: List<ContentBlock>, state: AgentMessageState) {
    val spacing = MaterialTheme.closePaw.spacing
    val groups = groupTrace(blocks)
    val isStreaming = state == AgentMessageState.Streaming
    val lastTextIndex = blocks.indexOfLast { it is ContentBlock.Text }
    Column(
        verticalArrangement = Arrangement.spacedBy(spacing.md),
        modifier = Modifier.fillMaxWidth(),
    ) {
        groups.forEach { group ->
            ThoughtGroupView(
                group = group,
                isStreaming = isStreaming,
                streamingTextBlockIndex = lastTextIndex,
                allBlocks = blocks,
            )
        }
    }
}

@Composable
private fun ThoughtGroupView(
    group: ThoughtGroup,
    isStreaming: Boolean,
    streamingTextBlockIndex: Int,
    allBlocks: List<ContentBlock>,
) {
    val spacing = MaterialTheme.closePaw.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Spacer(Modifier.width(spacing.md))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            group.thought?.let { ThoughtHeader(it.text) }
            group.items.forEach { item ->
                when (item) {
                    is ContentBlock.Action -> ActionRow(
                        data = item.data,
                        modifier = Modifier.padding(start = spacing.lg),
                    )
                    is ContentBlock.Text -> {
                        val streamingTail =
                            isStreaming && allBlocks.indexOf(item) == streamingTextBlockIndex
                        if (streamingTail) {
                            StreamingText(
                                text = item.text,
                                isStreaming = true,
                                textColor = MaterialTheme.colorScheme.onSurface,
                            )
                        } else if (item.text.isNotEmpty()) {
                            SelectionContainer(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = item.text,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun ThoughtHeader(text: String) {
    SelectionContainer(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * ActionRow — inline trace row for an action. UXFB-4: monoSmall + onSurfaceVariant
 * inside a ThoughtGroup; status glyph stays right-aligned. Caller supplies the
 * group's start indent via [modifier].
 */
@Composable
internal fun ActionRow(
    data: ActionCardData,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.closePaw.spacing
    val mono = MaterialTheme.closePaw.monoSmall
    val statusGlyph = when (data.state) {
        ActionState.Proposed -> "⏳"
        ActionState.Executing -> "⏳"
        ActionState.Success -> "✓"
        ActionState.Failed -> "✕"
        ActionState.Skipped -> "⊘"
    }
    val statusDescription = when (data.state) {
        ActionState.Proposed -> "Proposed"
        ActionState.Executing -> "Executing"
        ActionState.Success -> "Success"
        ActionState.Failed -> "Failed"
        ActionState.Skipped -> "Skipped"
    }
    val statusColor = when (data.state) {
        ActionState.Success -> MaterialTheme.colorScheme.secondary
        ActionState.Failed -> MaterialTheme.colorScheme.error
        ActionState.Skipped -> MaterialTheme.closePaw.inkFaint
        ActionState.Proposed, ActionState.Executing -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "→",
                style = mono,
                color = MaterialTheme.closePaw.inkFaint,
            )
            Spacer(Modifier.width(spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = formatToolCall(data),
                    style = mono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val subtitle = data.resultSummary
                    ?: data.description.takeIf { it.isNotEmpty() && data.state != ActionState.Success }
                if (!subtitle.isNullOrBlank() && subtitle != data.description) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(spacing.sm))
            Text(
                text = statusGlyph,
                style = mono,
                color = statusColor,
                modifier = Modifier.semantics { stateDescription = statusDescription },
            )
        }
        val expanded = data.expandedContent
        if (!expanded.isNullOrBlank()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = spacing.xs),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = expanded,
                    style = MaterialTheme.closePaw.monoSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(spacing.sm),
                )
            }
        }
    }
}

private fun formatToolCall(data: ActionCardData): String {
    val args = data.description.takeIf { it.isNotBlank() } ?: ""
    return "${data.toolName}($args)"
}
