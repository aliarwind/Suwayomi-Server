package suwayomi.tachidesk.server.database.migration

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.Migration
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.vendors.currentDialectMetadata
import suwayomi.tachidesk.manga.model.table.MangaLlmIndexTable

/**
 * Creates [MangaLlmIndexTable], the per-manga structured title index written by the LLM.
 *
 * Only a new table is added: the MANGA table is untouched, so an older jar can run against this database
 * (it just ignores the table) and rolling back needs no down migration. The table starts empty; filling it
 * is a background job ([suwayomi.tachidesk.llm.LlmTitleStructurer]) and does not block startup.
 *
 * [SchemaUtils.createStatements] emits nothing for a table that already exists, so a re-run (and a
 * database where the table was created by hand) is a no-op.
 */
@Suppress("ClassName", "unused")
class M0072_AddMangaLlmIndex : Migration() {
    override fun run() {
        with(TransactionManager.current()) {
            SchemaUtils.createStatements(MangaLlmIndexTable).forEach { exec(it) }
            currentDialectMetadata.resetCaches()
        }
    }
}
