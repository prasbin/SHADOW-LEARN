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
 *   path, default empty). Purely additive with column defaults.
 * - v3 (Phase 3): adds the `source_files` content-identity table and the
 *   plain (non-FK) `AcademicFile.sourceFileId` reference column. Purely
 *   additive.
 *
 * All three migrations are shipped as [AutoMigration] (verified by
 * `MigrationTest` against the committed schemas — v1→v2 and v2→v3 preserve
 * every row).
 *
 * Migration strategy (see docs/ARCHITECTURE.md):
 * - Every schema change bumps [DATABASE_VERSION] and ships an explicit
 *   migration (`AutoMigration` counts). Destructive migration is NEVER used
 *   — academic data must survive updates.
 * - `exportSchema = true`; generated schemas are committed under
 *   `app/schemas/` so migration tests can validate upgrades.
 */
@Database(
    entities = [
        AcademicYear::class, Semester::class, Module::class, Week::class,
        AcademicFile::class, SourceFile::class
    ],
    version = ShadowLearnDatabase.DATABASE_VERSION,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3)
    ]
)
abstract class ShadowLearnDatabase : RoomDatabase() {

    abstract fun academicDao(): AcademicDao

    companion object {
        const val DATABASE_VERSION = 3
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
