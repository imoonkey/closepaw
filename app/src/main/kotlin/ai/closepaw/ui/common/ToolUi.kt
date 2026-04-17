package ai.closepaw.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.SwipeVertical
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.ui.graphics.vector.ImageVector
import ai.closepaw.tool.MobileActionName
import ai.closepaw.tool.ToolName

data class ToolDisplay(
    val name: String,
    val icon: ImageVector
)

fun formatToolName(toolName: String): String = resolveToolDisplay(toolName).name

fun getToolIcon(toolName: String): ImageVector = resolveToolDisplay(toolName).icon

private fun resolveToolDisplay(toolName: String): ToolDisplay {
    val action = MobileActionName.fromOrNull(toolName)
    if (action != null) {
        return ToolDisplay(action.displayName, iconForAction(action))
    }

    return when (val tool = ToolName.from(toolName)) {
        ToolName.MobileAction -> ToolDisplay(tool.displayName, Icons.Rounded.TouchApp)
        ToolName.OpenApp -> ToolDisplay(tool.displayName, Icons.Rounded.Apps)
        ToolName.Wait -> ToolDisplay(tool.displayName, Icons.Rounded.HourglassEmpty)
        ToolName.SystemButton -> ToolDisplay(tool.displayName, Icons.Rounded.TouchApp)
        ToolName.CompleteTask -> ToolDisplay(tool.displayName, Icons.Rounded.CheckCircle)
        ToolName.WriteTodos -> ToolDisplay(tool.displayName, Icons.AutoMirrored.Rounded.FormatListBulleted)
        ToolName.Scratchpad -> ToolDisplay(tool.displayName, Icons.Rounded.Build)
        ToolName.DelegateTask -> ToolDisplay(tool.displayName, Icons.Rounded.Apps)
        ToolName.RememberExperience -> ToolDisplay(tool.displayName, Icons.Rounded.Build)
        ToolName.AskUser -> ToolDisplay(tool.displayName, Icons.Rounded.Build)
        ToolName.Shell -> ToolDisplay(tool.displayName, Icons.Rounded.Build)
        is ToolName.Unknown -> ToolDisplay(tool.displayName, Icons.Rounded.Build)
    }
}

private fun iconForAction(action: MobileActionName): ImageVector = when (action) {
    MobileActionName.Click -> Icons.Rounded.TouchApp
    MobileActionName.LongPress -> Icons.Rounded.TouchApp
    MobileActionName.Type -> Icons.Rounded.Keyboard
    MobileActionName.Scroll -> Icons.Rounded.UnfoldMore
    MobileActionName.Swipe -> Icons.Rounded.SwipeVertical
    MobileActionName.Back -> Icons.AutoMirrored.Rounded.ArrowBack
    MobileActionName.Home -> Icons.Rounded.Home
    MobileActionName.Wait -> Icons.Rounded.HourglassEmpty
    MobileActionName.SystemButton -> Icons.Rounded.TouchApp
    is MobileActionName.Unknown -> Icons.Rounded.Build
}
