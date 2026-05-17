package ai.closepaw.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.closepaw.protocol.AppTier
import ai.closepaw.tool.AppClassifier
import ai.closepaw.ui.theme.closePaw
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class AppFilter(val label: String) {
    All("All"),
    Allow("Allow"),
    Ask("Ask"),
    Reject("Reject"),
}

@Composable
internal fun AppAccessSettingsPage(
    appClassifier: AppClassifier,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val overrides by appClassifier.userOverrides.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    val rowsState = produceState<List<AppRow>?>(initialValue = null, context) {
        value = withContext(Dispatchers.IO) { loadInstalledAppRows(context) }
    }
    val rows = rowsState.value

    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(AppFilter.All) }

    // Pending downgrade of a bundled-BLOCKED app awaiting user confirmation.
    // BLOCKED writes (the safe direction) bypass this dialog.
    var pendingConfirm by remember { mutableStateOf<PendingConfirm?>(null) }

    val commitTier: (String, AppTier) -> Unit = { pkg, tier ->
        coroutineScope.launch(Dispatchers.IO) {
            appClassifier.setOverride(pkg, tier)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SettingsSubPageHeader(title = "App Access", onBack = onBack, onClose = onClose)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SearchField(query = query, onQueryChange = { query = it })
            FilterChipsRow(selected = filter, onSelect = { filter = it })
        }

        Spacer(modifier = Modifier.height(12.dp))

        when {
            rows == null -> LoadingState()
            else -> {
                val filtered = remember(rows, overrides, query, filter) {
                    filterRows(rows, appClassifier, query.trim(), filter)
                }
                AppList(
                    rows = filtered,
                    classifier = appClassifier,
                    onPickTier = { pkg, appLabel, tier, isBundledBlocked ->
                        if (isBundledBlocked && tier != AppTier.BLOCKED) {
                            pendingConfirm = PendingConfirm(pkg, appLabel, tier)
                        } else {
                            commitTier(pkg, tier)
                        }
                    },
                )
            }
        }
    }

    pendingConfirm?.let { pending ->
        SensitiveAppConfirmDialog(
            appLabel = pending.appLabel,
            tier = pending.tier,
            onConfirm = {
                commitTier(pending.pkg, pending.tier)
                pendingConfirm = null
            },
            onDismiss = { pendingConfirm = null },
        )
    }
}

private data class PendingConfirm(
    val pkg: String,
    val appLabel: String,
    val tier: AppTier,
)

@Composable
private fun SensitiveAppConfirmDialog(
    appLabel: String,
    tier: AppTier,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val (titleText, bodyText, confirmLabel) = when (tier) {
        AppTier.NORMAL -> Triple(
            "Allow agent to access $appLabel?",
            "This app is marked sensitive. The agent will be able to read its " +
                "screen and tap inside it.",
            "Allow",
        )
        AppTier.CAUTIOUS -> Triple(
            "Change $appLabel to Ask?",
            "This app is marked sensitive. You will be prompted each time the " +
                "agent wants to access it.",
            "Confirm",
        )
        // BLOCKED writes bypass this dialog entirely (see onPickTier in AppList).
        AppTier.BLOCKED -> Triple("", "", "Confirm")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(titleText) },
        text = { Text(bodyText) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(10.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = "Search apps or package name",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.closePaw.inkFaint,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun FilterChipsRow(selected: AppFilter, onSelect: (AppFilter) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AppFilter.entries.forEach { f ->
            FilterChip(
                label = f.label,
                selected = selected == f,
                onClick = { onSelect(f) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
        tonalElevation = if (selected) 2.dp else 0.dp,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 12.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 2.dp,
        )
    }
}

@Composable
private fun AppList(
    rows: List<AppRow>,
    classifier: AppClassifier,
    onPickTier: (pkg: String, appLabel: String, tier: AppTier, isBundledBlocked: Boolean) -> Unit,
) {
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (rows.isEmpty()) {
            item {
                Text(
                    text = "No apps match.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.closePaw.inkFaint,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
        } else {
            items(rows, key = { it.info.packageName }) { row ->
                val pkg = row.info.packageName
                val isBundledBlocked = classifier.bundledTier(pkg) == AppTier.BLOCKED
                AppRowItem(
                    row = row,
                    effectiveTier = classifier.classify(pkg),
                    isBundledBlocked = isBundledBlocked,
                    onPickTier = { tier ->
                        onPickTier(pkg, row.info.label, tier, isBundledBlocked)
                    },
                )
            }
        }
    }
}

@Composable
private fun AppRowItem(
    row: AppRow,
    effectiveTier: AppTier,
    isBundledBlocked: Boolean,
    onPickTier: (AppTier) -> Unit,
) {
    val icon by produceState<ImageBitmap?>(initialValue = null, row.info.packageName) {
        value = row.iconLoader()
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(bitmap = icon)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = row.info.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (isBundledBlocked) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Outlined.Lock,
                                contentDescription = "Sensitive app",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.closePaw.inkFaint,
                            )
                        }
                    }
                    Text(
                        text = row.info.packageName,
                        style = MaterialTheme.closePaw.monoSmall,
                        color = MaterialTheme.closePaw.inkFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isBundledBlocked) {
                        Text(
                            text = "Sensitive — confirm before changing",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            TierSegmentedSelector(selected = effectiveTier, onPick = onPickTier)
        }
    }
}

@Composable
private fun AppIcon(bitmap: ImageBitmap?) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.closePaw.inkFaint),
            )
        }
    }
}

@Composable
private fun TierSegmentedSelector(
    selected: AppTier,
    onPick: (AppTier) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SegmentChip(
            label = "Allow",
            isSelected = selected == AppTier.NORMAL,
            onClick = { onPick(AppTier.NORMAL) },
            modifier = Modifier.weight(1f),
        )
        SegmentChip(
            label = "Ask",
            isSelected = selected == AppTier.CAUTIOUS,
            onClick = { onPick(AppTier.CAUTIOUS) },
            modifier = Modifier.weight(1f),
        )
        SegmentChip(
            label = "Reject",
            isSelected = selected == AppTier.BLOCKED,
            onClick = { onPick(AppTier.BLOCKED) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SegmentChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
        tonalElevation = if (isSelected) 2.dp else 0.dp,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

// --- filter helpers ---

private fun filterRows(
    rows: List<AppRow>,
    classifier: AppClassifier,
    query: String,
    filter: AppFilter,
): List<AppRow> {
    val q = query.lowercase()
    return rows.filter { row ->
        val matchesQuery = q.isEmpty() ||
            row.info.label.lowercase().contains(q) ||
            row.info.packageName.lowercase().contains(q)
        if (!matchesQuery) return@filter false

        val pkg = row.info.packageName
        when (filter) {
            AppFilter.All -> true
            AppFilter.Allow -> classifier.classify(pkg) == AppTier.NORMAL
            AppFilter.Ask -> classifier.classify(pkg) == AppTier.CAUTIOUS
            AppFilter.Reject -> classifier.classify(pkg) == AppTier.BLOCKED
        }
    }
}
