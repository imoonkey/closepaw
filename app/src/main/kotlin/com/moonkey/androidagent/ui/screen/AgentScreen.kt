package com.moonkey.androidagent.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moonkey.androidagent.ui.theme.*
import com.moonkey.androidagent.util.StatusUtils

data class AgentUiState(
    val apiKey: String = "",
    val goal: String = "",
    val statusLines: List<String> = emptyList(),
    val isServiceEnabled: Boolean = false,
    val isRunning: Boolean = false
)

@Composable
fun AgentScreen(
    state: AgentUiState,
    onApiKeyChange: (String) -> Unit,
    onGoalChange: (String) -> Unit,
    onStartClick: () -> Unit,
    onAccessibilityClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var apiKeyVisible by remember { mutableStateOf(false) }
    var contentVisible by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        contentVisible = true
    }
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Spacer(modifier = Modifier.height(24.dp))
            
            // Header
            AnimatedVisibility(
                visible = contentVisible,
                enter = fadeIn(tween(500)) + slideInVertically(tween(500)) { -20 }
            ) {
                Header()
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Config Section
            AnimatedVisibility(
                visible = contentVisible,
                enter = fadeIn(tween(500, delayMillis = 100)) + slideInVertically(tween(500, delayMillis = 100)) { -15 }
            ) {
                ConfigSection(
                    apiKey = state.apiKey,
                    onApiKeyChange = onApiKeyChange,
                    apiKeyVisible = apiKeyVisible,
                    onToggleApiKeyVisibility = { apiKeyVisible = !apiKeyVisible },
                    goal = state.goal,
                    onGoalChange = onGoalChange
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Action Buttons
            AnimatedVisibility(
                visible = contentVisible,
                enter = fadeIn(tween(500, delayMillis = 200)) + slideInVertically(tween(500, delayMillis = 200)) { -10 }
            ) {
                ActionButtons(
                    isServiceEnabled = state.isServiceEnabled,
                    isRunning = state.isRunning,
                    onStartClick = onStartClick,
                    onAccessibilityClick = onAccessibilityClick
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Status Log
            AnimatedVisibility(
                visible = contentVisible,
                enter = fadeIn(tween(500, delayMillis = 300)) + slideInVertically(tween(500, delayMillis = 300)) { -5 },
                modifier = Modifier.weight(1f)
            ) {
                StatusLog(
                    statusLines = state.statusLines,
                    modifier = Modifier.fillMaxSize()
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Header() {
    Column {
        Text(
            text = "Android Agent",
            style = MaterialTheme.typography.headlineLarge.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.5).sp
            ),
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "AI-powered automation for your device",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted
        )
    }
}

@Composable
private fun ConfigSection(
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    apiKeyVisible: Boolean,
    onToggleApiKeyVisibility: () -> Unit,
    goal: String,
    onGoalChange: (String) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // API Key Field
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "API Key",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                placeholder = { 
                    Text(
                        "sk-...",
                        color = TextPlaceholder
                    ) 
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Key,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                },
                trailingIcon = {
                    IconButton(onClick = onToggleApiKeyVisibility) {
                        Icon(
                            imageVector = if (apiKeyVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (apiKeyVisible) "Hide" else "Show",
                            tint = TextMuted,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = elegantTextFieldColors(),
                shape = RoundedCornerShape(10.dp)
            )
        }
        
        // Goal Field
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "What would you like to do?",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            OutlinedTextField(
                value = goal,
                onValueChange = onGoalChange,
                placeholder = { 
                    Text(
                        "e.g., Open Settings and turn on Wi-Fi",
                        color = TextPlaceholder
                    ) 
                },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
                colors = elegantTextFieldColors(),
                shape = RoundedCornerShape(10.dp)
            )
        }
    }
}

@Composable
private fun ActionButtons(
    isServiceEnabled: Boolean,
    isRunning: Boolean,
    onStartClick: () -> Unit,
    onAccessibilityClick: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Start Button - Primary CTA
        Button(
            onClick = onStartClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            enabled = !isRunning,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Primary,
                contentColor = OnPrimary,
                disabledContainerColor = Disabled,
                disabledContentColor = DisabledText
            ),
            elevation = ButtonDefaults.buttonElevation(
                defaultElevation = 0.dp,
                pressedElevation = 0.dp
            )
        ) {
            if (isRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = OnPrimary,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Running...",
                    style = MaterialTheme.typography.labelLarge
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Start Agent",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
        
        // Accessibility Settings Button - Secondary
        OutlinedButton(
            onClick = onAccessibilityClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = TextSecondary
            ),
            border = androidx.compose.foundation.BorderStroke(1.dp, Border)
        ) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Accessibility Settings",
                style = MaterialTheme.typography.labelLarge
            )
            
            Spacer(modifier = Modifier.weight(1f))
            
            // Status indicator
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isServiceEnabled) StatusSuccess else StatusWarning)
                )
                Text(
                    text = if (isServiceEnabled) "Enabled" else "Required",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isServiceEnabled) StatusSuccess else StatusWarning
                )
            }
        }
    }
}

@Composable
private fun StatusLog(
    statusLines: List<String>,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        // Header
        Text(
            text = "Activity",
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // Log container
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(10.dp))
                .background(SurfaceVariant)
                .border(1.dp, Border, RoundedCornerShape(10.dp))
        ) {
            val scrollState = rememberScrollState()
            
            // Auto-scroll to bottom when new lines are added
            LaunchedEffect(statusLines.size) {
                scrollState.animateScrollTo(scrollState.maxValue)
            }
            
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (statusLines.isEmpty()) {
                    Text(
                        text = "Activity will appear here...",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                } else {
                    statusLines.forEachIndexed { index, line ->
                        StatusLine(line, isLatest = index == statusLines.lastIndex)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(text: String, isLatest: Boolean) {
    // Use shared StatusUtils for consistent status type detection
    val (bgColor, textColor, icon) = when (StatusUtils.getStatusType(text)) {
        StatusUtils.StatusType.SUCCESS -> Triple(StatusSuccessBg, StatusSuccess, "✓")
        StatusUtils.StatusType.ERROR -> Triple(StatusErrorBg, StatusError, "✗")
        StatusUtils.StatusType.WARNING -> Triple(StatusWarningBg, StatusWarning, "!")
        StatusUtils.StatusType.THINKING -> Triple(StatusInfoBg, StatusInfo, "◉")
        StatusUtils.StatusType.TOOL -> Triple(Color.Transparent, TextSecondary, "→")
        StatusUtils.StatusType.RUNNING -> Triple(StatusInfoBg, StatusInfo, "▶")
        StatusUtils.StatusType.NEUTRAL -> Triple(Color.Transparent, TextSecondary, "·")
    }
    
    // Clean up emoji from display text using shared utility
    val cleanText = StatusUtils.cleanStatusText(text)
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (bgColor != Color.Transparent) {
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(bgColor)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                } else {
                    Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                }
            ),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = icon,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace
            ),
            color = textColor
        )
        Text(
            text = cleanText,
            style = MaterialTheme.typography.bodySmall,
            color = if (bgColor != Color.Transparent) textColor else TextSecondary
        )
    }
}

@Composable
private fun elegantTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedBorderColor = BorderFocused,
    unfocusedBorderColor = Border,
    focusedLabelColor = TextPrimary,
    unfocusedLabelColor = TextMuted,
    cursorColor = Primary,
    focusedLeadingIconColor = TextSecondary,
    unfocusedLeadingIconColor = TextMuted,
    focusedPlaceholderColor = TextPlaceholder,
    unfocusedPlaceholderColor = TextPlaceholder,
    focusedContainerColor = Surface,
    unfocusedContainerColor = Surface
)
