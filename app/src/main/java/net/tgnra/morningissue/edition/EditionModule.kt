package net.tgnra.morningissue.edition

import kotlinx.coroutines.flow.first
import net.tgnra.morningissue.CoreComponents
import net.tgnra.morningissue.sync.SyncComponents

/** What the edition layer offers to the rest of the app. */
class EditionComponents(
    val publisher: EditionPublisher,
    /** Changes the release times so that they apply from the next release. */
    val releaseTimes: ReleaseTimes,
)

/**
 * The edition layer's wiring. **Owned by the engine/edition agent**: replace the stubs below
 * with the real implementations here, so `AppContainer` never needs to change.
 *
 * Nothing is scheduled: editions are printed in the foreground when the reader opens the app
 * (`EditionPrinter`, which the app container wires with the syncer and the on-device model),
 * so [sync] is not needed at wiring time.
 */
object EditionModule {
    @Suppress("UNUSED_PARAMETER")
    fun create(core: CoreComponents, sync: SyncComponents): EditionComponents = EditionComponents(
        publisher = RoomEditionPublisher(
            database = core.database,
            settings = core.settings,
            clock = core.clock,
            // The on-device model's stored ratings, while the reader has it switched on. Off, or
            // on a phone that never had the model, there are none and N is 1 throughout.
            interestRatings = RoomEditionPublisher.InterestRatings.stored(core.database) {
                core.settings.settings.first().onDeviceAiRanking
            },
        ),
        releaseTimes = ReleaseTimes(
            settings = core.settings,
            isBuilt = { date, kind -> core.database.editionDao().getByDate(date, kind) != null },
            clock = core.clock,
        ),
    )
}
