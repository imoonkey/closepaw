package ai.closepaw.ui.settings

import ai.closepaw.agent.cognition.prompt.AssetAppSkillRepository
import ai.closepaw.app.MemoryEditGate
import ai.closepaw.memory.MemoryScope
import ai.closepaw.memory.MemoryStore
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.closepaw.protocol.AppTier
import ai.closepaw.tool.AppClassifier
import ai.closepaw.ui.theme.closePaw
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class AppFilter(val label: String) {
    All("All"),
    Allow("Allow"),
    Ask("Ask"),
    Reject("Reject"),
}

internal const val APP_ROW_TRAILING_CHEVRON_TAG = "app-row-trailing-chevron"
internal const val APP_ROW_ADD_MEMORY_TAG = "app-row-add-memory"
internal const val APP_ROW_MEMORY_CHIP_TAG = "app-row-memory-chip"
internal const val APP_ROW_SKILL_CHIP_TAG = "app-row-skill-chip"

private enum class AddMemoryOutcome { Created, AlreadyExists, Aborted, WriteFailed }

@Composable
internal fun AppAccessSettingsPage(
    appClassifier: AppClassifier,
    memoryStore: MemoryStore,
    gate: MemoryEditGate,
    onBack: () -> Unit,
    onClose: () -> Unit,
    contentIndex: AppAccessContentIndex? = null,
    skillLoader: (suspend (String) -> String?)? = null,
    rowsOverride: List<AppRow>? = null,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val context = LocalContext.current
    val overrides by appClassifier.userOverrides.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    val index = remember(context, contentIndex) {
        contentIndex ?: AppAccessContentIndex(
            memoryPackages = AppAccessContentIndex.memoryLister(memoryStore),
            skillPackages = AppAccessContentIndex.assetSkillLister(context.assets),
        )
    }
    val summaries by index.summaries.collectAsState()

    val effectiveSkillLoader: suspend (String) -> String? = if (skillLoader != null) {
        skillLoader
    } else {
        val repo = remember(context) { AssetAppSkillRepository(context.assets) }
        remember(repo) { { pkg: String -> repo.load(pkg) } }
    }

    LaunchedEffect(index) { index.load() }

    val rowsState = produceState<List<AppRow>?>(initialValue = rowsOverride, context, rowsOverride) {
        if (rowsOverride != null) {
            value = rowsOverride
        } else {
            value = withContext(ioDispatcher) { loadInstalledAppRows(context) }
        }
    }
    val rows = rowsState.value

    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(AppFilter.All) }
    val expandedPackages = remember { mutableStateMapOf<String, Boolean>() }
    // Per-package one-shot nonce: bumped whenever "+ Memory" creates a fresh
    // file, threaded into MemoryFileEditor so the editor lands in EDIT
    // immediately rather than VIEW. Map survives recomposition only — process
    // death drops the signal, which is correct (file already exists).
    val startInEditNonces = remember { mutableStateMapOf<String, String>() }
    val locked by gate.memoryEditLocked.collectAsStateWithLifecycle()

    val commitTier: (String, AppTier) -> Unit = { pkg, tier ->
        coroutineScope.launch(Dispatchers.IO) {
            appClassifier.setOverride(pkg, tier)
        }
    }

    val onMemoryPresenceChanged: (String, Boolean) -> Unit = { pkg, hasMemory ->
        val existing = summaries[pkg] ?: AppContentSummary.NONE
        coroutineScope.launch {
            index.update(pkg, existing.copy(hasMemory = hasMemory))
        }
    }

    val onAddMemory: (String) -> Unit = { pkg ->
        // UI-layer gate: chip is also disabled when locked, but the click can
        // race the lock flipping true mid-recomposition. Drop the click here
        // before launching to avoid spawning an aborted coroutine.
        if (!locked) {
            coroutineScope.launch {
                // Two safety layers around the write:
                //  - Idempotent: re-read inside the coroutine. If a file
                //    already exists (page mounted with a stale empty index,
                //    or two "+ Memory" taps raced), skip the write so an
                //    existing apps/<pkg>.md is never blanked.
                //  - Gate TOCTOU: re-check `gate.isLockedNow()` right before
                //    the write. If a session began between click and IO,
                //    abort with the standard toast. `isLockedNow()` reads
                //    the upstream state directly so it cannot lag the lock.
                val outcome = withContext(ioDispatcher) {
                    if (gate.isLockedNow()) {
                        AddMemoryOutcome.Aborted
                    } else if (memoryStore.read(MemoryScope.APP, pkg) != null) {
                        AddMemoryOutcome.AlreadyExists
                    } else {
                        when (memoryStore.write(MemoryScope.APP, pkg, "")) {
                            ai.closepaw.memory.SaveResult.Success ->
                                AddMemoryOutcome.Created
                            else -> AddMemoryOutcome.WriteFailed
                        }
                    }
                }
                when (outcome) {
                    AddMemoryOutcome.Created, AddMemoryOutcome.AlreadyExists -> {
                        val existing = summaries[pkg] ?: AppContentSummary.NONE
                        index.update(pkg, existing.copy(hasMemory = true))
                        expandedPackages[pkg] = true
                        // Fresh nonce: editor consumes it once and switches to EDIT.
                        startInEditNonces[pkg] =
                            "${System.currentTimeMillis()}-${startInEditNonces.size}"
                    }
                    AddMemoryOutcome.Aborted -> {
                        Toast.makeText(context, MEMORY_EDIT_ABORT_TOAST, Toast.LENGTH_SHORT).show()
                    }
                    AddMemoryOutcome.WriteFailed -> {
                        // Best-effort: nothing to surface inline (no error host on the row).
                        // The agent's write path will retry next session.
                    }
                }
            }
        } else {
            Toast.makeText(context, MEMORY_EDIT_ABORT_TOAST, Toast.LENGTH_SHORT).show()
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
                    summaries = summaries,
                    expanded = expandedPackages,
                    startInEditNonces = startInEditNonces,
                    addMemoryLocked = locked,
                    onToggleExpand = { pkg ->
                        expandedPackages[pkg] = !(expandedPackages[pkg] ?: false)
                    },
                    onPickTier = { pkg, tier -> commitTier(pkg, tier) },
                    onAddMemory = onAddMemory,
                    onMemoryPresenceChanged = onMemoryPresenceChanged,
                    memoryStore = memoryStore,
                    gate = gate,
                    skillLoader = effectiveSkillLoader,
                    ioDispatcher = ioDispatcher,
                )
            }
        }
    }
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
    summaries: Map<String, AppContentSummary>,
    expanded: Map<String, Boolean>,
    startInEditNonces: Map<String, String>,
    addMemoryLocked: Boolean,
    onToggleExpand: (String) -> Unit,
    onPickTier: (pkg: String, tier: AppTier) -> Unit,
    onAddMemory: (String) -> Unit,
    onMemoryPresenceChanged: (String, Boolean) -> Unit,
    memoryStore: MemoryStore,
    gate: MemoryEditGate,
    skillLoader: suspend (String) -> String?,
    ioDispatcher: CoroutineDispatcher,
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
                val summary = summaries[pkg] ?: AppContentSummary.NONE
                AppRowItem(
                    row = row,
                    effectiveTier = classifier.classify(pkg),
                    isBundledBlocked = isBundledBlocked,
                    summary = summary,
                    isExpanded = expanded[pkg] ?: false,
                    addMemoryLocked = addMemoryLocked,
                    startInEditNonce = startInEditNonces[pkg],
                    onToggleExpand = { onToggleExpand(pkg) },
                    onAddMemory = { onAddMemory(pkg) },
                    onPickTier = { tier -> onPickTier(pkg, tier) },
                    onMemoryPresenceChanged = { has -> onMemoryPresenceChanged(pkg, has) },
                    memoryStore = memoryStore,
                    gate = gate,
                    skillLoader = skillLoader,
                    ioDispatcher = ioDispatcher,
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
    summary: AppContentSummary,
    isExpanded: Boolean,
    addMemoryLocked: Boolean,
    startInEditNonce: String?,
    onToggleExpand: () -> Unit,
    onAddMemory: () -> Unit,
    onPickTier: (AppTier) -> Unit,
    onMemoryPresenceChanged: (Boolean) -> Unit,
    memoryStore: MemoryStore,
    gate: MemoryEditGate,
    skillLoader: suspend (String) -> String?,
    ioDispatcher: CoroutineDispatcher,
) {
    val pkg = row.info.packageName
    val icon by produceState<ImageBitmap?>(initialValue = null, pkg) {
        value = row.iconLoader()
    }
    val hasContent = summary.hasMemory || summary.hasSkill
    val effectivelyBlocked = effectiveTier == AppTier.BLOCKED

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(bitmap = icon)
                Spacer(modifier = Modifier.width(12.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        // Tap row body (not the trailing slot, not tier chips) to expand.
                        // Only active when there is something to show — empty rows
                        // route through the "+ Memory" affordance instead.
                        .let { base ->
                            if (hasContent) base.clickable(onClick = onToggleExpand) else base
                        },
                ) {
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
                        if (summary.hasMemory) {
                            Spacer(modifier = Modifier.width(6.dp))
                            SummaryChip(text = "Memory", testTag = APP_ROW_MEMORY_CHIP_TAG)
                        }
                        if (summary.hasSkill) {
                            Spacer(modifier = Modifier.width(6.dp))
                            SummaryChip(text = "Skill", testTag = APP_ROW_SKILL_CHIP_TAG)
                        }
                    }
                    Text(
                        text = pkg,
                        style = MaterialTheme.closePaw.monoSmall,
                        color = MaterialTheme.closePaw.inkFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isBundledBlocked) {
                        Text(
                            text = "Permanently restricted",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                TrailingSlot(
                    hasContent = hasContent,
                    isExpanded = isExpanded,
                    addMemoryLocked = addMemoryLocked,
                    onToggleExpand = onToggleExpand,
                    onAddMemory = onAddMemory,
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            if (isBundledBlocked) {
                RejectOnlyChip()
            } else {
                TierSegmentedSelector(selected = effectiveTier, onPick = onPickTier)
            }
            AnimatedVisibility(visible = isExpanded && hasContent) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    AppRowExpansion(
                        packageName = pkg,
                        isBlocked = effectivelyBlocked,
                        showMemoryEditor = summary.hasMemory,
                        skillLoader = skillLoader,
                        memoryStore = memoryStore,
                        gate = gate,
                        startInEditNonce = startInEditNonce,
                        onMemoryPresenceChanged = onMemoryPresenceChanged,
                        // TODO: route to a per-app MemoryFileEditorPage variant.
                        // Requires SettingsSheet to expose a nav callback for
                        // SettingsPage.MEMORY targeting (scope=APP, pkg). Left
                        // unwired here so the bounded editor remains usable;
                        // long content still scrolls inside the bounded frame.
                        onOpenFullMemoryEditor = null,
                        ioDispatcher = ioDispatcher,
                    )
                }
            }
        }
    }
}

@Composable
private fun TrailingSlot(
    hasContent: Boolean,
    isExpanded: Boolean,
    addMemoryLocked: Boolean,
    onToggleExpand: () -> Unit,
    onAddMemory: () -> Unit,
) {
    if (hasContent) {
        Surface(
            onClick = onToggleExpand,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .size(32.dp)
                .testTag(APP_ROW_TRAILING_CHEVRON_TAG),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (isExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    } else {
        // UI-layer enforcement of the single-writer rule for "+ Memory" — the
        // action-layer re-check still happens inside the click coroutine, but
        // disabling here also stops the visible affordance from looking
        // tappable while a session is open.
        Surface(
            onClick = onAddMemory,
            enabled = !addMemoryLocked,
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
            modifier = Modifier.testTag(APP_ROW_ADD_MEMORY_TAG),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val tint = if (addMemoryLocked) MaterialTheme.closePaw.inkFaint
                else MaterialTheme.colorScheme.onSurface
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = tint,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Memory",
                    style = MaterialTheme.typography.labelSmall,
                    color = tint,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun SummaryChip(text: String, testTag: String) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
        tonalElevation = 1.dp,
        modifier = Modifier.testTag(testTag),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
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
private fun RejectOnlyChip() {
    Row(modifier = Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.small,
            tonalElevation = 2.dp,
        ) {
            Row(
                modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Reject",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
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
