package net.tgnra.morningissue.ui.sources

import androidx.compose.runtime.Composable
import net.tgnra.morningissue.ui.components.PlaceholderAction
import net.tgnra.morningissue.ui.components.PlaceholderScreen

/** The feed health check: flagged feeds with one-tap fixes. Placeholder body. */
@Composable
fun SubscriptionsDeskScreen(
    onOpenSource: (feedId: Long) -> Unit,
    onBack: () -> Unit,
) {
    PlaceholderScreen(
        title = "Subscriptions desk",
        detail = "Feeds that need attention will be here.",
        actions = listOf(
            PlaceholderAction("Open source 1") { onOpenSource(1) },
            PlaceholderAction("Back", onBack),
        ),
    )
}
