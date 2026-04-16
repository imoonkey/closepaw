package com.moonkey.androidagent.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moonkey.androidagent.app.AgentService
import com.moonkey.androidagent.history.model.SessionInfo
import com.moonkey.androidagent.protocol.PlatformMode
import com.moonkey.androidagent.ui.capsule.NavAction
import com.moonkey.androidagent.ui.capsule.SmartCapsuleCompose
import com.moonkey.androidagent.ui.capsule.surface.smartCapsuleHostPadding
import com.moonkey.androidagent.ui.chat.components.ChatHeader
import com.moonkey.androidagent.ui.chat.components.EmptyState
import com.moonkey.androidagent.ui.chat.components.MessageBubble
import com.moonkey.androidagent.ui.chat.model.ChatMessage
import com.moonkey.androidagent.ui.navigation.NavigationDrawerContent
import com.moonkey.androidagent.ui.overlay.model.CapsuleContext
import com.moonkey.androidagent.ui.overlay.model.CapsuleMode
import kotlinx.coroutines.launch

/**
 * ChatScreen - Main chat interface composable.
 * 
 * Orchestrates all chat components into a cohesive conversation experience.
 * Includes navigation drawer for session history and settings access.
 * Uses SmartCapsuleCompose as bottomBar (replaces old InputDock).
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
    onOpenViewer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val messages = viewModel.messages

    // Collect capsule mode from CapsuleStateHolder (via AgentService singleton).
    // Fallback flows are stable (remembered) to avoid recomposition churn when service is null.
    val stateHolder = AgentService.instance?.capsuleStateHolder
    val fallbackMode = remember { kotlinx.coroutines.flow.MutableStateFlow<CapsuleMode>(CapsuleMode.Hidden) }
    val fallbackPlatform = remember { kotlinx.coroutines.flow.MutableStateFlow(PlatformMode.ACCESSIBILITY) }
    val fallbackStopPending = remember { kotlinx.coroutines.flow.MutableStateFlow(false) }
    val capsuleMode by (stateHolder?.mode ?: fallbackMode).collectAsStateWithLifecycle()
    val capsulePlatformMode by (stateHolder?.platformMode ?: fallbackPlatform).collectAsStateWithLifecycle()
    val isStopPending by (stateHolder?.isStopPending ?: fallbackStopPending).collectAsStateWithLifecycle()

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
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .smartCapsuleHostPadding()
                ) {
                    SmartCapsuleCompose(
                        mode = capsuleMode,
                        isStopPending = isStopPending,
                        platformMode = capsulePlatformMode,
                        context = CapsuleContext.MAIN_APP,
                        onSend = viewModel::sendMessage,
                        onSupplement = viewModel::sendSupplement,
                        onTakeover = viewModel::requestTakeover,
                        onResume = viewModel::requestResume,
                        onStop = {
                            if (stateHolder?.onStopRequested() != false) {
                                viewModel.stopTask()
                            }
                        },
                        onUserResponse = viewModel::sendUserResponse,
                        onApprovalResponse = { callId, decision, approvalScope, packageName ->
                            if (stateHolder?.onApprovalResolved(callId) != false) {
                                viewModel.sendApprovalResponse(callId, decision, approvalScope, packageName)
                            }
                        },
                        onDismissError = { viewModel.dismissError() },
                        onNavigate = { action ->
                            if (action == NavAction.OPEN_VIEWER) {
                                onOpenViewer()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        previousMode = stateHolder?.previousMode,
                    )
                }
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
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
 * MessageList - Scrollable list of chat messages with stick-to-bottom policy.
 *
 * Auto-scrolls only when the user is near the bottom of the list (following the
 * conversation). Shows a scroll-to-bottom FAB when user has scrolled up and new
 * content arrives below the fold.
 */
@Composable
private fun MessageList(
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // "Near bottom" = last visible item is within 2 items of the end
    val isNearBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = layoutInfo.totalItemsCount
            totalItems == 0 || lastVisible >= totalItems - 2
        }
    }

    // Derive a scroll key that changes on message count *and* last-message content.
    // This covers both new messages and streaming mutations to the last message.
    val scrollKey by remember {
        derivedStateOf {
            val last = messages.lastOrNull()
            val contentSignal = when (last) {
                is ChatMessage.Agent -> last.contentBlocks.size.toLong() + last.content.length
                is ChatMessage.User -> last.text.length.toLong()
                null -> 0L
            }
            messages.size.toLong() * 100_000 + contentSignal
        }
    }

    // Auto-scroll when scroll key changes, but only if user is near bottom
    LaunchedEffect(scrollKey) {
        if (messages.isNotEmpty() && isNearBottom) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Box(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
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

        // Scroll-to-bottom FAB when user has scrolled up
        AnimatedVisibility(
            visible = !isNearBottom && messages.isNotEmpty(),
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            SmallFloatingActionButton(
                onClick = {
                    scope.launch {
                        listState.animateScrollToItem(messages.size - 1)
                    }
                },
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = "Scroll to bottom"
                )
            }
        }
    }
}
