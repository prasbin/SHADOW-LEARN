package com.prasbin.shadowlearn.ui.cards

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.prasbin.shadowlearn.data.AppContainer
import com.prasbin.shadowlearn.data.cards.FlashcardRepository
import com.prasbin.shadowlearn.data.cards.Rating
import com.prasbin.shadowlearn.data.db.Flashcard
import com.prasbin.shadowlearn.data.db.ReviewSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Distinct screen states of the Cards tab (see FlashcardScreen). */
enum class FlashcardUiKind {
    LOADING,
    NO_SEMESTER,
    NO_DECKS,
    IDLE,
    REVIEW,
    RESULTS,
    ERROR
}

data class FlashcardUiState(
    val kind: FlashcardUiKind = FlashcardUiKind.LOADING,
    val yearName: String? = null,
    val semesterName: String? = null,
    val semesterId: Long? = null,
    val deckId: Long? = null,
    val deckTitle: String = "",
    val totalCards: Int = 0,
    val dueCount: Int = 0,
    val suspendedCount: Int = 0,
    /** Cards derived from READY listener transcripts (Phase 11 provenance). */
    val lectureCardCount: Int = 0,
    /** Cards in the current due queue. */
    val queue: List<Flashcard> = emptyList(),
    /** Index of the card currently being reviewed. */
    val currentIndex: Int = 0,
    /** The front text of the card at [currentIndex] (before reveal). */
    val currentFront: String = "",
    /** The back text of the card at [currentIndex] (after reveal). */
    val currentBack: String = "",
    val currentSourceLabel: String = "",
    /** Whether the current card has been revealed. */
    val revealed: Boolean = false,
    val reviewSessionId: Long? = null,
    val reviewedCount: Int = 0,
    val retainedCount: Int = 0,
    /** Whether an unfinished (IN_PROGRESS) session can be resumed. */
    val hasInProgress: Boolean = false,
    val results: ReviewSession? = null,
    val error: String? = null
) {
    val currentCard: Flashcard?
        get() = queue.getOrNull(currentIndex)
    val isLastCard: Boolean
        get() = currentIndex >= queue.size - 1
}

/**
 * Cards tab state — a snapshot machine over [FlashcardRepository].
 * The scope (current semester from DataStore) is watched; changing
 * it resets the tab. Deck generation, review, and grading are
 * persisted in SQLite so a session survives process death.
 */
class FlashcardViewModel(context: Context) : ViewModel() {

    private val app = context.applicationContext
    private val repo: FlashcardRepository = AppContainer.flashcard(app)

    private val _state = MutableStateFlow(FlashcardUiState())
    val state: StateFlow<FlashcardUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            AppContainer.settings(app).currentSemesterId
                .distinctUntilChanged()
                .collect { semesterId ->
                    if (semesterId == null) {
                        _state.update { it.copy(kind = FlashcardUiKind.NO_SEMESTER) }
                    } else {
                        loadDeck(semesterId)
                    }
                }
        }
    }

    /** Loads or builds the deck for [semesterId]. */
    fun loadDeck(semesterId: Long) {
        viewModelScope.launch {
            _state.update { it.copy(kind = FlashcardUiKind.LOADING) }
            runCatching {
                val yearId = AppContainer.settings(app).currentYearId.first()
                val yearName = runCatching { AppContainer.dao(app).getYears().firstOrNull { it.id == yearId }?.name }.getOrNull()
                val semesterName = runCatching { AppContainer.dao(app).getSemesters(yearId ?: 0).firstOrNull { it.id == semesterId }?.name }.getOrNull()

                // Always regenerate so newly imported materials, quiz
                // mistakes, and READY transcripts appear without a restart.
                val deck = repo.buildDeck(semesterId)
                val now = System.currentTimeMillis()
                val totalCards = repo.cardCount(deck.id)
                if (totalCards == 0) {
                    _state.update {
                        it.copy(
                            kind = FlashcardUiKind.NO_DECKS,
                            yearName = yearName,
                            semesterName = semesterName,
                            semesterId = semesterId,
                            deckId = deck.id
                        )
                    }
                    return@launch
                }
                val dueCount = repo.dueCount(deck.id, now)
                val suspendedCount = repo.suspendedCount(deck.id)
                val lectureCardCount = repo.lectureCardCount(deck.id)
                val queue = repo.dueCards(deck.id, now, REVIEW_LIMIT)
                // Never enter REVIEW without a session id: grading against a
                // null session would silently drop every review.
                val resumable = repo.latestInProgress(deck.id) != null

                _state.update {
                    it.copy(
                        kind = FlashcardUiKind.IDLE,
                        yearName = yearName,
                        semesterName = semesterName,
                        semesterId = semesterId,
                        deckId = deck.id,
                        deckTitle = deck.title,
                        totalCards = totalCards,
                        dueCount = dueCount,
                        suspendedCount = suspendedCount,
                        lectureCardCount = lectureCardCount,
                        queue = queue,
                        currentIndex = 0,
                        hasInProgress = resumable,
                        reviewSessionId = null,
                        reviewedCount = 0,
                        retainedCount = 0,
                        revealed = false
                    )
                }
            }.onFailure { e ->
                _state.update { it.copy(kind = FlashcardUiKind.ERROR, error = e.message ?: "Could not load the deck.") }
            }
        }
    }

    /**
     * Rebuilds the deck for the current semester. Used when returning to
     * Cards after quiz mistakes or READY transcripts produced new cards
     * elsewhere. No-op unless IDLE or NO_DECKS, so an in-progress review
     * or a fresh result is never clobbered by a tab revisit.
     */
    fun refreshDeck() {
        val st = _state.value
        val semId = st.semesterId ?: return
        if (st.kind != FlashcardUiKind.IDLE && st.kind != FlashcardUiKind.NO_DECKS) return
        loadDeck(semId)
    }

    /** Starts a review session for the current deck. */    fun startReview() {
        val state = _state.value
        if (state.deckId == null || state.queue.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                // Reuse an unfinished session instead of leaking a new one.
                val sessionId = repo.latestInProgress(state.deckId!!)?.id
                    ?: repo.startReview(state.deckId!!)
                val queue = repo.dueCards(state.deckId!!, System.currentTimeMillis(), REVIEW_LIMIT)
                val first = queue.firstOrNull()
                _state.update {
                    it.copy(
                        kind = FlashcardUiKind.REVIEW,
                        reviewSessionId = sessionId,
                        queue = queue,
                        currentIndex = 0,
                        revealed = false,
                        currentFront = first?.front ?: "",
                        currentBack = "",
                        currentSourceLabel = first?.sourceLabel ?: "",
                        reviewedCount = 0,
                        retainedCount = 0
                    )
                }
            }.onFailure { e ->
                _state.update { it.copy(kind = FlashcardUiKind.ERROR, error = e.message ?: "Could not start review.") }
            }
        }
    }

    /** Reveals the back of the current card. */
    fun reveal() {
        _state.update { state ->
            val card = state.queue.getOrNull(state.currentIndex) ?: return@update state
            state.copy(revealed = true, currentBack = card.back, currentSourceLabel = card.sourceLabel)
        }
    }

    /**
     * Rates the current card with [rating] and advances to the next.
     * If this is the last card, completes the review.
     */
    fun gradeCurrent(rating: Rating) {
        val state = _state.value
        val card = state.queue.getOrNull(state.currentIndex) ?: return
        if (state.reviewSessionId == null) return
        viewModelScope.launch {
            runCatching {
                val now = System.currentTimeMillis()
                repo.grade(card, rating, state.reviewSessionId!!, now)
                val retainedDelta = if (rating == Rating.AGAIN) 0 else 1
                val newState = state.copy(
                    currentIndex = state.currentIndex + 1,
                    revealed = false,
                    reviewedCount = state.reviewedCount + 1,
                    retainedCount = state.retainedCount + retainedDelta
                )
                if (newState.currentIndex >= newState.queue.size) {
                    completeReview(newState.reviewedCount, newState.retainedCount, now)
                } else {
                    val nextCard = newState.queue.getOrNull(newState.currentIndex)
                    _state.update {
                        newState.copy(
                            currentFront = nextCard?.front ?: "",
                            currentBack = "",
                            currentSourceLabel = nextCard?.sourceLabel ?: ""
                        )
                    }
                }
            }.onFailure { e ->
                _state.update { it.copy(kind = FlashcardUiKind.ERROR, error = e.message ?: "Could not grade the card.") }
            }
        }
    }

    /** Completes the review session and shows results. */
    private suspend fun completeReview(reviewed: Int, retained: Int, now: Long) {
        val state = _state.value
        val sessionId = state.reviewSessionId ?: return
        repo.completeSession(sessionId, reviewed, retained, now)
        _state.update {
            it.copy(
                kind = FlashcardUiKind.RESULTS,
                reviewSessionId = null,
                hasInProgress = false,
                reviewedCount = reviewed,
                retainedCount = retained,
                results = ReviewSession(
                    id = sessionId,
                    deckId = state.deckId ?: 0,
                    reviewedCount = reviewed,
                    retainedCount = retained,
                    status = ReviewSession.STATUS_COMPLETED
                )
            )
        }
    }

    /**
     * Resumes an interrupted review session: progress is read back from
     * persisted events, and cards already graded in this session are
     * dropped from the queue.
     */
    fun resumeReview() {
        val state = _state.value
        if (state.deckId == null) return
        viewModelScope.launch {
            runCatching {
                val session = repo.latestInProgress(state.deckId!!)
                if (session == null) {
                    loadDeck(state.semesterId ?: return@launch)
                    return@launch
                }
                val now = System.currentTimeMillis()
                val events = repo.reviewEvents(session.id)
                val gradedIds = events.map { it.flashcardId }.toSet()
                val queue = repo.dueCards(state.deckId!!, now, REVIEW_LIMIT)
                    .filter { it.id !in gradedIds }
                val reviewedCount = events.size
                val retainedCount = events.count { it.retained }
                if (queue.isEmpty()) {
                    // Everything left was already graded — close it out.
                    repo.completeSession(session.id, reviewedCount, retainedCount, now)
                    loadDeck(state.semesterId ?: return@launch)
                    return@launch
                }
                _state.update {
                    it.copy(
                        kind = FlashcardUiKind.REVIEW,
                        reviewSessionId = session.id,
                        queue = queue,
                        currentIndex = 0,
                        revealed = false,
                        currentFront = queue.first().front,
                        currentBack = "",
                        currentSourceLabel = queue.first().sourceLabel,
                        reviewedCount = reviewedCount,
                        retainedCount = retainedCount
                    )
                }
            }.onFailure { e ->
                _state.update { it.copy(kind = FlashcardUiKind.ERROR, error = e.message ?: "Could not resume review.") }
            }
        }
    }

    /**
     * Suspends the current card and drops it from the live queue without
     * leaving the review session.
     */
    fun toggleSuspend() {
        val state = _state.value
        val card = state.queue.getOrNull(state.currentIndex) ?: return
        if (card.suspended) return
        viewModelScope.launch {
            runCatching {
                repo.setSuspended(card.id, true, System.currentTimeMillis())
                val newQueue = state.queue.filterNot { it.id == card.id }
                if (newQueue.isEmpty()) {
                    loadDeck(state.semesterId ?: return@launch)
                    return@launch
                }
                val idx = state.currentIndex.coerceAtMost(newQueue.size - 1)
                val next = newQueue[idx]
                _state.update {
                    it.copy(
                        queue = newQueue,
                        currentIndex = idx,
                        revealed = false,
                        currentFront = next.front,
                        currentBack = "",
                        currentSourceLabel = next.sourceLabel,
                        suspendedCount = it.suspendedCount + 1,
                        dueCount = (it.dueCount - 1).coerceAtLeast(0)
                    )
                }
            }.onFailure { e ->
                _state.update { it.copy(kind = FlashcardUiKind.ERROR, error = e.message ?: "Could not suspend the card.") }
            }
        }
    }

    fun newReview() {
        val semesterId = _state.value.semesterId
        if (semesterId != null) loadDeck(semesterId)
        else _state.update { it.copy(kind = FlashcardUiKind.IDLE, queue = emptyList(), currentIndex = 0, revealed = false, reviewSessionId = null) }
    }

    companion object {
        const val REVIEW_LIMIT = 50

        fun factory(context: Context) = viewModelFactory {
            initializer { FlashcardViewModel(context.applicationContext) }
        }
    }
}
