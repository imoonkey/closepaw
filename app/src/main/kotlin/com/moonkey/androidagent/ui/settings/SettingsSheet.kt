package com.moonkey.androidagent.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.moonkey.androidagent.BuildConfig
import com.moonkey.androidagent.protocol.LLMBackendType
import com.moonkey.androidagent.ui.theme.ChatSuccess
import com.moonkey.androidagent.ui.theme.ChatWarning

/**
 * Available cloud LLM models for selection.
 */
private val AVAILABLE_CLOUD_MODELS = listOf(
    "gpt-4o" to "GPT-4o (Recommended)",
    "gpt-4o-mini" to "GPT-4o Mini (Faster)",
    "gpt-4-turbo" to "GPT-4 Turbo"
)

/**
 * Available local LLM models for selection.
 * 
 * Note: Only models available in Leap SDK's downloadable model library are listed.
 * Check LeapDownloadableModel.resolve() for available models.
 */
private val AVAILABLE_LOCAL_MODELS = listOf(
    LocalModelOption(
        id = "lfm2-350m",
        displayName = "LFM 350M",
        modelSlug = "lfm2-350m",
        quantizationSlug = "lfm2-350m-20250710-8da4w",
        description = "On-device inference, ~350M parameters"
    )
    // Note: Larger models (1.2B+) require manual download and local file loading,
    // not yet supported via the downloadable model API.
)

/**
 * Local model option data.
 */
data class LocalModelOption(
    val id: String,
    val displayName: String,
    val modelSlug: String,
    val quantizationSlug: String,
    val description: String
)

/**
 * Model loading state for UI display.
 */
sealed interface ModelLoadingStatus {
    data object Idle : ModelLoadingStatus
    data class Downloading(val progress: Float) : ModelLoadingStatus
    data object Loading : ModelLoadingStatus
    data object Ready : ModelLoadingStatus
    data class Error(val message: String) : ModelLoadingStatus
}

/**
 * Available max turns options.
 */
private val MAX_TURNS_OPTIONS = listOf(10, 20, 50)

/**
 * SettingsSheet - Bottom sheet for configuration.
 * 
 * Sections:
 * - LLM Backend (Cloud/Local)
 * - Model Selection (cloud or local based on backend)
 * - Max Turns
 * - API Key (cloud only)
 * - Accessibility Service status
 * - Overlay Permission status
 * - About & Debug
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    // Backend selection
    llmBackend: LLMBackendType,
    onBackendChange: (LLMBackendType) -> Unit,
    // Cloud model selection
    selectedModel: String,
    onModelChange: (String) -> Unit,
    // Local model selection
    selectedLocalModel: String,
    onLocalModelChange: (LocalModelOption) -> Unit,
    // Local model loading status
    modelLoadingStatus: ModelLoadingStatus,
    // API key (cloud only)
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    // Other settings
    maxTurns: Int,
    onMaxTurnsChange: (Int) -> Unit,
    debugMode: Boolean,
    onDebugModeChange: (Boolean) -> Unit,
    isAccessibilityEnabled: Boolean,
    isOverlayEnabled: Boolean,
    onAccessibilityClick: () -> Unit,
    onOverlayClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var apiKeyVisible by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val isCloudBackend = llmBackend == LLMBackendType.OPENAI
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()  // Add padding for status bar / Dynamic Island
            .displayCutoutPadding()  // Handle camera cutout
            .navigationBarsPadding()
    ) {
        // Header with close button (like ChatGPT/Manus)
        SettingsHeader(onClose = onDismiss)
        
        // Scrollable content
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp)
        ) {
        
        // LLM Backend Section
        SettingsSection(title = "LLM Backend") {
            BackendSelector(
                selectedBackend = llmBackend,
                onBackendChange = onBackendChange
            )
        }
        
        Spacer(modifier = Modifier.height(20.dp))
        
        // Model Section - Cloud or Local based on backend
        AnimatedVisibility(visible = isCloudBackend) {
            Column {
                SettingsSection(title = "Cloud Model") {
                    CloudModelDropdown(
                        selectedModel = selectedModel,
                        onModelChange = onModelChange
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
        
        AnimatedVisibility(visible = !isCloudBackend) {
            Column {
                SettingsSection(title = "Local Model") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        LocalModelDropdown(
                            selectedModelId = selectedLocalModel,
                            onModelChange = onLocalModelChange
                        )
                        
                        // Model loading status
                        ModelLoadingStatusIndicator(status = modelLoadingStatus)
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
        
        // Max Turns Section
        SettingsSection(title = "Max Turns") {
            MaxTurnsDropdown(
                maxTurns = maxTurns,
                onMaxTurnsChange = onMaxTurnsChange
            )
        }
        
        Spacer(modifier = Modifier.height(20.dp))
        
        // API Key Section - Only for Cloud backend
        AnimatedVisibility(visible = isCloudBackend) {
            Column {
                SettingsSection(title = "API Key") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = onApiKeyChange,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { 
                                Text(
                                    "sk-...",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                ) 
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Key,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            trailingIcon = {
                                IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                                    Icon(
                                        imageVector = if (apiKeyVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                        contentDescription = if (apiKeyVisible) "Hide" else "Show",
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline
                            )
                        )
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
        
        // Permissions Section
        SettingsSection(title = "Permissions") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Accessibility Service
                SettingsRow(
                    icon = Icons.Outlined.Settings,
                    title = "Accessibility Service",
                    isEnabled = isAccessibilityEnabled,
                    onClick = onAccessibilityClick
                )
                
                // Overlay Permission
                SettingsRow(
                    icon = Icons.Outlined.Layers,
                    title = "Overlay Permission",
                    isEnabled = isOverlayEnabled,
                    onClick = onOverlayClick
                )
            }
        }
        
        Spacer(modifier = Modifier.height(20.dp))
        
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        
        Spacer(modifier = Modifier.height(20.dp))
        
        // About & Debug Section
        SettingsSection(title = "About & Debug") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Version info
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Android Agent",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                
                // Debug mode toggle
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.BugReport,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Debug Mode",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "Enable verbose logging",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = debugMode,
                            onCheckedChange = onDebugModeChange,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }
                }
            }
        }
        
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/**
 * Settings header with title and close button.
 */
@Composable
private fun SettingsHeader(
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 8.dp, top = 8.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Close",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Backend selector (Cloud vs Local).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackendSelector(
    selectedBackend: LLMBackendType,
    onBackendChange: (LLMBackendType) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val backends = listOf(
        LLMBackendType.OPENAI to "Cloud (OpenAI)",
        LLMBackendType.LOCAL to "Local (On-Device)"
    )
    val selectedDisplayName = backends.find { it.first == selectedBackend }?.second ?: "Cloud (OpenAI)"
    
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selectedDisplayName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Backend") },
            leadingIcon = {
                Icon(
                    imageVector = if (selectedBackend == LLMBackendType.OPENAI) 
                        Icons.Outlined.Cloud else Icons.Outlined.PhoneAndroid,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            )
        )
        
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            backends.forEach { (backend, displayName) ->
                DropdownMenuItem(
                    text = { Text(displayName) },
                    onClick = {
                        onBackendChange(backend)
                        expanded = false
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = if (backend == LLMBackendType.OPENAI) 
                                Icons.Outlined.Cloud else Icons.Outlined.PhoneAndroid,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = if (backend == selectedBackend) 
                                MaterialTheme.colorScheme.primary 
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
            }
        }
    }
}

/**
 * Cloud model selection dropdown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CloudModelDropdown(
    selectedModel: String,
    onModelChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedDisplayName = AVAILABLE_CLOUD_MODELS.find { it.first == selectedModel }?.second ?: selectedModel
    
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selectedDisplayName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Model") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Psychology,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            )
        )
        
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            AVAILABLE_CLOUD_MODELS.forEach { (modelId, displayName) ->
                DropdownMenuItem(
                    text = { Text(displayName) },
                    onClick = {
                        onModelChange(modelId)
                        expanded = false
                    },
                    leadingIcon = if (modelId == selectedModel) {
                        {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }
                    } else null
                )
            }
        }
    }
}

/**
 * Local model selection dropdown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalModelDropdown(
    selectedModelId: String,
    onModelChange: (LocalModelOption) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedModel = AVAILABLE_LOCAL_MODELS.find { it.id == selectedModelId } 
        ?: AVAILABLE_LOCAL_MODELS.first()
    
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selectedModel.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Local Model") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Memory,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            )
        )
        
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            AVAILABLE_LOCAL_MODELS.forEach { model ->
                DropdownMenuItem(
                    text = { 
                        Column {
                            Text(model.displayName)
                            Text(
                                text = model.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = {
                        onModelChange(model)
                        expanded = false
                    },
                    leadingIcon = if (model.id == selectedModelId) {
                        {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }
                    } else null
                )
            }
        }
    }
}

/**
 * Model loading status indicator.
 */
@Composable
private fun ModelLoadingStatusIndicator(status: ModelLoadingStatus) {
    when (status) {
        is ModelLoadingStatus.Idle -> {
            // No indicator needed
        }
        is ModelLoadingStatus.Downloading -> {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Downloading model...",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "${(status.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    LinearProgressIndicator(
                        progress = { status.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        is ModelLoadingStatus.Loading -> {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "Loading model...",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
        is ModelLoadingStatus.Ready -> {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = ChatSuccess.copy(alpha = 0.1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(ChatSuccess)
                    )
                    Text(
                        text = "Model ready",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ChatSuccess
                    )
                }
            }
        }
        is ModelLoadingStatus.Error -> {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Error: ${status.message}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}

/**
 * Max turns selection dropdown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MaxTurnsDropdown(
    maxTurns: Int,
    onMaxTurnsChange: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = "$maxTurns turns",
            onValueChange = {},
            readOnly = true,
            label = { Text("Max Turns") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Repeat,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            )
        )
        
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            MAX_TURNS_OPTIONS.forEach { turns ->
                DropdownMenuItem(
                    text = { Text("$turns turns") },
                    onClick = {
                        onMaxTurnsChange(turns)
                        expanded = false
                    },
                    leadingIcon = if (turns == maxTurns) {
                        {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }
                    } else null
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,  // Muted instead of primary
            modifier = Modifier.padding(bottom = 12.dp)
        )
        content()
    }
}

@Composable
private fun SettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    isEnabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            
            // Status indicator
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isEnabled) ChatSuccess else ChatWarning)
                )
                Text(
                    text = if (isEnabled) "Enabled" else "Required",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isEnabled) ChatSuccess else ChatWarning
                )
            }
        }
    }
}
