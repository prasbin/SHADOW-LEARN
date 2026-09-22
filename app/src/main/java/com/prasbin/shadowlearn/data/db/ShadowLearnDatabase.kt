package com.prasbin.shadowlearn.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * SHADOW LEARN database, version 1 (Phase 1 foundation).
 *
 * Migration strategy (see docs/ARCHITECTURE.md):
 * - Every schema change bumps [DATABASE_VERSION] and ships an explicit
 *   `Migration(x, y)`. Destructive migration is NEVER used — academic data
 *   must survive updates.
 * - `exportSchema = true`; generated schemas are committed under
 *   `app/schemas/` so future migration tests can validate upgrades.
 * - Later phases add tables (chunks, pages/slides, topics, quizzes, cards,
 *   transcripts, progress, similarity) referencing existing primary keys;
 *   this v1 hierarchy needs no destructive redesign.
 */
@Database(
    entities = [AcademicYear::class, Semester::class, Module::class, Week::class, AcademicFile::class],
    version = ShadowLearnDatabase.DATABASE_VERSION,
    exportSchema = true
)
abstract class ShadowLearnDatabase : RoomDatabase() {

    abstract fun academicDao(): AcademicDao

    companion object {
        const val DATABASE_VERSION = 1
        const val DATABASE_NAME = "shadowlearn.db"

        @Volatile
        private var instance: ShadowLearnDatabase? = null

        fun get(context: Context): ShadowLearnDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ShadowLearnDatabase::class.java,
                    DATABASE_NAME
                )
                    // No fallbackToDestructiveMigration() by design: if a future
                    // migration is missing, fail loudly instead of wiping data.
                    .build()
                    .also { instance = it }
            }
    }
}
