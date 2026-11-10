package net.tgnra.morningissue.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.tgnra.morningissue.ui.layout.LocalPaperLayoutSpec
import net.tgnra.morningissue.ui.theme.MorningIssueTheme

/** A way out of a placeholder screen. */
data class PlaceholderAction(val label: String, val onClick: () -> Unit)

/**
 * Milestone 0 stand-in for a real screen: a title, a line of detail and a button per way out,
 * so the navigation graph can be walked by hand. Delete once no screen uses it.
 */
@Composable
fun PlaceholderScreen(
    title: String,
    detail: String,
    actions: List<PlaceholderAction>,
    modifier: Modifier = Modifier,
) {
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.paper)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(LocalPaperLayoutSpec.current.pageMargin),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("MORNING ISSUE", style = type.sectionLabel, color = colors.inkSecondary)
        Text(title, style = type.leadHeadline, color = colors.ink)
        HorizontalDivider(thickness = 2.dp, color = colors.rule)
        Text(detail, style = type.standfirst, color = colors.ink)
        HorizontalDivider(color = colors.hairline)
        actions.forEach { action ->
            OutlinedButton(onClick = action.onClick) {
                Text(action.label, style = type.label)
            }
        }
    }
}
