package net.tgnra.morningissue.edition

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import net.tgnra.morningissue.engine.EditionClock
import net.tgnra.morningissue.engine.ReleaseSchedule
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.EditionSummary

/** A scheduled release whose time has come: its edition is the current one of its kind. */
data class DueRelease(
    val kind: EditionKind,
    /** The release time: the morning reset, or the Evening edition's time. */
    val at: Instant,
    /** The date its edition is published for. */
    val date: LocalDate,
)

/** Where the stand is with the scheduled edition that is due. */
sealed interface DueStatus {
    /** The due edition is on the stand. Nothing to say. */
    data object Printed : DueStatus

    /**
     * No edition was printed for [release] because there was nothing new to print. Not a
     * failure: the stand keeps the previous paper and says "No new edition today".
     */
    data class NothingNew(val release: DueRelease) : DueStatus

    /**
     * The edition for [release] failed to build: the stand shows the unprinted placeholder
     * with Print now. See [DueEditions] for what counts.
     */
    data class NotPrinted(val release: DueRelease) : DueStatus
}

/**
 * Decides whether a scheduled edition that is due **failed to build**, from what is stored.
 * Pure, so every case can be tested; the newsstand shows the placeholder cover exactly when
 * [status] returns [DueStatus.NotPrinted].
 *
 * Editions are built in the background by the early build (`EarlyBuild`, run by WorkManager)
 * and never silently when the app is opened. So once a release time has passed, one of three
 * things is true of its edition:
 *
 * 1. **It was printed**: an edition for it is stored and released.
 * 2. **There was nothing new to print**: a run reached the feeds, built, and found no new
 *    post to print. The publisher stores no empty or reprint-only paper, so no edition exists,
 *    and without a record this would look like case 3. The run therefore writes the release
 *    time down (`AppSettings.nothingNewFor`), and that record is the only thing added to
 *    storage for this decision.
 * 3. **It failed to build**: everything else, which is to say no edition and no such record.
 *    That covers a build that threw; a run whose sync reached no feed and which found nothing
 *    stored to print (it cannot know there was nothing new); and a run that never happened,
 *    because WorkManager did not start it (Doze, the app force-stopped, the device off or
 *    without a network), or it started too late or was stopped part-way. These are not told
 *    apart, because the reader's remedy is the same: Print now.
 *
 * The special cases:
 *
 * - **No sources.** Nothing can be new, so it is never a failure: a brand-new install shows
 *   the empty stand and its "Add sources" button, not the placeholder.
 * - **Sources but no edition yet** (onboarding was skipped, or its first sync found nothing
 *   recent). The latest reset is due like any other, so the placeholder shows until an
 *   edition is printed or a run records that there was nothing new.
 * - **Onboarding** prints the first edition itself, in the foreground, when it finishes its
 *   sync. Once that edition exists the stand is in case 1.
 * - **Evening edition** (when switched on, from its release time until the next morning
 *   reset). It is due like the morning one and gets the placeholder when it failed to build.
 *   The newest printed edition settles the matter: with the evening paper on the stand, a
 *   morning edition missed earlier that day is not asked for (its posts wait for the next
 *   morning). With no evening paper because there was nothing new for it, the question falls
 *   back to the morning edition.
 * - **Extra editions** are printed on request and are never due: one on the stand does not
 *   count as today's paper, and none is ever reported as unprinted.
 * - **A release time changed in Settings** applies from the next release (`ReleaseSchedule`),
 *   so it never makes an edition due, or due again, on the day it is changed: the release
 *   that had come keeps its instant, and with it its edition or its "nothing new" record.
 * - **Time zones.** Travelling west can leave a morning edition on the stand dated after the
 *   latest local reset. That edition is current, so the reset counts as printed.
 */
object DueEditions {
    /**
     * The releases whose editions are current at [now], newest first: the Evening edition's
     * when it is switched on and its time has passed since the latest morning reset, then
     * that morning reset. Never empty.
     *
     * They come from [schedule], not from the bare times, so a release time changed in
     * Settings does not move a release that has already come: see [ReleaseSchedule].
     */
    fun due(now: Instant, schedule: ReleaseSchedule, zone: ZoneId): List<DueRelease> = buildList {
        schedule.currentEveningRelease(now, zone)?.let { evening ->
            add(DueRelease(EditionKind.EVENING, evening, EditionClock.editionDate(evening, zone)))
        }
        val reset = schedule.latestReset(now, zone)
        add(DueRelease(EditionKind.MORNING, reset, EditionClock.editionDate(reset, zone)))
    }

    /**
     * The edition among [released] (released editions only) that answers [release]: of its
     * kind, dated on or after the release's date. Null when it has not been printed.
     */
    fun editionFor(release: DueRelease, released: List<EditionSummary>): EditionSummary? =
        released.filter { it.kind == release.kind && !it.date.isBefore(release.date) }.maxByOrNull { it.date }

    /**
     * The releases, newest first, that still have to be accounted for: every one of [due]
     * that is newer than the newest printed one.
     */
    fun unprinted(due: List<DueRelease>, released: List<EditionSummary>): List<DueRelease> =
        due.takeWhile { editionFor(it, released) == null }

    /**
     * The stand's verdict at [now]; see the class comment for the rule.
     *
     * @param released the released editions, as `EditionRepository.observeReleasedEditions` gives them
     * @param nothingNewFor release times a run found nothing new to print for (`AppSettings.nothingNewFor`)
     * @param hasSources whether any feed is subscribed
     */
    fun status(
        now: Instant,
        schedule: ReleaseSchedule,
        zone: ZoneId,
        released: List<EditionSummary>,
        nothingNewFor: Collection<Instant>,
        hasSources: Boolean,
    ): DueStatus {
        val due = due(now, schedule, zone)
        val open = unprinted(due, released)
        // Oldest first: when both of the day's papers failed, the morning one is asked for.
        val failed = open.lastOrNull { it.at !in nothingNewFor }
        return when {
            failed != null && hasSources -> DueStatus.NotPrinted(failed)
            open.size < due.size -> DueStatus.Printed
            else -> DueStatus.NothingNew(due.last())
        }
    }
}
