package com.moonkey.androidagent.ui.chat

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
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moonkey.androidagent.history.model.SessionInfo
import com.moonkey.androidagent.ui.chat.components.ChatHeader
import com.moonkey.androidagent.ui.chat.components.EmptyState
import com.moonkey.androidagent.ui.chat.components.InputDock
import com.moonkey.androidagent.ui.chat.components.MessageBubble
import com.moonkey.androidagent.ui.chat.components.TaskBanner
import com.moonkey.androidagent.ui.chat.model.ChatMessage
import com.moonkey.androidagent.ui.navigation.NavigationDrawerContent
import kotlinx.coroutines.launch

/**
 * ChatScreen - Main chat interface composable.
 * 
 * Orchestrates all chat components into a cohesive conversation experience.
 * Includes navigation drawer for session history and settings access.
 */
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    sessions: List<SessionInfo>,
    currentModel: String,
    appVersion: String,
    onOpenSettings: () -> Unit,
    onSessionSelect: (SessionInfo) -> Unit,
    onNewSession: () -> Unit,
    onDeleteSession: (SessionInfo) -> Unit,
    onLoadSessions: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val taskBannerState by viewModel.taskBannerState.collectAsStateWithLifecycle()
    val messages = viewModel.messages
    
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    
    // Load sessions when drawer opens
    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) {
            onLoadSessions()
        }
    }
    
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            NavigationDrawerContent(
                sessions = sessions,
                currentModel = currentModel,
                appVersion = appVersion,
                onSessionSelect = { session ->
                    scope.launch { drawerState.close() }
                    onSessionSelect(session)
                },
                onNewSession = {
                    scope.launch { drawerState.close() }
                    onNewSession()
                },
                onDeleteSession = onDeleteSession,
                onSettingsClick = {
                    scope.launch { drawerState.close() }
                    onOpenSettings()
                },
                onClose = {
                    scope.launch { drawerState.close() }
                }
            )
        },
        gesturesEnabled = true
    ) {
        Scaffold(
            modifier = modifier,
            topBar = {
                ChatHeader(
                    onMenuClick = {
                        scope.launch { drawerState.open() }
                    },
                    onNewChatClick = onNewSession,
                    showNewChatButton = messages.isNotEmpty()
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
