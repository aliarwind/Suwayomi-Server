package suwayomi.tachidesk.server.database.migration

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.helpers.SQLMigration
import suwayomi.tachidesk.server.database.migration.helpers.toSqlName

/**
 * Adds performance indexes for large libraries (20k+ manga):
 * - MANGA.IN_LIBRARY: library manga queries / COUNT
 * - CATEGORY_MANGA.CATEGORY: category-based manga listing
 * - CATEGORY_MANGA.MANGA: manga-category JOINs
 * - CHAPTER.MANGA: chapter queries by manga (download cleanup)
 * - CHAPTER.IS_DOWNLOADED: download cleanup queries
 */
@Suppress("ClassName", "unused")
class M0059_AddPerformanceIndexes : SQLMigration() {
    override val sql: String
        get() {
            // Table names as defined in Exposed table objects
            val mangaTable = "Manga".toSqlName()
            val categoryMangaTable = "CategoryManga".toSqlName()
            val chapterTable = "Chapter".toSqlName()

            // Column names as defined in the table column definitions
            val inLibraryCol = "in_library".toSqlName()
            val categoryCol = "category".toSqlName()
            val mangaRefCol = "manga".toSqlName()
            val isDownloadedCol = "is_downloaded".toSqlName()

            return """
                CREATE INDEX IF NOT EXISTS MANGA_IN_LIBRARY_IDX ON $mangaTable($inLibraryCol);
                CREATE INDEX IF NOT EXISTS CATEGORY_MANGA_CATEGORY_IDX ON $categoryMangaTable($categoryCol);
                CREATE INDEX IF NOT EXISTS CATEGORY_MANGA_MANGA_IDX ON $categoryMangaTable($mangaRefCol);
                CREATE INDEX IF NOT EXISTS CHAPTER_MANGA_IDX ON $chapterTable($mangaRefCol);
                CREATE INDEX IF NOT EXISTS CHAPTER_IS_DOWNLOADED_IDX ON $chapterTable($isDownloadedCol);
            """.trimIndent()
        }
}
