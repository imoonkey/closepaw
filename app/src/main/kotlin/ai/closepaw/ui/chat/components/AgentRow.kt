package ai.closepaw.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import ai.closepaw.ui.chat.model.AgentMessageState
import ai.closepaw.ui.chat.model.ChatMessage
import ai.closepaw.ui.chat.model.ContentBlock
import ai.closepaw.ui.chat.model.RowState
import ai.closepaw.ui.theme.ClosePawMotion
import ai.closepaw.ui.theme.closePaw

/**
 * AgentRow — D2/D3 layout. Three vertical regions:
 *   1. Trace (collapsible) — Thought + Action + mid-stream Text blocks.
 *   2. CollapsePill (toggle) — only on Complete rows that have a trace.
 *   3. Final region (always visible) — the FinalText block, if present.
 *
 * Live/Waiting/Error rows render trace open with no pill (locked open). The row
 * root is no longer clickable; the pill owns the toggle and Role.Button. The
 * trace itself (ThoughtGroup layout) lives in [AgentTrace].
 */
@Composable
internal fun AgentRow(
    message: ChatMessage.Agent,
    onOpenApp: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.closePaw.spacing
    val traceBlocks = message.contentBlocks.filter { it !is ContentBlock.FinalText }
    val finalText = message.contentBlocks.filterIsInstance<ContentBlock.FinalText>().lastOrNull()
    val hasTrace = traceBlocks.isNotEmpty()

    val collapsible = message.rowState == RowState.Complete && hasTrace
    var userCollapsed by remember(message.id) { mutableStateOf<Boolean?>(null) }
    val collapsed = if (collapsible) (userCollapsed ?: true) else false
    val expanded = !collapsed

    val rowDescription = when (message.rowState) {
        RowState.Live -> "live"
        RowState.Waiting -> "waiting"
        RowState.Complete -> if (collapsed) "collapsed" else "expanded"
        RowState.Error -> "error"
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("qa-agent-bubble")
            .semantics { stateDescription = rowDescription },
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        if (message.state == AgentMessageState.Thinking && message.contentBlocks.isEmpty()) {
            ThinkingIndicator()
            return@Column
        }

        if (hasTrace) {
            val rowTween = tween<IntSize>(ClosePawMotion.RowExpand, easing = ClosePawMotion.EaseInOutSine)
            val fadeTween = tween<Float>(ClosePawMotion.RowExpand, easing = ClosePawMotion.EaseInOutSine)
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = rowTween) + fadeIn(animationSpec = fadeTween),
                exit = shrinkVertically(animationSpec = rowTween) + fadeOut(animationSpec = fadeTween),
            ) {
                ExpandedTrace(traceBlocks, message.state)
            }
            if (collapsible) {
                CollapsePill(
                    summary = outcomeFooter(message),
                    expanded = expanded,
                    onToggle = { userCollapsed = !collapsed },
                )
            }
        }

        if (finalText != null) {
            FinalAnswer(text = finalText.text)
        }

        if (message.rowState == RowState.Complete && message.handoff != null) {
            CompletionHandoffCtaRow(
                handoff = message.handoff,
                onOpenApp = onOpenApp,
            )
        }
    }
}

@Composable
private fun FinalAnswer(text: String) {
    SelectionContainer(modifier = Modifier.fillMaxWidth().testTag("qa-final-answer")) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
