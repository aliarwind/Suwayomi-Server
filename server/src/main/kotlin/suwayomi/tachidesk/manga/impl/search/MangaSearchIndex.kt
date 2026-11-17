package suwayomi.tachidesk.manga.impl.search

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.statements.BatchUpdateStatement
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.statements.toExecutable
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.manga.model.table.MangaTable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Background backfill of [MangaTable.searchText].
 *
 * The migration only adds the columns, so the very first start after upgrading (or any start after
 * [MangaSearchText.VERSION] was bumped) has to fill ~190k rows. That is done here, in id ordered batches,
 * so the server is reachable immediately and the job is safe to interrupt: it only ever writes rows it
 * re-reads through the "needs rebuild" predicate.
 */
object MangaSearchIndex {
    private const val BATCH_SIZE = 500
    private const val LOG_EVERY_BATCHES = 20

    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)

    /**
     * Starts the backfill unless one is already running. Used at startup and whenever the database
     * connection changes (the new database may miss the index entirely).
     */
    fun startAsync() {
        if (!running.compareAndSet(false, true)) return
        scope.launch {
            runCatching { rebuildAll() }
                .onSuccess { logger.info { "Manga search index is up to date ($it rows rebuilt)" } }
                .onFailure { logger.error(it) { "Failed to build the manga search index" } }
            running.set(false)
        }
    }

    /** Rebuilds every manga whose index is missing or was built with an older [MangaSearchText.VERSION]. */
    fun rebuildAll(): Int {
        val start = System.currentTimeMillis()
        var rebuilt = 0
        var lastId = 0
        var batches = 0

        while (true) {
            val batch = rebuildBatch(lastId)
            if (batch.rebuilt == 0) break

            rebuilt += batch.rebuilt
            lastId = batch.lastId
            batches++

            if (batches % LOG_EVERY_BATCHES == 0) {
                logger.info { "Manga search index: $rebuilt manga rebuilt so far (manga id > $lastId)" }
            }
        }

        logger.info { "Manga search index rebuilt $rebuilt manga in ${System.currentTimeMillis() - start}ms" }

        // The vocabulary is built from the indexed text, so it has to wait until the index is complete.
        MangaSearchVocabulary.build()

        return rebuilt
    }

    private class BatchResult(
        val rebuilt: Int,
        val lastId: Int,
    )

    private fun rebuildBatch(afterId: Int): BatchResult =
        transaction {
            val rows =
                MangaTable
                    .select(MangaTable.id, MangaTable.title, MangaTable.author, MangaTable.artist, MangaTable.genre)
                    .where {
                        (MangaTable.id greater afterId) and
                            (MangaTable.searchText.isNull() or (MangaTable.searchTextVer less MangaSearchText.VERSION))
                    }.orderBy(MangaTable.id to SortOrder.ASC)
                    .limit(BATCH_SIZE)
                    .toList()

            if (rows.isEmpty()) return@transaction BatchResult(0, afterId)

            BatchUpdateStatement(MangaTable)
                .apply {
                    rows.forEach { row ->
                        addBatch(EntityID(row[MangaTable.id].value, MangaTable))
                        this[MangaTable.searchText] =
                            MangaSearchText.index(
                                row[MangaTable.title],
                                row[MangaTable.author],
                                row[MangaTable.artist],
                                row[MangaTable.genre],
                            )
                        this[MangaTable.searchTextVer] = MangaSearchText.VERSION
                    }
                }.toExecutable()
                .execute(this@transaction)

            BatchResult(rows.size, rows.last()[MangaTable.id].value)
        }
}
