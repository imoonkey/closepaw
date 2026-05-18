package ai.closepaw.ui.theme

import ai.closepaw.R
import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Date

// "Bound Edition" ornaments. Single source for the paper-zine register —
// fleuron + running-head — so call sites never reinvent them.

@Composable
fun Fleuron(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "❦",
            style = TextStyle(
                fontFamily = Fraunces,
                fontStyle = FontStyle.Italic,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
            ),
            color = MaterialTheme.closePaw.inkFaint,
        )
    }
}

@Composable
fun PageMasthead(
    title: String,
    modifier: Modifier = Modifier,
    leadingSlot: @Composable RowScope.() -> Unit = { PawGlyph() },
    rightSlot: @Composable RowScope.() -> Unit = { TodayLabel() },
    trailingSlot: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = MaterialTheme.closePaw.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leadingSlot()
        Spacer(Modifier.width(MaterialTheme.closePaw.spacing.sm))
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = TextStyle(
                fontFamily = Fraunces,
                fontStyle = FontStyle.Italic,
                fontWeight = FontWeight.Normal,
                fontSize = 18.sp,
                lineHeight = 22.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(MaterialTheme.closePaw.spacing.sm))
        rightSlot()
        trailingSlot()
    }
}

// Pre-slot call-site compatibility shim. Three known callers (ChatHeader,
// NavigationDrawer DrawerHeader, SettingsHomePage) continue to compile during
// the Phase 0 → Phase 1 window; Phase 1 migrates them and deletes this.
@Deprecated(
    message = "Use the slot-based PageMasthead or PageMastheadIdentity / PageMastheadDrillDown convenience wrappers.",
    replaceWith = ReplaceWith("PageMasthead(title = title, modifier = modifier)"),
)
@Composable
fun PageMasthead(
    title: String,
    modifier: Modifier = Modifier,
    leadingPaw: Boolean = true,
    onLeadingClick: (() -> Unit)? = null,
) {
    PageMasthead(
        title = title,
        modifier = modifier,
        leadingSlot = {
            if (leadingPaw) PawGlyph(onClick = onLeadingClick)
        },
    )
}

@Composable
fun PawGlyph(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val sized = modifier.size(18.dp)
    val tappable = if (onClick != null) sized.clickable(onClick = onClick) else sized
    Icon(
        painter = painterResource(R.drawable.ic_paw),
        contentDescription = null,
        modifier = tappable,
        tint = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
fun TodayLabel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val text = remember(context) {
        DateFormat.getMediumDateFormat(context).format(Date())
    }
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.closePaw.monoSmall,
        color = MaterialTheme.closePaw.inkFaint,
    )
}

@Composable
fun PageMastheadIdentity(
    title: String,
    onClose: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    PageMasthead(
        title = title,
        modifier = modifier,
        trailingSlot = {
            if (onClose != null) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

@Composable
fun PageMastheadDrillDown(
    title: String,
    onBack: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PageMasthead(
        title = title,
        modifier = modifier,
        leadingSlot = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Rounded.ChevronLeft,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingSlot = {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Close",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

// Section subhead — Fraunces italic Regular at 18sp on identity surfaces
// (e.g. Settings home groupings). Bound Edition direction.md §sectioning.
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = TextStyle(
            fontFamily = Fraunces,
            fontStyle = FontStyle.Italic,
            fontWeight = FontWeight.Normal,
            fontSize = 18.sp,
            lineHeight = 22.sp,
        ),
        color = MaterialTheme.closePaw.inkFaint,
        modifier = modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}
