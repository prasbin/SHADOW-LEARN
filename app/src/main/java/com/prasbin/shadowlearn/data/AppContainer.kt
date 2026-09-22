package com.prasbin.shadowlearn.data

import android.content.Context
import com.prasbin.shadowlearn.data.db.AcademicDao
import com.prasbin.shadowlearn.data.db.ShadowLearnDatabase
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

    fun database(context: Context): ShadowLearnDatabase =
        database ?: synchronized(this) {
            database ?: ShadowLearnDatabase.get(context).also { database = it }
        }

    fun dao(context: Context): AcademicDao = database(context).academicDao()

    fun settings(context: Context): SettingsRepository =
        settings ?: synchronized(this) {
            settings ?: SettingsRepository(context.applicationContext).also { settings = it }
        }
}
