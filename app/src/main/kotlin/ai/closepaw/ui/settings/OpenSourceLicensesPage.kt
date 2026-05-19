package ai.closepaw.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ai.closepaw.ui.theme.Fleuron
import ai.closepaw.ui.theme.PageMastheadDrillDown
import ai.closepaw.ui.theme.closePaw
import ai.closepaw.ui.theme.foldedPaper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException

@Serializable
internal data class LicenseEntry(
    val project: String? = null,
    val description: String? = null,
    val version: String? = null,
    val developers: List<String> = emptyList(),
    val url: String? = null,
    val year: String? = null,
    val licenses: List<LicenseTerm> = emptyList(),
    val dependency: String? = null,
)

@Serializable
internal data class LicenseTerm(
    val license: String? = null,
    @kotlinx.serialization.SerialName("license_url")
    val licenseUrl: String? = null,
)

private val LicenseJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private const val LICENSES_ASSET = "open_source_licenses.json"

internal suspend fun loadLicenseEntries(context: Context): List<LicenseEntry> =
    withContext(Dispatchers.IO) {
        context.assets.open(LICENSES_ASSET).use { input ->
            LicenseJson.decodeFromString<List<LicenseEntry>>(input.bufferedReader().readText())
        }
    }

private sealed interface LicenseLoadState {
    data object Loading : LicenseLoadState
    data class Loaded(val rows: List<LicenseEntry>) : LicenseLoadState
    data class Error(val message: String) : LicenseLoadState
}

@Composable
internal fun OpenSourceLicensesPage(
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var reloadKey by remember { mutableStateOf(0) }
    var loadState by remember { mutableStateOf<LicenseLoadState>(LicenseLoadState.Loading) }

    LaunchedEffect(context, reloadKey) {
        loadState = LicenseLoadState.Loading
        loadState = loadLicenseState(context)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        PageMastheadDrillDown(title = "Open Source Licenses", onBack = onBack, onClose = onClose)
        when (val state = loadState) {
            LicenseLoadState.Loading -> LoadingNotice()
            is LicenseLoadState.Error -> ErrorNotice(
                message = state.message,
                onRetry = { reloadKey += 1 },
            )
            is LicenseLoadState.Loaded -> {
                if (state.rows.isEmpty()) {
                    EmptyNotice()
                } else {
                    LicenseList(rows = state.rows, onOpenUrl = { url -> openUrl(context, url) })
                }
            }
        }
    }
}

private suspend fun loadLicenseState(context: Context): LicenseLoadState =
    try {
        val rows = loadLicenseEntries(context)
            .sortedBy { (it.project ?: it.dependency ?: "").lowercase() }
        LicenseLoadState.Loaded(rows)
    } catch (e: IOException) {
        LicenseLoadState.Error("Could not open the generated license asset. Retry from Settings.")
    } catch (e: SerializationException) {
        LicenseLoadState.Error("Could not read the generated license asset. Retry from Settings.")
    }

@Composable
private fun LoadingNotice() {
    LicenseNoticeCard(
        title = "Loading licenses",
        message = "Reading the generated dependency license list.",
        loading = true,
    )
}

@Composable
private fun EmptyNotice() {
    LicenseNoticeCard(
        title = "No license entries",
        message = "The generated license asset is empty.",
    )
}

@Composable
private fun ErrorNotice(message: String, onRetry: () -> Unit) {
    LicenseNoticeCard(
        title = "Could not load licenses",
        message = message,
        action = {
            TextButton(onClick = onRetry) {
                Text("Retry")
            }
        },
    )
}

@Composable
private fun LicenseNoticeCard(
    title: String,
    message: String,
    loading: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.closePaw.spacing.lg, vertical = MaterialTheme.closePaw.spacing.cardPadding),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .foldedPaper(MaterialTheme.shapes.medium),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
        ) {
            Row(
                modifier = Modifier.padding(MaterialTheme.closePaw.spacing.cardPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.closePaw.spacing.md),
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.dp,
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                action?.invoke()
            }
        }
    }
}

@Composable
private fun LicenseList(rows: List<LicenseEntry>, onOpenUrl: (String) -> Unit) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = MaterialTheme.closePaw.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.closePaw.spacing.sm),
    ) {
        item { LicensesPreamble() }
        items(rows, key = { entry -> entry.dependency ?: entry.project ?: entry.hashCode().toString() }) { entry ->
            LicenseCard(entry = entry, onOpenUrl = onOpenUrl)
        }
        item { Fleuron() }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun LicensesPreamble() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = MaterialTheme.closePaw.spacing.sm, bottom = MaterialTheme.closePaw.spacing.sm),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(MaterialTheme.closePaw.spacing.cardPadding), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "ClosePaw is licensed under the Apache License 2.0. The list below " +
                    "is generated at build time from every runtime dependency.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Note: ai.liquid.leap:leap-sdk is proprietary (Leap Terms of Use); " +
                    "see NOTICE in the repository root for redistribution implications.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LicenseCard(entry: LicenseEntry, onOpenUrl: (String) -> Unit) {
    val title = entry.project?.takeIf { it.isNotBlank() } ?: entry.dependency.orEmpty()
    val coordinates = entry.dependency.orEmpty()
    val licenseLine = entry.licenses.mapNotNull { it.license?.takeIf(String::isNotBlank) }
        .joinToString(", ")
        .ifBlank { "Unknown license" }
    val urlForClick = entry.licenses.firstNotNullOfOrNull { it.licenseUrl?.takeIf(String::isNotBlank) }
        ?: entry.url?.takeIf(String::isNotBlank)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .let { if (urlForClick != null) it.clickable { onOpenUrl(urlForClick) } else it },
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(MaterialTheme.closePaw.spacing.cardPadding), verticalArrangement = Arrangement.spacedBy(MaterialTheme.closePaw.spacing.xs)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (coordinates.isNotBlank() && coordinates != title) {
                Text(
                    text = coordinates,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = licenseLine,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
