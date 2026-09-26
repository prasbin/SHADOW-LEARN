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
 * - v5 (Phase 6): adds `quiz_sessions` and `quiz_questions` (the daily quiz
 *   engine). Purely additive, shipped as a manual MIGRATION_4_5 mirroring
 *   the exported schema (Task #3: quiz reads live chunk text directly from
 *   `document_chunks`, never via FTS, so no index migration is needed).
 * - v6 (Phase 7): adds `listener_sessions` and `listener_segments`
 *   (Listener Mode recording foundation). Purely additive, shipped as a
 *   manual MIGRATION_5_6 mirroring the exported schema. Raw audio lives in
 *   app-private files, never in SQLite.
 *
 * The FTS virtual index table (`document_fts`, FTS5 where the platform
 * SQLite provides the module, FTS4 with honest fallback otherwise) is NOT
 * part of the schema export: it is created and mirrored by
 * `data/search/FtsIndex.kt` on first use (see that file and
 * docs/ARCHITECTURE.md for the FTS5→FTS4 story and acceptance evidence).
 *
 * v1→v2 and v2→v3 ship as [AutoMigration]; 3→4, 4→5 and 5→6 are manual (verified
 * by `MigrationTest` against the committed schemas — every row is preserved).
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
        DocumentChunk::class, ExtractionMeta::class,
        QuizSession::class, QuizQuestion::class,
        ListenerSession::class, ListenerSegment::class
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

    abstract fun searchDao(): SearchDao

    abstract fun quizDao(): QuizDao

    abstract fun listenerDao(): ListenerDao

    companion object {
        const val DATABASE_VERSION = 6
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

        /**
         * v4 → v5: creates `quiz_sessions` and `quiz_questions` (Phase 6).
         * Purely additive; quiz tables reference academic rows through PLAIN
         * columns (never FKs) so quiz history survives re-imports/deletes.
         * DDL mirrors the exported v5 schema exactly (TableInfo validation);
         * asserted by MigrationTest.migrate4ToCurrent.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `quiz_sessions` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`semesterId` INTEGER NOT NULL, " +
                        "`seed` INTEGER NOT NULL, " +
                        "`totalQuestions` INTEGER NOT NULL, " +
                        "`correctCount` INTEGER NOT NULL, " +
                        "`xpEarned` INTEGER NOT NULL, " +
                        "`streak` INTEGER NOT NULL, " +
                        "`status` TEXT NOT NULL, " +
                        "`startedAt` INTEGER NOT NULL, " +
                        "`completedAt` INTEGER )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_quiz_sessions_semesterId` " +
                        "ON `quiz_sessions` (`semesterId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_quiz_sessions_status` " +
                        "ON `quiz_sessions` (`status`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `quiz_questions` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`sessionId` INTEGER NOT NULL, " +
                        "`position` INTEGER NOT NULL, " +
                        "`chunkId` INTEGER NOT NULL, " +
                        "`academicFileId` INTEGER NOT NULL, " +
                        "`questionType` TEXT NOT NULL, " +
                        "`prompt` TEXT NOT NULL, " +
                        "`optionsJson` TEXT, " +
                        "`correctAnswer` TEXT NOT NULL, " +
                        "`userAnswer` TEXT, " +
                        "`isCorrect` INTEGER, " +
                        "`srcFileName` TEXT NOT NULL, " +
                        "`srcFileType` TEXT NOT NULL, " +
                        "`srcPage` INTEGER, " +
                        "`srcExcerpt` TEXT NOT NULL, " +
                        "FOREIGN KEY(`sessionId`) REFERENCES `quiz_sessions`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_quiz_questions_sessionId` " +
                        "ON `quiz_questions` (`sessionId`)"
                )
            }
        }

        /**
         * v5 → v6: creates `listener_sessions` and `listener_segments`
         * (Phase 7). Purely additive; listener history references semesters
         * through a PLAIN column so it survives academic re-imports, and
         * segments CASCADE only with their own session. DDL mirrors the
         * exported v6 schema exactly (TableInfo validation); asserted by
         * MigrationTest.migrate5ToCurrent.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `listener_sessions` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`semesterId` INTEGER NOT NULL, " +
                        "`status` TEXT NOT NULL, " +
                        "`startedAt` INTEGER NOT NULL, " +
                        "`completedAt` INTEGER, " +
                        "`audioPath` TEXT, " +
                        "`createdAt` INTEGER NOT NULL )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_listener_sessions_semesterId` " +
                        "ON `listener_sessions` (`semesterId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_listener_sessions_status` " +
                        "ON `listener_sessions` (`status`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `listener_segments` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`sessionId` INTEGER NOT NULL, " +
                        "`position` INTEGER NOT NULL, " +
                        "`startedAtMs` INTEGER NOT NULL, " +
                        "`durationMs` INTEGER NOT NULL, " +
                        "`transcript` TEXT NOT NULL, " +
                        "`transcriptStatus` TEXT NOT NULL, " +
                        "FOREIGN KEY(`sessionId`) REFERENCES `listener_sessions`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_listener_segments_sessionId` " +
                        "ON `listener_segments` (`sessionId`)"
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
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .build()
                    .also { instance = it }
            }
    }
}
