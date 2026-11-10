package net.tgnra.morningissue.model

/** How often a feed posts. Thresholds live in `RankingConfig`. */
enum class CadenceTier { FIREHOSE, DAILY, WEEKLY, RARE }

/** The per-feed 5-step slider that overrides learned affinity. [AUTO] is the default. */
enum class SliderStep {
    RARELY, LESS, AUTO, MORE, FAVOURITE;

    /** One step towards [FAVOURITE] ("Show more"); stays put at the end. */
    fun stepUp(): SliderStep = entries[(ordinal + 1).coerceAtMost(entries.lastIndex)]

    /** One step towards [RARELY] ("Show less"); stays put at the end. */
    fun stepDown(): SliderStep = entries[(ordinal - 1).coerceAtLeast(0)]
}

enum class EditionKind { MORNING, EVENING }

enum class EditionLength { SHORT, STANDARD, LONG }

/**
 * Where a story sits in an edition. This is also the contract between the edition builder and
 * the pages the UI renders:
 *
 * - every `edition_item` row carries a 1-based `page` and a `slot` (order within the page);
 * - all items on one page share a page type: [LEAD] and [FRONT] together form the front page
 *   (exactly one [LEAD]); [TOP], [SECTION], [BRIEF], [ARCHIVE] and [AWAY] each get pages of
 *   their own kind; [SECTION] items on a page share the same `section` name;
 * - [INDEX] items ("Also in your feeds") belong to the back page, which is always last.
 */
enum class ItemKind { LEAD, FRONT, TOP, SECTION, BRIEF, ARCHIVE, AWAY, INDEX }

/** Feed health flags shown on the Subscriptions desk. */
enum class HealthFlag { BROKEN, MOVED, QUIET, IGNORED, NOISY, LOVED }

/** Where stored article content came from. */
enum class ContentSource {
    /** Taken from the feed entry itself; may be an excerpt. */
    FEED,

    /** Extracted from the article's web page (Readability). */
    FETCHED,
}

/** User's colour theme choice. E-ink display mode overrides both light and dark. */
enum class ThemeMode { System, Light, Dark }

/** [Auto] picks [Eink] on known e-ink hardware; a manual choice always wins. */
enum class DisplayMode { Auto, Standard, Eink }
