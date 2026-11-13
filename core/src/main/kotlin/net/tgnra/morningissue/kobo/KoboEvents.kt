package net.tgnra.morningissue.kobo

import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** What one Kobo event asks the phone to change; see the merge table in `docs/kobo-sync.md`. */
sealed interface KoboChange {
    /** A change to one article's `engagement` row. */
    sealed interface ToArticle : KoboChange {
        val articleId: Long
    }

    /** A change to one edition, or to its page views. */
    sealed interface ToEdition : KoboChange {
        val editionId: Long
    }

    data class Opened(override val articleId: Long, val at: Instant) : ToArticle
    data class ReadFraction(override val articleId: Long, val fraction: Float) : ToArticle
    data class Read(override val articleId: Long, val at: Instant) : ToArticle

    /** [at] null clears the Like. */
    data class Liked(override val articleId: Long, val at: Instant?) : ToArticle

    /** [at] null clears the save. */
    data class Saved(override val articleId: Long, val at: Instant?) : ToArticle
    data class PageView(override val editionId: Long, val page: Int, val millis: Long, val at: Instant) : ToEdition
    data class EditionOpened(override val editionId: Long, val at: Instant) : ToEdition
    data class EditionFinished(override val editionId: Long, val at: Instant) : ToEdition
}

object KoboEvents {
    /** An instant from the wire: ISO-8601, `Z` or an offset, fractions of a second allowed. Null if unreadable. */
    fun parseTime(text: String?): Instant? = text?.let {
        runCatching { Instant.parse(it) }.getOrNull() ?: runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull()
    }

    /**
     * What [event] asks for, or null when it is to be skipped: a type this version does not
     * know, or a field its type needs that is missing or unreadable. A skipped event still
     * counts as merged, so that the Kobo can delete it; sending it again would not make it
     * readable.
     */
    fun changeOf(event: SyncEvent): KoboChange? {
        val at = parseTime(event.at)
        val article = event.articleId
        val edition = event.editionId
        return when (event.type) {
            "opened" -> if (article != null && at != null) KoboChange.Opened(article, at) else null
            "readFraction" -> {
                val value = event.value?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
                if (article != null && value != null) KoboChange.ReadFraction(article, value.toFloat().coerceIn(0f, 1f)) else null
            }
            "read" -> if (article != null && at != null) KoboChange.Read(article, at) else null
            "liked", "saved" -> {
                val value = event.value?.takeIf { !it.isString }?.booleanOrNull
                when {
                    article == null || value == null -> null
                    value && at == null -> null
                    event.type == "liked" -> KoboChange.Liked(article, at.takeIf { value })
                    else -> KoboChange.Saved(article, at.takeIf { value })
                }
            }
            "pageView" -> {
                val page = event.page
                val ms = event.ms
                if (edition != null && page != null && page >= 1 && ms != null && ms > 0 && at != null) {
                    KoboChange.PageView(edition, page, ms, at)
                } else {
                    null
                }
            }
            "editionOpened" -> if (edition != null && at != null) KoboChange.EditionOpened(edition, at) else null
            "editionFinished" -> if (edition != null && at != null) KoboChange.EditionFinished(edition, at) else null
            else -> null
        }
    }
}
