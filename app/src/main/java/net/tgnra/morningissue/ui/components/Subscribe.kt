package net.tgnra.morningissue.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.tgnra.morningissue.AppContainer
import net.tgnra.morningissue.R
import net.tgnra.morningissue.ui.theme.MorningIssueTheme

/**
 * Subscribes to a feed the reader found through discovery (the server fetches it), then syncs.
 * Runs in the app's scope: the subscription is for the next edition, whatever screen is open.
 */
fun subscribeAndSync(container: AppContainer, feedId: Long) {
    container.appScope.launch {
        if (container.feeds.subscribe(feedId)) container.serverSync.sync("subscribed")
    }
}

/**
 * The bar shown wherever a post from a source the reader does not subscribe to appears (the
 * reader, the Saved shelf, From your reading list, the source page): what it is, and a
 * prominent Subscribe button. Once subscribed it says so instead.
 *
 * @param compact one line of text, for lists and the edition page
 */
@Composable
fun NotSubscribedBar(
    feedTitle: String,
    subscribed: Boolean,
    onSubscribe: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    extra: @Composable () -> Unit = {},
) {
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    Column(
        modifier.fillMaxWidth().border(1.dp, colors.rule).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (subscribed) {
            Text(
                stringResource(R.string.subscribed_to, feedTitle),
                style = type.label,
                color = colors.ink,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            return@Column
        }
        if (!compact) SectionLabel(stringResource(R.string.not_subscribed), color = colors.accent)
        Text(
            stringResource(if (compact) R.string.not_subscribed_short else R.string.not_subscribed_detail, feedTitle),
            style = if (compact) type.caption else type.body,
            color = colors.inkSecondary,
            maxLines = if (compact) 2 else 4,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            PaperButton(
                text = stringResource(R.string.discover_subscribe),
                onClick = onSubscribe,
                icon = Icons.Filled.Add,
                primary = true,
            )
            extra()
        }
    }
}

/** What was just done or removed, with the way to put it back. */
@Composable
fun UndoLine(text: String, onUndo: () -> Unit, modifier: Modifier = Modifier, undoLabel: String = stringResource(R.string.action_undo)) {
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Min).border(1.5.dp, colors.ink),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = type.label,
            color = colors.ink,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        Box(Modifier.fillMaxHeight().width(1.5.dp).background(colors.ink))
        Box(
            Modifier
                .fillMaxHeight()
                .defaultMinSize(minWidth = 80.dp, minHeight = 48.dp)
                .clickable(role = Role.Button, onClick = onUndo)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(undoLabel, style = type.label.copy(fontWeight = FontWeight.Bold), color = colors.ink)
        }
    }
}
