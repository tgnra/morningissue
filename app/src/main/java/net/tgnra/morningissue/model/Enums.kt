package net.tgnra.morningissue.model

/** How often a feed posts. Thresholds live in `RankingConfig`. */
enum class CadenceTier { FIREHOSE, DAILY, WEEKLY, RARE }

/**
 * Whether the reader subscribes to a feed. A [DISCOVERED] feed exists only to hold posts the
 * reader opened or saved from feed discovery without subscribing: it is never synced, never
 * printed in an edition (except the weekly "From your reading list" slot) and never learned
 * from until it is subscribed. Every query that drives editions, sync, health or learning
 * reads [SUBSCRIBED] feeds only; `SubscribedOnlyQueryTest` enforces it.
 */
enum class FeedStatus { SUBSCRIBED, DISCOVERED }

/**
 * What the reader decided about a suggested feed, kept in `discovery_verdict` by the feed's
 * URL key. Any verdict keeps the feed out of suggestions for good: [PASSED] on a card,
 * [SUBSCRIBED] from discovery, [DROPPED] by removing the feed in Sources.
 */
enum class DiscoveryVerdict { PASSED, SUBSCRIBED, DROPPED }

/** The per-feed 5-step slider that overrides learned affinity. [AUTO] is the default. */
enum class SliderStep {
    RARELY, LESS, AUTO, MORE, FAVOURITE;

    /** One step towards [FAVOURITE] ("Show more"); stays put at the end. */
    fun stepUp(): SliderStep = entries[(ordinal + 1).coerceAtMost(entries.lastIndex)]

    /** One step towards [RARELY] ("Show less"); stays put at the end. */
    fun stepDown(): SliderStep = entries[(ordinal - 1).coerceAtLeast(0)]
}

/**
 * [MORNING] is the daily paper. [EVENING] is the optional small paper of Firehose and Daily
 * feeds. [EXTRA] is printed on request from chosen feeds, outside the schedule; there is at
 * most one per calendar date.
 */
enum class EditionKind { MORNING, EVENING, EXTRA }

enum class EditionLength { SHORT, STANDARD, LONG }

/**
 * Where a story sits in an edition. This is also the contract between the edition builder and
 * the pages the UI renders:
 *
 * - every `edition_item` row carries a 1-based `page` and a `slot` (order within the page);
 * - all items on one page share a page type: [LEAD] and [FRONT] together form the front page
 *   (exactly one [LEAD]); [TOP], [SECTION], [BRIEF], [ARCHIVE], [AWAY] and [READING_LIST] each
 *   get pages of their own kind; [SECTION] items on a page share the same `section` name;
 * - [READING_LIST] ("From your reading list") is a post saved from a feed the reader does not
 *   subscribe to; it is placed apart from the ranking and never counts as a new post;
 * - [INDEX] items ("Also in your feeds") belong to the back page, which is always last.
 */
enum class ItemKind { LEAD, FRONT, TOP, SECTION, BRIEF, ARCHIVE, AWAY, INDEX, READING_LIST }

/** Feed health flags shown on the Subscriptions desk. */
enum class HealthFlag { BROKEN, MOVED, QUIET, IGNORED, NOISY, LOVED }

/** Where stored article content came from. */
enum class ContentSource {
    /** Taken from the feed entry itself; may be an excerpt. */
    FEED,

    /** Extracted from the article's web page (Readability). */
    FETCHED,
}

/**
 * What a post carries besides its text: a podcast episode, a video file or some other download.
 * Image attachments are not counted; they are the post's lead image.
 */
enum class EnclosureKind { NONE, AUDIO, VIDEO, OTHER }

/** User's colour theme choice. E-ink display mode overrides both light and dark. */
enum class ThemeMode { System, Light, Dark }

/** [Auto] picks [Eink] on known e-ink hardware; a manual choice always wins. */
enum class DisplayMode { Auto, Standard, Eink }
