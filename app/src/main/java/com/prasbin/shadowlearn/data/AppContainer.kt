package com.prasbin.shadowlearn.data

import android.content.Context
import com.prasbin.shadowlearn.data.db.AcademicDao
import com.prasbin.shadowlearn.data.db.FlashcardDao
import com.prasbin.shadowlearn.data.db.SearchDao
import com.prasbin.shadowlearn.data.db.ShadowLearnDatabase
import com.prasbin.shadowlearn.data.ingest.ExtractionRepository
import com.prasbin.shadowlearn.data.search.FtsIndex
import com.prasbin.shadowlearn.data.search.RoomBackedSqlExecutor
import com.prasbin.shadowlearn.data.search.SearchRepository
import com.prasbin.shadowlearn.data.settings.SettingsRepository

/**
 * Minimal Phase 1 service locator. No DI framework yet (evaluate Hilt in
 * Phase 2 when the ingestion graph grows). Holds singletons keyed off the
 * application context so screens/ViewModels share one DB instance.
 */
object AppContainer {

    @Volatile
    private var database: ShadowLearnDatabase? = null

    @Volatile
    private var settings: SettingsRepository? = null

    @Volatile
    private var extraction: ExtractionRepository? = null

    fun database(context: Context): ShadowLearnDatabase =
        database ?: synchronized(this) {
            database ?: ShadowLearnDatabase.get(context).also { database = it }
        }

    fun dao(context: Context): AcademicDao = database(context).academicDao()

    fun extractionDao(context: Context) = database(context).extractionDao()

    /** Phase 4 extraction/FT index repository (one shared instance so the
     * Academic screen observes its state across imports).
     */
    fun extraction(context: Context): ExtractionRepository =
        extraction ?: synchronized(this) {
            extraction ?: ExtractionRepository(
                context = context,
                db = database(context),
                dao = database(context).extractionDao(),
                academicDao = database(context).academicDao(),
                fts = ExtractionRepository.ftsIndex(database(context))
            ).also { extraction = it }
        }

    @Volatile
    private var searchRepo: SearchRepository? = null

    fun searchDao(context: Context): SearchDao = database(context).searchDao()

    /** Phase 5 search orchestration over the shared FTS index + SearchDao. */
    fun search(context: Context): SearchRepository =
        searchRepo ?: synchronized(this) {
            searchRepo ?: SearchRepository(
                searchDao = database(context).searchDao(),
                fts = FtsIndex(RoomBackedSqlExecutor(database(context).openHelper.writableDatabase))
            ).also { searchRepo = it }
        }

    @Volatile
    private var quizRepo: com.prasbin.shadowlearn.data.quiz.QuizRepository? = null

    fun quizDao(context: Context) = database(context).quizDao()

    /** Phase 6 daily quiz engine over the semester chunk pool. */
    fun quiz(context: Context): com.prasbin.shadowlearn.data.quiz.QuizRepository =
        quizRepo ?: synchronized(this) {
            quizRepo ?: com.prasbin.shadowlearn.data.quiz.QuizRepository(
                quizDao = database(context).quizDao()
            ).also { quizRepo = it }
        }

    @Volatile
    private var listenerRepo: com.prasbin.shadowlearn.data.listener.ListenerRepository? = null

    fun listenerDao(context: Context) = database(context).listenerDao()

    /** Phase 7 Listener Mode: recording sessions + transcript-ready segments. */
    fun listener(context: Context): com.prasbin.shadowlearn.data.listener.ListenerRepository =
        listenerRepo ?: synchronized(this) {
            listenerRepo ?: com.prasbin.shadowlearn.data.listener.ListenerRepository(
                dao = database(context).listenerDao(),
                audioDir = java.io.File(context.filesDir, "listener")
            ).also { listenerRepo = it }
        }

    @Volatile
    private var transcriptionRepo: com.prasbin.shadowlearn.data.listener.ListenerTranscriptionRepository? = null

    /**
     * Phase 10 transcription seam: pending listener segments → READY/FAILED
     * via the installed [Transcriber]. Production default is the honest
     * [UnavailableTranscriber]; a real on-device engine drops in here.
     * UI talks to repositories only — never Room directly.
     */
    fun transcription(context: Context): com.prasbin.shadowlearn.data.listener.ListenerTranscriptionRepository =
        transcriptionRepo ?: synchronized(this) {
            transcriptionRepo ?: com.prasbin.shadowlearn.data.listener.ListenerTranscriptionRepository(
                dao = database(context).listenerDao(),
                transcriber = com.prasbin.shadowlearn.data.listener.UnavailableTranscriber()
            ).also { transcriptionRepo = it }
        }

    @Volatile
    private var flashcardRepo: com.prasbin.shadowlearn.data.cards.FlashcardRepository? = null

    fun flashcardDao(context: Context) = database(context).flashcardDao()

    /** Phase 8 flashcards + spaced review: deck generation, due queue, grading. */
    fun flashcard(context: Context): com.prasbin.shadowlearn.data.cards.FlashcardRepository =
        flashcardRepo ?: synchronized(this) {
            flashcardRepo ?: com.prasbin.shadowlearn.data.cards.FlashcardRepository(
                flashcardDao = database(context).flashcardDao()
            ).also { flashcardRepo = it }
        }

    @Volatile
    private var progressionRepo: com.prasbin.shadowlearn.data.progression.ProgressionRepository? = null

    /** Phase 9 progression: derived XP/level/streak from existing persisted activity. */
    fun progression(context: Context): com.prasbin.shadowlearn.data.progression.ProgressionRepository =
        progressionRepo ?: synchronized(this) {
            progressionRepo ?: com.prasbin.shadowlearn.data.progression.ProgressionRepository(
                quizDao = database(context).quizDao(),
                flashcardDao = database(context).flashcardDao()
            ).also { progressionRepo = it }
        }

    fun settings(context: Context): SettingsRepository =
        settings ?: synchronized(this) {
            settings ?: SettingsRepository(context.applicationContext).also { settings = it }
        }
}
