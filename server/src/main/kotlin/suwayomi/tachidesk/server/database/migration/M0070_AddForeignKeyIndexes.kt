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
 * Adds the foreign-key indexes from upstream M0061 (`CreateForeignKeyIndexes`) that are still
 * missing on databases which recorded version 61 through the former local
 * `AddPerformanceIndexesV2` migration instead.
 *
 * The migration framework only compares version numbers, so on those databases upstream M0061
 * is skipped and its indexes are never created. `IF NOT EXISTS` keeps this a no-op wherever
 * M0061 did run (fresh installs), so it is safe on every database.
 *
 * The three indexes M0069 already covers (categorymanga.category, categorymanga.manga,
 * chapter.manga) are intentionally not repeated here.
 */
@Suppress("ClassName", "unused")
class M0070_AddForeignKeyIndexes : SQLMigration() {
    override val sql: String
        get() {
            val categoryMetaTable = "CategoryMeta".toSqlName()
            val chapterMetaTable = "ChapterMeta".toSqlName()
            val mangaMetaTable = "MangaMeta".toSqlName()
            val pageTable = "Page".toSqlName()
            val sourceTable = "Source".toSqlName()
            val sourceMetaTable = "SourceMeta".toSqlName()
            val trackRecordTable = "TrackRecord".toSqlName()

            val categoryRefCol = "category_ref".toSqlName()
            val chapterRefCol = "chapter_ref".toSqlName()
            val mangaRefCol = "manga_ref".toSqlName()
            val chapterCol = "chapter".toSqlName()
            val extensionCol = "extension".toSqlName()
            val sourceRefCol = "source_ref".toSqlName()
            val mangaIdCol = "manga_id".toSqlName()

            return """
                CREATE INDEX IF NOT EXISTS idx_categorymeta_category ON $categoryMetaTable($categoryRefCol);
                CREATE INDEX IF NOT EXISTS idx_chaptermeta_chapter ON $chapterMetaTable($chapterRefCol);
                CREATE INDEX IF NOT EXISTS idx_mangameta_manga ON $mangaMetaTable($mangaRefCol);
                CREATE INDEX IF NOT EXISTS idx_page_chapter ON $pageTable($chapterCol);
                CREATE INDEX IF NOT EXISTS idx_source_extension ON $sourceTable($extensionCol);
                CREATE INDEX IF NOT EXISTS idx_sourcemeta_source ON $sourceMetaTable($sourceRefCol);
                CREATE INDEX IF NOT EXISTS idx_trackrecord_manga ON $trackRecordTable($mangaIdCol);
            """.trimIndent()
        }
}
