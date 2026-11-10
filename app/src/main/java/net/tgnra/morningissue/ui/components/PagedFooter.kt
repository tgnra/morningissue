package net.tgnra.morningissue.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.tgnra.morningissue.R
import net.tgnra.morningissue.ui.theme.MorningIssueTheme

/** E-ink's page controls: boxed Previous and Next, which step through a long page before turning it. */
@Composable
fun PagedFooter(canGoBack: Boolean, canGoForward: Boolean, onBackward: () -> Unit, onForward: () -> Unit) {
    val colors = MorningIssueTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.rule))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PaperButton(
                text = stringResource(R.string.paged_previous),
                onClick = onBackward,
                modifier = Modifier.weight(1f),
                icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                enabled = canGoBack,
            )
            PaperButton(
                text = stringResource(R.string.paged_next),
                onClick = onForward,
                modifier = Modifier.weight(1f),
                icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                enabled = canGoForward,
            )
        }
    }
}
