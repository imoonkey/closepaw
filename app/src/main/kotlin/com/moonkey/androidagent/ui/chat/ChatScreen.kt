package com.moonkey.androidagent.ui.chat

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moonkey.androidagent.ui.chat.components.ChatHeader
import com.moonkey.androidagent.ui.chat.components.EmptyState
import com.moonkey.androidagent.ui.chat.components.InputDock
import com.moonkey.androidagent.ui.chat.components.MessageBubble
import com.moonkey.androidagent.ui.chat.components.TaskBanner
import com.moonkey.androidagent.ui.chat.model.ChatMessage

/**
 * ChatScreen - Main chat interface composable.
 * 
 * Orchestrates all chat components into a cohesive conversation experience.
 */
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenSettings: () -> Unit,
    onOpenSessionHistory: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val taskBannerState by viewModel.taskBannerState.collectAsStateWithLifecycle()
    val messages = viewModel.messages
    
    // Track swipe gesture for opening settings
    var swipeOffset by remember { mutableFloatStateOf(0f) }
    
    Scaffold(
        modifier = modifier.pointerInput(Unit) {
            detectVerticalDragGestures(
                onDragEnd = {
                    // Trigger settings if swiped up more than 100px from bottom
                    if (swipeOffset < -100f) {
                        onOpenSettings()
                    }
                    swipeOffset = 0f
                },
                onVerticalDrag = { _, dragAmount ->
                    swipeOffset += dragAmount
                }
            )
        },
        topBar = {
            ChatHeader(
                onSettingsLongPress = onOpenSettings,
                onHistoryClick = onOpenSessionHistory
            )
        },
        bottomBar = {
            InputDock(
                state = uiState.inputState,
                onSend = viewModel::sendMessage,
                onStop = viewModel::stopTask
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Task Banner
            TaskBanner(state = taskBannerState)
            
            // Content area - use Box with conditional content
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (messages.isEmpty() && uiState.showEmptyState) {
                    // Empty state
                    EmptyState(
                        onSuggestionClick = viewModel::sendMessage,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (messages.isNotEmpty()) {
                    // Message list
                    MessageList(
                        messages = messages,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

/**
 * MessageList - Scrollable list of chat messages.
 */
@Composable
private fun MessageList(
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    
    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }
    
    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(
            items = messages,
            key = { it.id }
        ) { message ->
            MessageBubble(
                message = message,
                modifier = Modifier
                    .fillMaxWidth()
                    .animateItem()
            )
        }
    }
}
