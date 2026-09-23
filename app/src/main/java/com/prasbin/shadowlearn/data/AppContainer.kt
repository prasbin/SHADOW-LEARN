package com.prasbin.shadowlearn.data

import android.content.Context
import com.prasbin.shadowlearn.data.db.AcademicDao
import com.prasbin.shadowlearn.data.db.ShadowLearnDatabase
import com.prasbin.shadowlearn.data.ingest.ExtractionRepository
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

    /**
     * Phase 4 extraction/FT index repository (one shared instance so the
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

    fun settings(context: Context): SettingsRepository =
        settings ?: synchronized(this) {
            settings ?: SettingsRepository(context.applicationContext).also { settings = it }
        }
}
