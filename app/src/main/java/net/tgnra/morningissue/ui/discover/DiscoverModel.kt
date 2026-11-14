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
import net.tgnra.morningissue.data.discovery.Decision
import net.tgnra.morningissue.data.discovery.DeckState
import net.tgnra.morningissue.data.discovery.Suggestion
import net.tgnra.morningissue.engine.CatalogueHeadline
import net.tgnra.morningissue.sync.CandidateFetcher
import net.tgnra.morningissue.sync.CandidateResult
import net.tgnra.morningissue.sync.LiveCandidate

/** What is known of a card's feed fetched live. */
sealed interface LiveState {
    data object Loading : LiveState

    data class Fetched(val candidate: LiveCandidate) : LiveState

    /** Metered connection: nothing fetched until the reader taps Load latest. */
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

    /** The current card and the next [CandidateFetcher.PREFETCH_AHEAD], and only those. */
    private fun prefetch(state: DeckState) {
        if (state !is DeckState.Cards) return
        val ahead = shown(state).take(1 + CandidateFetcher.PREFETCH_AHEAD)
        val prefetchAllowed = container.candidates.mayPrefetch()
        ahead.forEachIndexed { index, suggestion ->
            val url = suggestion.feed.url
            if (live[url] != null && live[url] != LiveState.Metered) return@forEachIndexed
            when {
                prefetchAllowed -> fetch(url, force = false)
                // On a metered connection a feed already in the 24-hour cache still shows.
                index == 0 -> container.candidates.cached(url)?.let { live[url] = LiveState.Fetched(it) } ?: run { live[url] = LiveState.Metered }
            }
        }
    }

    /** Fetches [url]; [force] on a metered connection, since the reader asked. */
    fun fetch(url: String, force: Boolean) {
        live[url] = LiveState.Loading
        scope.launch {
            live[url] = when (val result = container.candidates.fetch(url, force)) {
                is CandidateResult.Fetched -> LiveState.Fetched(result.candidate)
                CandidateResult.SkippedMetered -> LiveState.Metered
                is CandidateResult.Unreachable -> LiveState.Unreachable
                is CandidateResult.Dead -> LiveState.Dead
            }
            // A dead card drops out, which brings another one within reach.
            deck?.let { prefetch(it) }
        }
    }

    private fun liveOf(suggestion: Suggestion): LiveCandidate? = (live[suggestion.feed.url] as? LiveState.Fetched)?.candidate

    fun pass(suggestion: Suggestion) = decide { container.discovery.pass(suggestion.feed, liveOf(suggestion)) }

    fun subscribe(suggestion: Suggestion) = decide {
        container.discovery.subscribe(suggestion.feed, liveOf(suggestion)).also { decision ->
            // Outlives the screen: the posts are for the next edition either way.
            container.appScope.launch { container.feedSyncer.syncFeed(decision.feedId) }
        }
    }

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
            container.discovery.openPost(suggestion.feed, headline, liveOf(suggestion))?.let(onOpen)
        }
    }

    /** The headlines to show: the live feed's when it was fetched, the catalogue's otherwise. */
    fun headlines(suggestion: Suggestion): List<CatalogueHeadline> =
        liveOf(suggestion)?.let { container.discovery.headlinesOf(it) }?.takeIf { it.isNotEmpty() } ?: suggestion.headlines
}

class DiscoverHolder : ViewModel() {
    private var model: DiscoverModel? = null

    fun model(container: AppContainer): DiscoverModel =
        model ?: DiscoverModel(container, viewModelScope).also {
            model = it
            it.load()
        }
}
