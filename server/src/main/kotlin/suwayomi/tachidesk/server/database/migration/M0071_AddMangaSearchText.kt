package suwayomi.tachidesk.server.database.migration

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.helpers.SQLMigration
import suwayomi.tachidesk.server.database.migration.helpers.UNLIMITED_TEXT
import suwayomi.tachidesk.server.database.migration.helpers.toSqlName

/**
 * Adds the normalized search text of a manga used by the library text search.
 *
 * Only the columns are created here: backfilling ~190k rows and building the optional trigram index
 * would block the first startup for a minute or more, so that runs as a resumable background job
 * ([suwayomi.tachidesk.manga.impl.search.MangaSearchIndex]) once the server is up.
 */
@Suppress("ClassName", "unused")
class M0071_AddMangaSearchText : SQLMigration() {
    override val sql: String
        get() {
            val mangaTable = "Manga".toSqlName()
            val searchTextCol = "search_text".toSqlName()
            val searchTextVerCol = "search_text_ver".toSqlName()

            return """
                ALTER TABLE $mangaTable ADD COLUMN IF NOT EXISTS $searchTextCol $UNLIMITED_TEXT;
                ALTER TABLE $mangaTable ADD COLUMN IF NOT EXISTS $searchTextVerCol INTEGER NOT NULL DEFAULT 0;
            """.trimIndent()
        }
}
