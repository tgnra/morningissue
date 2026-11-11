package net.tgnra.morningissue.ui.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import kotlinx.coroutines.launch
import net.tgnra.morningissue.R
import net.tgnra.morningissue.ui.LocalAppContainer
import net.tgnra.morningissue.ui.components.EmptyMessage
import net.tgnra.morningissue.ui.components.PagedList
import net.tgnra.morningissue.ui.components.PaperButton
import net.tgnra.morningissue.ui.components.PaperScreenFrame
import net.tgnra.morningissue.ui.components.retainedState
import net.tgnra.morningissue.ui.format.PaperFormat
import net.tgnra.morningissue.ui.theme.MorningIssueTheme

/**
 * Passed sources: every pass is permanent, and this is the one place to take one back. Sources
 * unsubscribed from in Sources are not listed: they are not suggested again either, but adding
 * one back by its address is the way to return to it.
 */
@Composable
fun PassedScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val passed by retainedState("discover-passed") { container.discovery.observePassed() }
    PaperScreenFrame(title = stringResource(R.string.discover_passed_title), onBack = onBack) {
        val list = passed
        when {
            list == null -> Unit
            list.isEmpty() -> EmptyMessage(
                stringResource(R.string.discover_passed_empty_title),
                stringResource(R.string.discover_passed_empty_detail),
            )
            else -> PagedList(items = list, key = { it.urlKey }) { feed ->
                val colors = MorningIssueTheme.colors
                val type = MorningIssueTheme.typography
                val action = stringResource(R.string.discover_unpass_action, feed.title)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(feed.title, style = type.headlineSmall, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(
                                R.string.discover_passed_on,
                                PaperFormat.shortDate(feed.passedAt.atZone(ZoneId.systemDefault()).toLocalDate()),
                            ),
                            style = type.caption,
                            color = colors.inkSecondary,
                        )
                    }
                    PaperButton(
                        text = stringResource(R.string.discover_unpass),
                        onClick = { scope.launch { container.discovery.unpass(feed.urlKey) } },
                        modifier = Modifier.semantics { contentDescription = action },
                    )
                }
            }
        }
    }
}
