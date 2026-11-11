package net.tgnra.morningissue.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import net.tgnra.morningissue.R
import net.tgnra.morningissue.model.EditionKind

/** "No. 12", with the kind added for the editions that are not the morning paper: "No. 12 · Extra". */
@Composable
fun editionNumberLabel(kind: EditionKind, number: Int): String = stringResource(
    when (kind) {
        EditionKind.MORNING -> R.string.edition_number
        EditionKind.EVENING -> R.string.edition_number_evening
        EditionKind.EXTRA -> R.string.edition_number_extra
    },
    number,
)
