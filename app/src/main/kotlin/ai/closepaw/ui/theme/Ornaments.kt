package ai.closepaw.ui.theme

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.closepaw.R

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
    leadingPaw: Boolean = true,
    onLeadingClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().height(32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingPaw) {
            val pawModifier = Modifier
                .size(18.dp)
                .let { if (onLeadingClick != null) it.clickable(onClick = onLeadingClick) else it }
            Icon(
                painter = painterResource(R.drawable.ic_paw),
                contentDescription = null,
                modifier = pawModifier,
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = title,
            style = TextStyle(
                fontFamily = Fraunces,
                fontStyle = FontStyle.Italic,
                fontWeight = FontWeight.Normal,
                fontSize = 18.sp,
                lineHeight = 22.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
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
