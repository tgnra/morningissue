package net.tgnra.morningissue.data.discovery

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The week's deck, kept so that discovery is a finite set of cards rather than an endless
 * stack: the same cards come back on every opening until the week (or the catalogue) changes,
 * and once every card is decided the screen says so.
 */
@Serializable
data class SavedDeck(
    /** ISO week, such as "2026-W46". */
    val week: String,
    /** The catalogue's `built_at`, in epoch milliseconds; 0 when it has none. */
    val catalogueBuiltAt: Long,
    /** Topics picked for this deck on the cold-start screen or "Browse by topic". */
    val topics: List<Int> = emptyList(),
    val cards: List<SavedCard> = emptyList(),
)

@Serializable
data class SavedCard(
    val urlKey: String,
    val reason: String,
    val exploration: Boolean = false,
    val score: Double = 0.0,
)

/** Where the week's deck is kept. */
interface DeckStore {
    fun load(): SavedDeck?
    fun save(deck: SavedDeck)
    fun clear()

    /** For tests. */
    class InMemory : DeckStore {
        private var deck: SavedDeck? = null
        override fun load(): SavedDeck? = deck
        override fun save(deck: SavedDeck) {
            this.deck = deck
        }
        override fun clear() {
            deck = null
        }
    }

    /** A small preferences file of its own. */
    class Prefs(context: Context) : DeckStore {
        private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("discovery", Context.MODE_PRIVATE)
        private val json = Json { ignoreUnknownKeys = true }

        override fun load(): SavedDeck? =
            prefs.getString(KEY, null)?.let { runCatching { json.decodeFromString<SavedDeck>(it) }.getOrNull() }

        override fun save(deck: SavedDeck) {
            prefs.edit().putString(KEY, json.encodeToString(deck)).apply()
        }

        override fun clear() {
            prefs.edit().remove(KEY).apply()
        }

        private companion object {
            const val KEY = "deck"
        }
    }
}
