package com.prasbin.shadowlearn.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * SHADOW LEARN database.
 *
 * - v1 (Phase 1): Year → Semester → Module → Week → AcademicFile hierarchy.
 * - v2 (Phase 2): adds `AcademicFile.classType` (Lecture/Tutorial/Workshop
 *   origin, default OTHER) and `AcademicFile.relativePath` (original archive
 *   path, default empty). Purely additive with column defaults, applied via
 *   [AutoMigration] — verified by `MigrationTest` (v1→v2 preserves rows).
 *
 * Migration strategy (see docs/ARCHITECTURE.md):
 * - Every schema change bumps [DATABASE_VERSION] and ships an explicit
 *   migration (`AutoMigration` counts). Destructive migration is NEVER used
 *   — academic data must survive updates.
 * - `exportSchema = true`; generated schemas are committed under
 *   `app/schemas/` so migration tests can validate upgrades.
 */
@Database(
    entities = [AcademicYear::class, Semester::class, Module::class, Week::class, AcademicFile::class],
    version = ShadowLearnDatabase.DATABASE_VERSION,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)]
)
abstract class ShadowLearnDatabase : RoomDatabase() {

    abstract fun academicDao(): AcademicDao

    companion object {
        const val DATABASE_VERSION = 2
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
