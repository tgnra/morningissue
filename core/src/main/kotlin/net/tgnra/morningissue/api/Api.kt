package net.tgnra.morningissue.api

import kotlinx.serialization.json.Json

/*
 * The self-hosted server's HTTP API, `/api/v1` (`docs/server-design.md`, section 4): the
 * contract between the server, the Android client, the server's tests and, for the endpoints
 * it calls, the Kobo. Every request and response body is a `@Serializable` class in this
 * package; the Ktor routes and the OkHttp client both use them.
 *
 * Conventions: JSON is UTF-8; instants are ISO-8601 in UTC (`2026-11-11T06:30:00Z`), dates
 * `yyyy-MM-dd`, local times `HH:mm`; enums are their names. Unknown fields are ignored on both
 * sides. Errors are [ApiErrorBody].
 */

/** The API version in `/health` and in the base path. */
const val API_VERSION = 1

/**
 * Carried by every gated request (`GET /sync/manifest`, `GET /editions/{id}/bundle`,
 * `GET /saved`, `POST /kobo/images`): the highest event `seq` the client has assigned and not
 * seen acknowledged, or 0 if it holds none. The server answers `409 events_pending` while the
 * device's `acked_seq` is lower.
 */
const val EVENTS_THROUGH_HEADER = "X-Events-Through"

/** `POST /import`'s retry key: a UUID the client keeps until the import is done. */
const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"

/** The manifest's `protocol` for the server. */
const val SERVER_PROTOCOL = "server/1"

/** Limits both sides keep to (section 4.11). */
object ApiLimits {
    /** Request bodies, except `/import`, `/opml` and `/kobo/images`. */
    const val MAX_BODY_BYTES = 1L * 1024 * 1024

    /** Events in one `POST /sync/events`. */
    const val MAX_EVENTS_PER_REQUEST = 500

    /** URLs in one `POST /kobo/images`. */
    const val MAX_IMAGE_URLS = 100

    /** An OPML upload. */
    const val MAX_OPML_BYTES = 5L * 1024 * 1024

    /** Editions the manifest may be asked for, and the default. */
    const val MAX_MANIFEST_EDITIONS = 30
    const val DEFAULT_MANIFEST_EDITIONS = 3

    /** Back issues per page of `GET /editions`. */
    const val DEFAULT_EDITIONS_PAGE = 30

    /** A new password is at least this long. */
    const val MIN_PASSWORD_LENGTH = 10

    /** A Kobo's picture width (the short side of its screen) is held to this range. */
    const val MIN_KOBO_WIDTH = 160
    const val MAX_KOBO_WIDTH = 2048

    /** Clients give up on the events gate after this many upload-and-retry rounds. */
    const val MAX_GATE_ROUNDS = 3
}

/** The JSON both sides read and write: lenient on input, every field written on output. */
object ApiJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
        // An enum value this version does not know reads as the field's default, where it has one.
        coerceInputValues = true
    }
}

/**
 * Paths under [BASE], as Ktor route templates (`{id}` is a path parameter). Clients fill the
 * templates with the functions below.
 */
object ApiPaths {
    const val BASE = "/api/v1"

    // 4.1 Auth and account
    const val HEALTH = "/health"
    const val LOGIN = "/auth/login"
    const val PASSWORD = "/auth/password"
    const val LOGOUT = "/auth/logout"
    const val ACCOUNT = "/account"

    // 4.2 Events and the manifest
    const val SYNC_EVENTS = "/sync/events"
    const val SYNC_MANIFEST = "/sync/manifest"

    // 4.3 Editions, saved shelf, stand, print
    const val EDITIONS = "/editions"
    const val EDITION_BUNDLE = "/editions/{id}/bundle"
    const val EDITION_DESK_NOTICE = "/editions/{id}/desk-notice"
    const val EDITIONS_EXTRA = "/editions/extra"
    const val SAVED = "/saved"
    const val ENGAGEMENT = "/engagement"
    const val STAND = "/stand"
    const val PRINT = "/print"
    const val JOB = "/jobs/{id}"

    // 4.4 Settings
    const val SETTINGS = "/settings"
    const val SETTINGS_RELEASE = "/settings/release"

    // 4.5 Feeds, OPML, health
    const val FEEDS = "/feeds"
    const val FEEDS_DISCOVER = "/feeds/discover"
    const val FEED = "/feeds/{id}"
    const val FEED_ARTICLES = "/feeds/{id}/articles"
    const val FEEDS_HEALTH = "/feeds/health"
    const val FEED_HEALTH_DISMISS = "/feeds/{id}/health/dismiss"
    const val FEED_HEALTH_ACKNOWLEDGE = "/feeds/{id}/health/acknowledge"
    const val FEED_HEALTH_RETRY = "/feeds/{id}/health/retry"
    const val OPML = "/opml"

    // 4.6 Articles and search
    const val ARTICLES = "/articles"
    const val ARTICLE = "/articles/{id}"
    const val ARTICLE_FULLTEXT = "/articles/{id}/fulltext"
    const val SEARCH = "/search"

    // 4.7 Discovery
    const val DISCOVERY_DECK = "/discovery/deck"
    const val DISCOVERY_TOPICS = "/discovery/topics"
    const val DISCOVERY_LIVE = "/discovery/live"
    const val DISCOVERY_VERDICTS = "/discovery/verdicts"
    const val DISCOVERY_PASSED = "/discovery/passed"
    const val DISCOVERY_OPEN = "/discovery/open"

    // 4.8 Devices and Kobo pairing
    const val DEVICES = "/devices"
    const val DEVICES_KOBO = "/devices/kobo"
    const val DEVICE = "/devices/{id}"
    const val KOBO_HELLO = "/kobo/hello"
    const val KOBO_IMAGES = "/kobo/images"
    const val KOBO_CONFIG = "/kobo/config.json"

    // 4.9 Legacy import
    const val IMPORT = "/import"

    /** The path parameter in the templates above. */
    const val ID = "id"

    fun editionBundle(id: Long) = "/editions/$id/bundle"
    fun editionDeskNotice(id: Long) = "/editions/$id/desk-notice"
    fun job(id: String) = "/jobs/$id"
    fun feed(id: Long) = "/feeds/$id"
    fun feedArticles(id: Long) = "/feeds/$id/articles"
    fun feedHealthDismiss(id: Long) = "/feeds/$id/health/dismiss"
    fun feedHealthAcknowledge(id: Long) = "/feeds/$id/health/acknowledge"
    fun feedHealthRetry(id: Long) = "/feeds/$id/health/retry"
    fun article(id: Long) = "/articles/$id"
    fun articleFullText(id: Long) = "/articles/$id/fulltext"
    fun device(id: String) = "/devices/$id"
}

/** Query parameter names and their fixed values. */
object ApiQuery {
    /** `GET /sync/manifest`: how many editions (1..30, default 3). */
    const val EDITIONS = "editions"

    /** `GET /editions`, `/feeds/{id}/articles`, `/articles`: the page boundary (an edition number, or an ISO instant). */
    const val BEFORE = "before"
    const val LIMIT = "limit"
    const val OFFSET = "offset"

    /** `GET /engagement`: the cursor from the previous answer, 0 at first. */
    const val SINCE = "since"

    /** `GET /articles`: which shelf. */
    const val SHELF = "shelf"
    const val SHELF_SAVED = "saved"
    const val SHELF_LIKED = "liked"

    /** `GET /search`. */
    const val Q = "q"
    const val WHEN = "when"
    const val SAVED = "saved"
    const val LIKED = "liked"
    const val TAG_ID = "tagId"
    const val FEED_ID = "feedId"

    /** `GET /discovery/live`, `DELETE /discovery/verdicts`. */
    const val URL = "url"

    /** `POST /import`. */
    const val MODE = "mode"
    const val MODE_MERGE = "merge"
    const val MODE_REPLACE = "replace"
}

/** Content types beyond JSON. */
object ApiContentTypes {
    const val OPML = "text/x-opml"
    const val ZIP = "application/zip"
}
