package ai.closepaw.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import ai.closepaw.onboarding.ApiKeyAuthMethod
import ai.closepaw.onboarding.ApiKeyStepState
import ai.closepaw.onboarding.DemoStepState
import ai.closepaw.onboarding.OnboardingProvider
import ai.closepaw.onboarding.PermissionStepState
import ai.closepaw.onboarding.StepOutcome
import ai.closepaw.onboarding.StepOutcomes
import ai.closepaw.onboarding.WizardStep

// ── Permission Step ──

@Composable
fun PermissionStepContent(
    step: WizardStep,
    state: PermissionStepState,
    onOpenSettings: () -> Unit,
    onSkip: () -> Unit,
    onContinue: () -> Unit = {}
) {
    val (icon, description, consequence, ctaLabel) = permissionStepCopy(step)

    Column(modifier = Modifier.fillMaxWidth()) {
        // Icon
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.secondary
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Description
        Text(
            text = description,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Status card
        StatusCard(state = state, consequence = consequence)

        Spacer(modifier = Modifier.weight(1f))

        // Primary CTA
        when (state) {
            PermissionStepState.Checking -> {
                LoadingButton(text = "Checking...")
            }
            PermissionStepState.Satisfied -> {
                Button(
                    onClick = onContinue,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Continue")
                }
            }
            else -> {
                Button(
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state != PermissionStepState.OpeningSettings
                ) {
                    Text(ctaLabel)
                }
            }
        }

        // Skip (Battery only)
        if (step == WizardStep.Battery && state != PermissionStepState.Satisfied) {
            TextButton(
                onClick = onSkip,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Text("Continue without this")
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

// ── API Key Step ──

@Composable
fun ApiKeyStepContent(
    state: ApiKeyStepState,
    selectedProvider: OnboardingProvider,
    authMethod: ApiKeyAuthMethod,
    onProviderSelected: (OnboardingProvider) -> Unit,
    onAuthMethodSelected: (ApiKeyAuthMethod) -> Unit,
    onStartOAuth: () -> Unit,
    onCancelOAuth: () -> Unit,
    onContinue: () -> Unit,
    onKeyChanged: (String) -> Unit,
    onValidate: () -> Unit,
    onRetry: () -> Unit
) {
    var passwordVisible by remember { mutableStateOf(false) }
    val currentKey = when (state) {
        is ApiKeyStepState.Editing -> state.key
        is ApiKeyStepState.Validating -> state.key
        is ApiKeyStepState.Invalid -> state.key
        is ApiKeyStepState.TransientError -> state.key
        is ApiKeyStepState.Valid -> state.key
        else -> ""
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Icon(
            imageVector = Icons.Outlined.Key,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.secondary
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Choose your provider to connect.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Provider picker
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OnboardingProvider.visibleInUi.forEach { provider ->
                FilterChip(
                    selected = selectedProvider == provider,
                    onClick = { onProviderSelected(provider) },
                    label = { Text(provider.label) },
                    enabled = state !is ApiKeyStepState.Validating
                            && state !is ApiKeyStepState.Valid
                            && state !is ApiKeyStepState.OAuthInProgress
                            && state !is ApiKeyStepState.OAuthFinishing
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Show OAuth or manual content based on auth method + provider
        if (selectedProvider == OnboardingProvider.OPENAI_API && authMethod == ApiKeyAuthMethod.OAUTH) {
            OAuthContent(
                state = state,
                onStartOAuth = onStartOAuth,
                onCancelOAuth = onCancelOAuth,
                onContinue = onContinue,
                onSwitchToManual = { onAuthMethodSelected(ApiKeyAuthMethod.MANUAL) },
                onRetry = { onStartOAuth() }
            )
        } else {
            ManualApiKeyContent(
                state = state,
                currentKey = currentKey,
                passwordVisible = passwordVisible,
                onPasswordVisibilityToggle = { passwordVisible = !passwordVisible },
                onKeyChanged = onKeyChanged,
                onValidate = onValidate,
                onRetry = onRetry,
                showSwitchToOAuth = selectedProvider == OnboardingProvider.OPENAI_API,
                onSwitchToOAuth = { onAuthMethodSelected(ApiKeyAuthMethod.OAUTH) }
            )
        }
    }
}

@Composable
private fun ColumnScope.OAuthContent(
    state: ApiKeyStepState,
    onStartOAuth: () -> Unit,
    onCancelOAuth: () -> Unit,
    onContinue: () -> Unit,
    onSwitchToManual: () -> Unit,
    onRetry: () -> Unit
) {
    when (state) {
        is ApiKeyStepState.OAuthReady -> {
            Text(
                text = "Sign in with your OpenAI account. Uses your existing ChatGPT subscription — no API key needed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = onStartOAuth,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Sign in with OpenAI")
            }

            TextButton(
                onClick = onSwitchToManual,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            ) {
                Text("or enter API key manually")
            }
        }

        is ApiKeyStepState.OAuthInProgress -> {
            Spacer(modifier = Modifier.height(24.dp))

            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Complete sign-in in your browser.\nYou'll return here automatically.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.weight(1f))

            TextButton(
                onClick = onCancelOAuth,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Cancel")
            }
        }

        is ApiKeyStepState.OAuthFinishing -> {
            Spacer(modifier = Modifier.height(24.dp))

            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Browser sign-in complete.\nFinishing up with OpenAI — this can take ~20 seconds.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.weight(1f))
        }

        is ApiKeyStepState.OAuthSuccess -> {
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = if (state.email.isNotBlank()) "Signed in as ${state.email}"
                    else "Signed in successfully",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.secondary
            )

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Continue")
            }
        }

        is ApiKeyStepState.OAuthError -> {
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = state.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Try Again")
            }

            TextButton(
                onClick = onSwitchToManual,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            ) {
                Text("or enter API key manually")
            }
        }

        else -> {
            // Shouldn't happen in OAuth mode, but handle gracefully
            Spacer(modifier = Modifier.weight(1f))
        }
    }

    Spacer(modifier = Modifier.height(32.dp))
}

@Composable
private fun ColumnScope.ManualApiKeyContent(
    state: ApiKeyStepState,
    currentKey: String,
    passwordVisible: Boolean,
    onPasswordVisibilityToggle: () -> Unit,
    onKeyChanged: (String) -> Unit,
    onValidate: () -> Unit,
    onRetry: () -> Unit,
    showSwitchToOAuth: Boolean,
    onSwitchToOAuth: () -> Unit
) {
    // API key field
    OutlinedTextField(
        value = currentKey,
        onValueChange = onKeyChanged,
        label = { Text("API Key") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = state !is ApiKeyStepState.Validating && state !is ApiKeyStepState.Valid,
        visualTransformation = if (passwordVisible) VisualTransformation.None
            else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = onPasswordVisibilityToggle) {
                Icon(
                    imageVector = if (passwordVisible) Icons.Outlined.VisibilityOff
                        else Icons.Outlined.Visibility,
                    contentDescription = if (passwordVisible) "Hide" else "Show"
                )
            }
        },
        isError = state is ApiKeyStepState.Invalid
    )

    // Security note
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = "Your key is encrypted on-device. Never sent anywhere except the LLM provider.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    // Error message
    when (state) {
        is ApiKeyStepState.Invalid -> {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        is ApiKeyStepState.TransientError -> {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        else -> {}
    }

    Spacer(modifier = Modifier.weight(1f))

    // CTA
    when (state) {
        is ApiKeyStepState.Validating -> LoadingButton(text = "Validating...")
        is ApiKeyStepState.Valid -> SuccessButton(text = "Key verified")
        is ApiKeyStepState.TransientError -> {
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Retry") }
        }
        else -> {
            Button(
                onClick = onValidate,
                modifier = Modifier.fillMaxWidth(),
                enabled = currentKey.isNotBlank()
            ) { Text("Validate & Continue") }
        }
    }

    if (showSwitchToOAuth && state !is ApiKeyStepState.Valid && state !is ApiKeyStepState.Validating) {
        TextButton(
            onClick = onSwitchToOAuth,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        ) {
            Text("or sign in with OpenAI")
        }
    }

    Spacer(modifier = Modifier.height(32.dp))
}

// ── Demo Step ──

@Composable
fun DemoStepContent(
    state: DemoStepState,
    onRunDemo: () -> Unit,
    onSkip: () -> Unit,
    onGoToAuthStep: () -> Unit = {}
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Icon(
            imageVector = Icons.Outlined.RocketLaunch,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.secondary
        )

        Spacer(modifier = Modifier.height(16.dp))

        when (state) {
            DemoStepState.Ready, DemoStepState.Preflight -> {
                Text(
                    text = "We'll open the Settings app to prove everything works. This does not change any device setting.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DemoStepState.Running -> {
                Text(
                    text = "Opening Settings...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            is DemoStepState.Success -> {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            is DemoStepState.Failure -> {
                Text(
                    text = state.reason,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error
                )
            }
            is DemoStepState.CredentialError -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Credential problem",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
            DemoStepState.Skipped -> {}
        }

        Spacer(modifier = Modifier.weight(1f))

        when (state) {
            DemoStepState.Ready -> {
                Button(onClick = onRunDemo, modifier = Modifier.fillMaxWidth()) {
                    Text("Run Demo")
                }
                TextButton(
                    onClick = onSkip,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Text("Skip for now")
                }
            }
            DemoStepState.Preflight, DemoStepState.Running -> {
                LoadingButton(text = "Running...")
            }
            is DemoStepState.Failure -> {
                Button(onClick = onRunDemo, modifier = Modifier.fillMaxWidth()) {
                    Text("Try Again")
                }
                TextButton(
                    onClick = onSkip,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Text("Skip for now")
                }
            }
            is DemoStepState.CredentialError -> {
                Button(onClick = onGoToAuthStep, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.isOAuth) "Sign in again" else "Re-enter API key")
                }
                TextButton(
                    onClick = onSkip,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Text("Skip for now")
                }
            }
            is DemoStepState.Success, DemoStepState.Skipped -> {
                SuccessButton(text = "Demo complete")
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

// ── Complete Step ──

@Composable
fun CompleteStepContent(
    outcomes: StepOutcomes,
    authMethod: ApiKeyAuthMethod,
    accessibilityGranted: Boolean,
    overlayGranted: Boolean,
    batteryGranted: Boolean,
    onFinish: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.secondary
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "You're All Set!",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Checklist — permission rows reflect live state (auto-skipped steps
        // never write StepOutcome.Done, so reading `outcomes` here would lie).
        LiveStatusRow("Accessibility service", accessibilityGranted)
        LiveStatusRow("Display overlay", overlayGranted)
        LiveStatusRow("Battery optimization", batteryGranted)
        val apiKeyLabel = if (authMethod == ApiKeyAuthMethod.OAUTH) "Signed in with OpenAI"
            else "API key verified"
        OutcomeRow(apiKeyLabel, outcomes.apiKey)
        OutcomeRow("Demo task", outcomes.demo)

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onFinish,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Start Using ClosePaw")
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

// ── Shared UI components ──

@Composable
private fun StatusCard(state: PermissionStepState, consequence: String) {
    val (label, color) = when (state) {
        PermissionStepState.Checking -> "Checking..." to MaterialTheme.colorScheme.onSurfaceVariant
        PermissionStepState.Ready -> "Not enabled" to MaterialTheme.colorScheme.error
        PermissionStepState.OpeningSettings -> "Waiting..." to MaterialTheme.colorScheme.onSurfaceVariant
        PermissionStepState.Satisfied -> "Enabled" to MaterialTheme.colorScheme.secondary
        PermissionStepState.Unsatisfied -> "Not enabled" to MaterialTheme.colorScheme.error
        PermissionStepState.Skipped -> "Skipped" to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Status: $label",
                style = MaterialTheme.typography.bodyMedium,
                color = color
            )
            if (state == PermissionStepState.Unsatisfied) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = consequence,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun LoadingButton(text: String) {
    Button(
        onClick = {},
        modifier = Modifier.fillMaxWidth(),
        enabled = false
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text)
    }
}

@Composable
private fun SuccessButton(text: String) {
    Button(
        onClick = {},
        modifier = Modifier.fillMaxWidth(),
        enabled = false,
        colors = ButtonDefaults.buttonColors(
            disabledContainerColor = MaterialTheme.colorScheme.secondary,
            disabledContentColor = MaterialTheme.colorScheme.onSecondary
        )
    ) {
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text)
    }
}

@Composable
private fun LiveStatusRow(label: String, granted: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val (icon, tint) = if (granted) {
            Icons.Filled.Check to MaterialTheme.colorScheme.secondary
        } else {
            Icons.Filled.Close to MaterialTheme.colorScheme.error
        }
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = tint
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
private fun OutcomeRow(label: String, outcome: StepOutcome) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val (icon, tint, suffix) = when (outcome) {
            StepOutcome.Done -> Triple(
                Icons.Filled.Check,
                MaterialTheme.colorScheme.secondary,
                ""
            )
            StepOutcome.Skipped -> Triple(
                Icons.Filled.Close,
                MaterialTheme.colorScheme.onSurfaceVariant,
                " (skipped)"
            )
            StepOutcome.Pending -> Triple(
                Icons.Filled.Close,
                MaterialTheme.colorScheme.error,
                ""
            )
        }

        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = tint
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "$label$suffix",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

// ── Copy for permission steps ──

private data class PermissionCopy(
    val icon: ImageVector,
    val description: String,
    val consequence: String,
    val ctaLabel: String
)

private fun permissionStepCopy(step: WizardStep): PermissionCopy = when (step) {
    WizardStep.Accessibility -> PermissionCopy(
        icon = Icons.Outlined.Security,
        description = "Android only allows trusted automation through Accessibility. This lets the agent read screens and perform taps in other apps.",
        consequence = "Without Accessibility, ClosePaw cannot automate tasks.",
        ctaLabel = "Open Accessibility Settings"
    )
    WizardStep.Overlay -> PermissionCopy(
        icon = Icons.Outlined.Layers,
        description = "The floating capsule shows progress and lets you stop, take over, or return to ClosePaw.",
        consequence = "Without Overlay, you won't see controls while the agent works in other apps.",
        ctaLabel = "Grant Overlay Permission"
    )
    WizardStep.Battery -> PermissionCopy(
        icon = Icons.Outlined.BatteryChargingFull,
        description = "Some phones aggressively stop background work. Allowing unrestricted battery use makes long tasks reliable.\n\nThis is optional but recommended.",
        consequence = "Long tasks may stop when the app is backgrounded.",
        ctaLabel = "Allow Background Running"
    )
    else -> error("Not a permission step: $step")
}
