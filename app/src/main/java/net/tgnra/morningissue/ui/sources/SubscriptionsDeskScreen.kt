package net.tgnra.morningissue.ui.sources

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import net.tgnra.morningissue.R
import net.tgnra.morningissue.ui.components.EmptyMessage
import net.tgnra.morningissue.ui.components.PaperScreenFrame

/**
 * The feed health check: flags and one-tap fixes. The health rules arrive in phase 3; until
 * then this says so rather than pretending every feed has been checked.
 */
@Composable
fun SubscriptionsDeskScreen(
    @Suppress("UNUSED_PARAMETER") onOpenSource: (feedId: Long) -> Unit,
    onBack: () -> Unit,
) {
    PaperScreenFrame(title = stringResource(R.string.desk_title), onBack = onBack) {
        EmptyMessage(stringResource(R.string.desk_placeholder_title), stringResource(R.string.desk_placeholder_detail))
    }
}
