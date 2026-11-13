package net.tgnra.morningissue.ui.discover

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.tgnra.morningissue.AppContainer
import net.tgnra.morningissue.client.DeckState
import net.tgnra.morningissue.client.Decision
import net.tgnra.morningissue.client.LiveHeadlines
import net.tgnra.morningissue.client.Suggestion
import net.tgnra.morningissue.engine.CatalogueHeadline

/** What is known of a card's feed fetched live (by the server). */
sealed interface LiveState {
    data object Loading : LiveState

    data class Fetched(val headlines: List<CatalogueHeadline>) : LiveState

    /** Not fetched until the reader taps Load latest. The server fetches, so this is no longer shown. */
    data object Metered : LiveState

    data object Unreachable : LiveState

    data object Dead : LiveState
}

/**
 * The discovery screen's state, kept in a view model so a rotation keeps the deck, the live
 * fetches and the Undo line. Cards are decided by buttons only (Pass, Subscribe), never by a
 * gesture; each decision can be undone until the next one.
 */
class DiscoverModel(private val container: AppContainer, private val scope: CoroutineScope) {
    var deck by mutableStateOf<DeckState?>(null)
        private set

    /** The topic picker opened from Browse by topic (a cold start shows it without asking). */
    var browsingTopics by mutableStateOf(false)

    var lastDecision by mutableStateOf<Decision?>(null)
        private set
    var undoLosesPosts by mutableStateOf(false)
        private set

    /** Live fetches by feed URL. */
    val live = mutableStateMapOf<String, LiveState>()

    private var loadJob: Job? = null
    private var busy = false

    fun load(topics: Set<Int>? = null) {
        loadJob?.cancel()
        loadJob = scope.launch {
            val state = container.discovery.deck(topics)
            deck = state
            if (topics != null) browsingTopics = false
            prefetch(state)
        }
    }

    /** The cards to show: the undecided ones whose feed is not known to be broken. */
    fun shown(state: DeckState.Cards): List<Suggestion> =
        state.suggestions.filter { live[it.feed.url] != LiveState.Dead }

    /** The current card and the next [PREFETCH_AHEAD], and only those. */
    private fun prefetch(state: DeckState) {
        if (state !is DeckState.Cards) return
        shown(state).take(1 + PREFETCH_AHEAD).forEach { suggestion ->
            if (live[suggestion.feed.url] == null) fetch(suggestion.feed.url, force = false)
        }
    }

    /** Asks the server for [url]'s latest posts. */
    @Suppress("UNUSED_PARAMETER")
    fun fetch(url: String, force: Boolean) {
        live[url] = LiveState.Loading
        scope.launch {
            live[url] = when (val result = container.discovery.live(url)) {
                is LiveHeadlines.Fetched -> LiveState.Fetched(result.headlines)
                LiveHeadlines.Unreachable -> LiveState.Unreachable
                LiveHeadlines.Dead -> LiveState.Dead
            }
            // A dead card drops out, which brings another one within reach.
            deck?.let { prefetch(it) }
        }
    }

    fun pass(suggestion: Suggestion) = decide { container.discovery.pass(suggestion.feed) }

    fun subscribe(suggestion: Suggestion) = decide { container.discovery.subscribe(suggestion.feed) }

    private fun decide(action: suspend () -> Decision) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                lastDecision = action()
                undoLosesPosts = false
                val state = container.discovery.deck()
                deck = state
                prefetch(state)
            } finally {
                busy = false
            }
        }
    }

    fun undo() {
        val decision = lastDecision ?: return
        if (busy) return
        busy = true
        scope.launch {
            try {
                container.discovery.undo(decision)
                lastDecision = null
                val state = container.discovery.deck()
                deck = state
                prefetch(state)
            } finally {
                busy = false
            }
        }
    }

    /** Checked when the Undo line shows, so it can warn that fetched posts would go too. */
    fun checkUndo() {
        val decision = lastDecision ?: return
        scope.launch { undoLosesPosts = container.discovery.undoLosesPosts(decision) }
    }

    /** Stores the headline as a post of a discovered feed and opens it in the reader. */
    fun open(suggestion: Suggestion, headline: CatalogueHeadline, onOpen: (Long) -> Unit) {
        scope.launch {
            container.discovery.openPost(suggestion.feed, headline)?.let(onOpen)
        }
    }

    /** The headlines to show: the live feed's when it was fetched, the deck's otherwise. */
    fun headlines(suggestion: Suggestion): List<CatalogueHeadline> =
        (live[suggestion.feed.url] as? LiveState.Fetched)?.headlines?.take(3)?.takeIf { it.isNotEmpty() } ?: suggestion.headlines

    private companion object {
        /** Cards beyond the current one whose latest posts are asked for ahead. */
        const val PREFETCH_AHEAD = 2
    }
}

class DiscoverHolder : ViewModel() {
    private var model: DiscoverModel? = null

    fun model(container: AppContainer): DiscoverModel =
        model ?: DiscoverModel(container, viewModelScope).also {
            model = it
            it.load()
        }
}
