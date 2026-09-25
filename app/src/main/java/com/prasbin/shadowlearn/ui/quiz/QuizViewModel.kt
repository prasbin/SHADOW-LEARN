package com.prasbin.shadowlearn.ui.quiz

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.prasbin.shadowlearn.data.AppContainer
import com.prasbin.shadowlearn.data.quiz.ActiveQuiz
import com.prasbin.shadowlearn.data.quiz.QuizRepository
import com.prasbin.shadowlearn.data.quiz.QuizResults
import com.prasbin.shadowlearn.data.quiz.QuizSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.zip
import kotlinx.coroutines.launch

/** Distinct screen states of the Quiz tab (see QuizScreen). */
enum class QuizUiKind { LOADING, NO_SEMESTER, NO_INDEXED, UNAVAILABLE, IDLE, QUESTION, RESULTS, ERROR }

/** After-the-fact judgement for the current question (derived from state). */
data class QuizFeedback(
    val isCorrect: Boolean,
    val correctAnswer: String,
    val excerpt: String
)

data class QuizUiState(
    val kind: QuizUiKind = QuizUiKind.LOADING,
    val yearName: String? = null,
    val semesterName: String? = null,
    val semesterId: Long? = null,
    val indexedChunkCount: Int = 0,
    val summary: QuizSummary? = null,
    val quiz: ActiveQuiz? = null,
    val current: Int = 0,
    val selectedLength: Int = QuizViewModel.DEFAULT_LENGTH,
    val results: QuizResults? = null,
    val error: String? = null
) {
    val feedback: QuizFeedback?
        get() {
            val q = quiz ?: return null
            val question = q.questions.getOrNull(current) ?: return null
            val correct = question.isCorrect ?: return null
            return QuizFeedback(
                isCorrect = correct,
                correctAnswer = question.correctAnswer,
                excerpt = question.source.excerpt
            )
        }
}

/**
 * Quiz tab state — a snapshot machine over [QuizRepository] (quiz generation
 * + scoring are persisted, so nothing lives only in memory). The scope
 * (current semester from DataStore) is watched; changing it resets the tab.
 * All actions mutate SQLite, then rebuild the local state.
 */
class QuizViewModel(context: Context) : ViewModel() {

    private val app = context.applicationContext
    private val dao = AppContainer.dao(app)
    private val settings = AppContainer.settings(app)
    private val repo: QuizRepository = AppContainer.quiz(app)

    private val _state = MutableStateFlow(QuizUiState())
    val state: StateFlow<QuizUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settings.currentYearId
                .zip(settings.currentSemesterId) { year, sem -> year to sem }
                .distinctUntilChanged()
                .collect { (yearId, semesterId) -> reload(yearId, semesterId) }
        }
    }

    private suspend fun reload(yearId: Long?, semesterId: Long?) {
        _state.value = if (semesterId == null) {
            QuizUiState(kind = QuizUiKind.NO_SEMESTER)
        } else {
            val base = QuizUiState(
                semesterId = semesterId,
                yearName = resolveYearName(yearId),
                semesterName = resolveSemesterName(yearId, semesterId)
            )
            try {
                base.copy(indexedChunkCount = repo.indexedChunkCount(semesterId))
            } catch (_: Exception) {
                base.copy(kind = QuizUiKind.ERROR, error = "Could not read the quiz index.")
            }
        }.let {
            when (it.kind) {
                QuizUiKind.NO_SEMESTER, QuizUiKind.ERROR -> it
                else -> {
                    if (it.indexedChunkCount == 0) it.copy(kind = QuizUiKind.NO_INDEXED)
                    else finishIdleLoad(it)
                }
            }
        }
    }

    private suspend fun finishIdleLoad(base: QuizUiState): QuizUiState {
        val active = try {
            repo.activeQuiz()
        } catch (_: Exception) {
            null
        }
        val summary = try {
            repo.summary()
        } catch (_: Exception) {
            null
        }
        return if (active != null) {
            base.copy(kind = QuizUiKind.QUESTION, summary = summary, quiz = active, current = active.currentIndex)
        } else {
            base.copy(kind = QuizUiKind.IDLE, summary = summary)
        }
    }

    fun selectLength(length: Int) {
        _state.updateState { it.copy(selectedLength = length) }
    }

    fun start() {
        val semesterId = _state.value.semesterId ?: return
        val length = _state.value.selectedLength
        viewModelScope.launch {
            runCatching { repo.launch(semesterId, length) }
                .onSuccess { quiz ->
                    _state.updateState {
                        if (quiz == null) it.copy(kind = QuizUiKind.UNAVAILABLE)
                        else it.copy(kind = QuizUiKind.QUESTION, quiz = quiz, current = quiz.currentIndex, results = null)
                    }
                }
                .onFailure { e ->
                    _state.updateState { it.copy(kind = QuizUiKind.ERROR, error = e.message ?: "Could not generate a quiz.") }
                }
        }
    }

    /** Records the chosen option for the current question. */
    fun answer(option: String) {
        val state = _state.value
        val quiz = state.quiz ?: return
        val question = quiz.questions.getOrNull(state.current) ?: return
        if (question.isCorrect != null) return
        viewModelScope.launch {
            runCatching { repo.answer(question.id, option) }
            _state.updateState { st ->
                val qz = st.quiz ?: return@updateState st
                st.copy(
                    quiz = qz.copy(
                        questions = qz.questions.map { q ->
                            if (q.id == question.id) q.copy(userAnswer = option, isCorrect = option == q.correctAnswer) else q
                        }
                    )
                )
            }
        }
    }

    /** Advances past the answered question, or completes the session. */
    fun next() {
        val state = _state.value
        val quiz = state.quiz ?: return
        val question = quiz.questions.getOrNull(state.current) ?: return
        if (question.isCorrect == null) return
        if (state.current + 1 < quiz.questions.size) {
            _state.updateState { it.copy(current = it.current + 1) }
        } else {
            viewModelScope.launch {
                runCatching { repo.complete(quiz.sessionId) }
                    .onSuccess { results ->
                        val summary = runCatching { repo.summary() }.getOrNull()
                        _state.updateState { it.copy(kind = QuizUiKind.RESULTS, quiz = null, results = results, summary = summary) }
                    }
                    .onFailure { e ->
                        _state.updateState { it.copy(kind = QuizUiKind.ERROR, error = e.message ?: "Could not save results.") }
                    }
            }
        }
    }

    /** Returns to the idle screen after a results run. */
    fun newQuiz() {
        _state.updateState { it.copy(kind = QuizUiKind.IDLE, quiz = null, results = null, current = 0) }
    }

    private suspend fun resolveYearName(yearId: Long?): String? {
        if (yearId == null) return null
        return runCatching { dao.getYears().firstOrNull { it.id == yearId }?.name }.getOrNull()
    }

    private suspend fun resolveSemesterName(yearId: Long?, semesterId: Long?): String? {
        if (semesterId == null || yearId == null) return null
        return runCatching { dao.getSemesters(yearId).firstOrNull { it.id == semesterId }?.name }.getOrNull()
    }

    private fun MutableStateFlow<QuizUiState>.updateState(transform: (QuizUiState) -> QuizUiState) {
        value = transform(value)
    }

    companion object {
        const val DEFAULT_LENGTH = 10
        val LENGTH_OPTIONS = listOf(5, 10, 15)

        fun factory(context: Context) = viewModelFactory {
            initializer { QuizViewModel(context.applicationContext) }
        }
    }
}