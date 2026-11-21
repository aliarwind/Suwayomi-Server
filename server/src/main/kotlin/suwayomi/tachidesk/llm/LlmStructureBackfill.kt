package suwayomi.tachidesk.llm

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import suwayomi.tachidesk.graphql.types.LlmTitleStructureResult
import suwayomi.tachidesk.graphql.types.LlmTitleStructureRow
import suwayomi.tachidesk.manga.impl.search.MangaSearchText
import suwayomi.tachidesk.manga.impl.search.StructuredTitle
import suwayomi.tachidesk.manga.model.table.MangaLlmIndexTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.MangaUserTable
import suwayomi.tachidesk.manga.model.table.getWithUserData
import suwayomi.tachidesk.server.serverConfig
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fills [MangaLlmIndexTable] one model batch at a time.
 *
 * Two callers share it: the [suwayomi.tachidesk.graphql.mutations.LlmMutation.llmStructureTitles]
 * mutation (dry run first, script driven) and [LlmStructureJob] (the periodic incremental run). Both
 * have to see the same pending predicate and the same write path, otherwise the job would write rows
 * the mutation considers unfinished, or the mutation would not rebuild `search_text`.
 *
 * Only the books that are pending are touched: never extracted, extracted with an older
 * [LlmTitleStructurer.VERSION], or refreshed by the source since the row was written
 * (`last_fetched_at > updated_at`). A refresh that left title/tags/author alone only gets a new
 * timestamp, so it costs no model call. A failed batch is simply not written, which leaves the books
 * pending for the next call - that is the resumable cursor.
 */
object LlmStructureBackfill {
    /** One model call: 200 books is about a minute of wall time (4 parallel batches of 20). */
    const val MAX_LIMIT = 200
    const val DEFAULT_LIMIT = 40

    private val logger = KotlinLogging.logger {}
    private val running = AtomicBoolean(false)

    /** The result of a fill that could not run (`enabled = false`) or had nothing to do. */
    fun empty(
        dryRun: Boolean,
        enabled: Boolean = true,
    ): LlmTitleStructureResult =
        LlmTitleStructureResult(
            enabled = enabled,
            dryRun = dryRun,
            version = LlmTitleStructurer.VERSION,
            candidates = 0,
            asked = 0,
            written = 0,
            rows = emptyList(),
        )

    /**
     * Extracts at most [limit] pending books and returns what the model said.
     *
     * Returns null when another fill is already running: the job and the mutation would otherwise send
     * the same books to the model twice. Callers treat null as "try again later".
     *
     * [dryRun] previews without writing. [source] limits the batch to one source id. [userId] limits it
     * to one user's library; the background job passes null and covers every user's library.
     */
    fun fillBatch(
        dryRun: Boolean = true,
        limit: Int = DEFAULT_LIMIT,
        source: Long? = null,
        userId: Int? = null,
    ): LlmTitleStructureResult? {
        if (!LlmClient.isConfigured()) return empty(dryRun, enabled = false)
        if (!running.compareAndSet(false, true)) {
            logger.debug { "structured title backfill is already running, skipping this call" }
            return null
        }

        return try {
            fill(dryRun, limit, source, userId)
        } finally {
            running.set(false)
        }
    }

    private fun fill(
        dryRun: Boolean,
        limit: Int,
        source: Long?,
        userId: Int?,
    ): LlmTitleStructureResult {
        val batchSize = limit.coerceIn(1, MAX_LIMIT)

        val candidates =
            transaction {
                libraryMangas(userId)
                    .join(MangaLlmIndexTable, JoinType.LEFT, onColumn = MangaTable.id, otherColumn = MangaLlmIndexTable.manga)
                    .select(
                        MangaTable.id,
                        MangaTable.title,
                        MangaTable.genre,
                        MangaTable.author,
                        MangaTable.artist,
                        MangaTable.lastFetchedAt,
                        MangaLlmIndexTable.version,
                        MangaLlmIndexTable.inputHash,
                    ).where { pendingCondition(source) }
                    .orderBy(MangaTable.id, SortOrder.ASC)
                    .limit(batchSize)
                    .map {
                        StructureCandidate(
                            id = it[MangaTable.id].value,
                            title = it[MangaTable.title],
                            tags = it[MangaTable.genre].orEmpty(),
                            author = it[MangaTable.author],
                            artist = it[MangaTable.artist],
                            lastFetchedAt = it[MangaTable.lastFetchedAt],
                            version = it[MangaLlmIndexTable.version],
                            inputHash = it[MangaLlmIndexTable.inputHash],
                        )
                    }.distinctBy { it.id }
            }

        val rows = candidates.map { LlmTitleStructurer.Row(it.id, it.title, it.tags, it.author) }
        val byId = rows.associateBy { it.id }
        val hashes = rows.associate { it.id to LlmTitleStructurer.inputHash(it) }
        // a source refresh that left the title/tags/author alone only needs a fresh timestamp
        val askIds =
            candidates
                .filter { it.version != LlmTitleStructurer.VERSION || it.inputHash != hashes[it.id] }
                .map { it.id }
                .toSet()

        val structures = LlmTitleStructurer.structure(askIds.mapNotNull(byId::get))

        if (!dryRun) {
            val now = Instant.now().epochSecond
            val model = serverConfig.llmChatModel.value.trim()
            val candidatesById = candidates.associateBy { it.id }

            // the timestamp may never go below last_fetched_at, or a clock skew between the app and the
            // database would leave the row pending forever (the skip below would not clear the staleness)
            fun stampOf(id: Int): Long = maxOf(now, candidatesById[id]?.lastFetchedAt ?: 0)
            transaction {
                candidates
                    .filterNot { it.id in askIds }
                    .forEach { candidate ->
                        MangaLlmIndexTable.update({ MangaLlmIndexTable.manga eq candidate.id }) { it[updatedAt] = stampOf(candidate.id) }
                    }

                structures.forEach { structure ->
                    MangaLlmIndexTable.upsert(MangaLlmIndexTable.manga) {
                        it[MangaLlmIndexTable.manga] = structure.id
                        it[cleanTitle] = structure.cleanTitle
                        it[series] = structure.series
                        it[characters] = encodeList(structure.characters)
                        it[circle] = structure.circle
                        it[artist] = structure.artist
                        it[lang] = structure.lang
                        it[aliases] = encodeList(structure.aliases)
                        it[flags] = structure.flags.joinToString(" ")
                        it[inputHash] = hashes[structure.id]
                        it[MangaLlmIndexTable.model] = model
                        it[version] = LlmTitleStructurer.VERSION
                        it[updatedAt] = stampOf(structure.id)
                    }

                    // the structured fields are part of the search index, so the row has to be rebuilt
                    // right away; otherwise the new extraction is invisible until something else touches it
                    candidatesById[structure.id]?.let { candidate ->
                        MangaTable.update({ MangaTable.id eq structure.id }) {
                            it[searchText] =
                                MangaSearchText.index(
                                    candidate.title,
                                    candidate.author,
                                    candidate.artist,
                                    candidate.tags,
                                    StructuredTitle.Fields(
                                        cleanTitle = structure.cleanTitle,
                                        series = structure.series,
                                        characters = structure.characters,
                                        circle = structure.circle,
                                        artist = structure.artist,
                                        aliases = structure.aliases,
                                        flags = structure.flags,
                                    ),
                                )
                            it[searchTextVer] = MangaSearchText.VERSION
                        }
                    }
                }
            }
        }

        return LlmTitleStructureResult(
            enabled = true,
            dryRun = dryRun,
            version = LlmTitleStructurer.VERSION,
            candidates = candidates.size,
            asked = askIds.size,
            written = structures.size,
            rows =
                structures.map { structure ->
                    LlmTitleStructureRow(
                        mangaId = structure.id,
                        title = byId[structure.id]?.title.orEmpty(),
                        cleanTitle = structure.cleanTitle,
                        series = structure.series,
                        characters = structure.characters,
                        circle = structure.circle,
                        artist = structure.artist,
                        lang = structure.lang,
                        aliases = structure.aliases,
                        flags = structure.flags,
                        rejects = structure.rejects,
                    )
                },
        )
    }

    /** Books in somebody's library; a user id narrows it to that user, null means every library. */
    private fun libraryMangas(userId: Int?) =
        if (userId == null) {
            MangaTable.innerJoin(MangaUserTable)
        } else {
            MangaTable.getWithUserData(userId)
        }

    /**
     * Rows that still need the model: no index row, an older rules version, or a source refresh after the
     * row was written. The refresh case is re-checked against `input_hash` before any call is made.
     */
    private fun pendingCondition(source: Long?): Op<Boolean> {
        val pending =
            MangaLlmIndexTable.version.isNull() or
                (MangaLlmIndexTable.version less LlmTitleStructurer.VERSION) or
                (MangaTable.lastFetchedAt greater MangaLlmIndexTable.updatedAt)
        val library = MangaUserTable.inLibrary eq true
        return if (source == null) library and pending else library and pending and (MangaTable.sourceReference eq source)
    }

    private fun encodeList(values: List<String>): String = Json.encodeToString(ListSerializer(String.serializer()), values)

    private class StructureCandidate(
        val id: Int,
        val title: String,
        val tags: String,
        val author: String?,
        val artist: String?,
        val lastFetchedAt: Long,
        val version: Int?,
        val inputHash: String?,
    )
}
