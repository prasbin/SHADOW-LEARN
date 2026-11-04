package com.prasbin.shadowlearn.ui.assistant

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.prasbin.shadowlearn.data.AppContainer
import com.prasbin.shadowlearn.data.home.Recommendation
import com.prasbin.shadowlearn.data.home.WeakArea
import com.prasbin.shadowlearn.data.intelligence.AssistantEngine
import com.prasbin.shadowlearn.data.intelligence.AssistantIntent
import com.prasbin.shadowlearn.data.intelligence.AssistantIntentType
import com.prasbin.shadowlearn.data.intelligence.ExplanationStatus
import com.prasbin.shadowlearn.data.intelligence.GroundedExplanation
import com.prasbin.shadowlearn.data.intelligence.RetrievalRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * I8 academic assistant view model — first vertical slice.
 *
 * Owns the assistant UI state machine only:
 * - input → intent (pure AssistantEngine)
 * - EXPLAIN  → reuse existing grounded retrieval + explanation path
 *              (`GroundedRetrievalRepository.explain` over I3/I4) and surface
 *              the honest failure states from the explanation status.
 * - REVISE   → reuse existing system-home aggregation (I1/I2 outputs: weak
 *              areas + grounded recommendation) and surface honest states.
 *
 * No Room access from the UI layer (repositories only), no new tables,
 * no new dependencies, no network/LLM/embeddings. Offline-first.
 */
data class AssistantResult(
    val explanation: GroundedExplanation? = null,
    val recommendation: Recommendation? = null,
    val weakAreas: List<WeakArea> = emptyList()
)

data class AssistantUiState(
    val question: String = "",
    val loading: Boolean = false,
    val unsupported: Boolean = false,
    val scopeInvalid: Boolean = false,
    val emptyCorpus: Boolean = false,
    val insufficientEvidence: Boolean = false,
    val result: AssistantResult? = null,
    val message: String? = null
)

class AssistantViewModel(context: Context) : ViewModel() {

    private val appContext = context.applicationContext
    private val assistantEngine = AssistantEngine
    private val settings = AppContainer.settings(appContext)
    private val retrieval = AppContainer.retrieval(appContext)
    private val search = AppContainer.search(appContext)
    private val systemHome = AppContainer.systemHome(appContext)

    private val _state = MutableStateFlow(AssistantUiState())
    val state: StateFlow<AssistantUiState> = _state.asStateFlow()

    fun send(rawQuestion: String) {
        viewModelScope.launch {
            val question = rawQuestion.trim()
            if (question.isEmpty()) return@launch
            _state.value = AssistantUiState(question = question, loading = true)
            val intent: AssistantIntent = assistantEngine.classify(question)
            val semesterId = currentSemesterId()
            if (semesterId == null) {
                _state.value = AssistantUiState(
                    question = question,
                    scopeInvalid = true,
                    message = "Select a semester."
                )
                return@launch
            }
            when (intent.type) {
                AssistantIntentType.UNSUPPORTED -> {
                    _state.value = AssistantUiState(
                        question = question,
                        unsupported = true,
                        message = "I couldn't understand your question. Please try rephrasing it."
                    )
                }
                AssistantIntentType.EXPLAIN -> {
                    sendExplain(semesterId, question, intent.topic ?: "")
                }
                AssistantIntentType.REVISE -> {
                    sendRevise(semesterId, question)
                }
            }
        }
    }

    private suspend fun sendExplain(
        semesterId: Long,
        question: String,
        topic: String
    ) {
        val indexedChunkCount = search.indexedChunkCount(semesterId)
        if (indexedChunkCount <= 0) {
            _state.value = AssistantUiState(
                question = question,
                emptyCorpus = true,
                message = "Your current semester has no indexed academic materials. Please import some files first."
            )
            return
        }
        val explanation = retrieval.explain(
            "Explain: $topic",
            RetrievalRequest(semesterId = semesterId, query = topic, maxChunks = 3)
        )
        _state.value = AssistantUiState(
            question = question,
            result = AssistantResult(explanation = explanation),
            insufficientEvidence = explanation.status == ExplanationStatus.INSUFFICIENT_EVIDENCE
        )
    }

    private suspend fun sendRevise(semesterId: Long, question: String) {
        val snapshot = systemHome.snapshot(semesterId, System.currentTimeMillis())
        // Honest insufficient-activity state only when there is material but no
        // study evidence at all. Due/resume/transcript guidance is meaningful
        // on its own and must not be mislabelled as "not enough activity".
        val noStudyEvidence = snapshot.weakAreas.isEmpty() &&
            snapshot.recommendation.kind == com.prasbin.shadowlearn.data.intelligence.RecommendationKind.MATERIAL
        _state.value = AssistantUiState(
            question = question,
            result = AssistantResult(
                recommendation = snapshot.recommendation,
                weakAreas = snapshot.weakAreas
            ),
            insufficientEvidence = noStudyEvidence,
            message = if (noStudyEvidence) {
                "I need more study activity (quizzes or flashcard reviews) to identify your revision needs."
            } else {
                null
            }
        )
    }

    private suspend fun currentSemesterId(): Long? = settings.currentSemesterId.first()

    companion object {
        fun factory(context: Context) = viewModelFactory {
            initializer { AssistantViewModel(context.applicationContext) }
        }
    }
}
