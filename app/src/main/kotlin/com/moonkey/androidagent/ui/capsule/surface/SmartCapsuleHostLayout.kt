package com.moonkey.androidagent.ui.capsule.surface

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Shared host-level spacing around SmartCapsuleSurface across main-app and overlay containers.
 */
fun Modifier.smartCapsuleHostPadding(): Modifier =
    this.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)

