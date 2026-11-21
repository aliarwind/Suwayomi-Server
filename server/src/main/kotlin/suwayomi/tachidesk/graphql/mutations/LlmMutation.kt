package suwayomi.tachidesk.graphql.mutations

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.types.LlmAuthorFixChange
import suwayomi.tachidesk.graphql.types.LlmAuthorFixResult
import suwayomi.tachidesk.graphql.types.LlmTitleStructureResult
import suwayomi.tachidesk.graphql.types.LlmTitleStructureRow
import suwayomi.tachidesk.llm.LlmAuthorFixer
import suwayomi.tachidesk.llm.LlmClient
import suwayomi.tachidesk.llm.LlmTitleStructurer
import suwayomi.tachidesk.manga.impl.MangaAuthorLock
import suwayomi.tachidesk.manga.impl.search.MangaSearchText
import suwayomi.tachidesk.manga.impl.search.StructuredTitle
import suwayomi.tachidesk.manga.model.table.MangaLlmIndexTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.MangaUserTable
import suwayomi.tachidesk.manga.model.table.getWithUserData
import suwayomi.tachidesk.server.serverConfig
import java.time.Instant

/**
 * Maintenance operations of the optional AI search.
 *
 * [llmFixAuthors] is the batch counterpart of the source's title based author extraction: the model reads
 * the titles that the regex could not resolve and proposes a name, which is written back only after the
 * answer passed the "the name really appears in the title or the tags" check. The dry run is the default,
 * so a caller has to opt in to writing.
 */
class LlmMutation {
    @RequireAuth
    fun llmFixAuthors(
        @GraphQLIgnore userId: Int,
        source: Long,
        dryRun: Boolean = true,
        limit: Int = 200,
    ): LlmAuthorFixResult {
        if (!LlmClient.isConfigured()) {
            return LlmAuthorFixResult(enabled = false, dryRun = dryRun, candidates = 0, changed = 0, changes = emptyList())
        }

        val rows =
            transaction {
                MangaTable
                    .getWithUserData(userId)
                    .select(MangaTable.id, MangaTable.title, MangaTable.author, MangaTable.genre)
                    .where {
                        (MangaTable.sourceReference eq source) and
                            (MangaUserTable.inLibrary eq true) and
                            needsFix()
                    }
                    .limit(limit.coerceIn(1, MAX_LIMIT))
                    .map {
                        Broken(
                            id = it[MangaTable.id].value,
                            title = it[MangaTable.title],
                            genre = it[MangaTable.genre].orEmpty(),
                            author = it[MangaTable.author],
                        )
                    }
            }

        val proposals = LlmAuthorFixer.propose(rows.map { LlmAuthorFixer.Row(it.id, it.title, it.genre) })
        val byId = rows.associateBy { it.id }

        if (!dryRun && proposals.isNotEmpty()) {
            transaction {
                proposals.forEach { proposal ->
                    val original = byId[proposal.id] ?: return@forEach
                    MangaTable.update({ MangaTable.id eq proposal.id }) {
                        it[author] = proposal.author
                        it[artist] = proposal.artist
                        // the author is part of the search index, so it has to be rebuilt with it
                        it[searchText] =
                            MangaSearchText.index(
                                original.title,
                                proposal.author,
                                proposal.artist,
                                original.genre,
                                StructuredTitle.load(proposal.id),
                            )
                        it[searchTextVer] = MangaSearchText.VERSION
                    }
                    MangaAuthorLock.lock(proposal.id, userId)
                }
            }
        }

        val changes =
            proposals.mapNotNull { proposal ->
                val original = byId[proposal.id] ?: return@mapNotNull null
                LlmAuthorFixChange(original.id, original.title, original.author, proposal.author, proposal.artist)
            }

        return LlmAuthorFixResult(
            enabled = true,
            dryRun = dryRun,
            candidates = rows.size,
            changed = changes.size,
            changes = changes,
        )
    }

    /**
     * Fills [MangaLlmIndexTable] for the library (v1 of the structured title index).
     *
     * One call extracts at most [limit] books, ordered by id, and only the books that are pending: never
     * extracted, extracted with an older [LlmTitleStructurer.VERSION], or refreshed by the source since
     * the row was written (`last_fetched_at > updated_at`). The caller loops until `candidates < limit`,
     * which is the resumable cursor — a failed batch is simply not written, so the next call retries it.
     *
     * Writing a row never touches `search_text`: v1 only fills the table, turning it into the index is a
     * separate change (rule version 4). [dryRun] is the default, so storing requires an explicit opt in.
     */
    @RequireAuth
    fun llmStructureTitles(
        @GraphQLIgnore userId: Int,
        source: Long? = null,
        dryRun: Boolean = true,
        limit: Int = DEFAULT_STRUCTURE_LIMIT,
    ): LlmTitleStructureResult {
        if (!LlmClient.isConfigured()) {
            return LlmTitleStructureResult(
                enabled = false,
                dryRun = dryRun,
                version = LlmTitleStructurer.VERSION,
                candidates = 0,
                asked = 0,
                written = 0,
                rows = emptyList(),
            )
        }

        val candidates =
            transaction {
                MangaTable
                    .getWithUserData(userId)
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
                    )
                    .where { pendingCondition(source) }
                    .orderBy(MangaTable.id, SortOrder.ASC)
                    .limit(limit.coerceIn(1, MAX_STRUCTURE_LIMIT))
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
                    }
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

    /** Authors the source could not resolve: empty, the "no idea" placeholder, or obvious metadata. */
    private fun needsFix() =
        MangaTable.author.isNull() or
            (MangaTable.author eq NO_AUTHOR) or
            (MangaTable.author like "%AI Generated%") or
            (MangaTable.author like "%Patreon%") or
            (MangaTable.author like "%Fanbox%") or
            (MangaTable.author like "%汉化%") or
            (MangaTable.author like "%漢化%") or
            (MangaTable.author like "%翻译%") or
            (MangaTable.author like "%翻譯%") or
            (MangaTable.author like "%翻訳%") or
            (MangaTable.author like "%無修正%") or
            (MangaTable.author like "%DL版%")

    private class Broken(
        val id: Int,
        val title: String,
        val genre: String,
        val author: String?,
    )

    private companion object {
        const val MAX_LIMIT = 2000

        /** Ten model batches: ~1 minute of wall time per call, the script loops for the rest. */
        const val MAX_STRUCTURE_LIMIT = 200
        const val DEFAULT_STRUCTURE_LIMIT = 40

        /** What a source writes when it could not find a name. */
        const val NO_AUTHOR = "某绅士"
    }
}
