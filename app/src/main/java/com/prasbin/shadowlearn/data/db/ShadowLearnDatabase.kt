package com.prasbin.shadowlearn.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
 * - v4 (Phase 4): adds `document_chunks` (extracted text units) and
 *   `extraction_meta` (per-file extraction status/signature). Purely
 *   additive, shipped as a manual [androidx.room.migration.Migration]
 *   (rows copied verbatim; the migration SQL mirrors the exported schema
 *   exactly so validation passes).
 *
 * The FTS virtual index table (`document_fts`, FTS5 where the platform
 * SQLite provides the module, FTS4 with honest fallback otherwise) is NOT
 * part of the schema export: it is created and mirrored by
 * `data/search/FtsIndex.kt` on first use (see that file and
 * docs/ARCHITECTURE.md for the FTS5→FTS4 story and acceptance evidence).
 *
 * v1→v2 and v2→v3 ship as [AutoMigration]; 3→4 is manual (verified by
 * `MigrationTest` against the committed schemas — every row is preserved).
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
        AcademicFile::class, SourceFile::class,
        DocumentChunk::class, ExtractionMeta::class
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

    abstract fun extractionDao(): ExtractionDao

    companion object {
        const val DATABASE_VERSION = 4
        const val DATABASE_NAME = "shadowlearn.db"

        /**
         * v3 → v4: creates `document_chunks` and `extraction_meta` (Phase 4).
         * Purely additive. The DDL mirrors the exported v4 schema exactly
         * (TableInfo validation compares all columns/indices/FKs), and is
         * asserted by MigrationTest.migrate3ToCurrent.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `document_chunks` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`academicFileId` INTEGER NOT NULL, " +
                        "`chunkIndex` INTEGER NOT NULL, " +
                        "`pageNumber` INTEGER, " +
                        "`text` TEXT NOT NULL, " +
                        "`charCount` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`academicFileId`) REFERENCES `academic_files`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_document_chunks_academicFileId` " +
                        "ON `document_chunks` (`academicFileId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_document_chunks_chunkIndex` " +
                        "ON `document_chunks` (`chunkIndex`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `extraction_meta` (" +
                        "`academicFileId` INTEGER NOT NULL, " +
                        "`sha256` TEXT NOT NULL, " +
                        "`status` TEXT NOT NULL, " +
                        "`format` TEXT NOT NULL, " +
                        "`charCount` INTEGER NOT NULL, " +
                        "`chunkCount` INTEGER NOT NULL, " +
                        "`error` TEXT, " +
                        "`startedAt` INTEGER NOT NULL, " +
                        "`completedAt` INTEGER, " +
                        "PRIMARY KEY(`academicFileId`), " +
                        "FOREIGN KEY(`academicFileId`) REFERENCES `academic_files`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
            }
        }

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
                    .addMigrations(MIGRATION_3_4)
                    .build()
                    .also { instance = it }
            }
    }
}
