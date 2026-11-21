package suwayomi.tachidesk.graphql.mutations

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.types.LlmAuthorFixChange
import suwayomi.tachidesk.graphql.types.LlmAuthorFixResult
import suwayomi.tachidesk.graphql.types.LlmTitleStructureResult
import suwayomi.tachidesk.llm.LlmAuthorFixer
import suwayomi.tachidesk.llm.LlmClient
import suwayomi.tachidesk.llm.LlmStructureBackfill
import suwayomi.tachidesk.manga.impl.MangaAuthorLock
import suwayomi.tachidesk.manga.impl.search.MangaSearchText
import suwayomi.tachidesk.manga.impl.search.StructuredTitle
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.MangaUserTable
import suwayomi.tachidesk.manga.model.table.getWithUserData

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
     * Fills the structured title table (`mangallmindex`): one model batch of pending books per call.
     *
     * One call extracts at most [limit] books, ordered by id, and only the books that are pending: never
     * extracted, extracted with an older prompt version, or refreshed by the source since the row was
     * written (`last_fetched_at > updated_at`). The caller loops until `candidates < limit`, which is the
     * resumable cursor — a failed batch is simply not written, so the next call retries it.
     *
     * Writing a row also rebuilds that book's `search_text`, so the new fields are searchable right away.
     * [dryRun] is the default, so storing requires an explicit opt in. The periodic incremental run uses
     * the same batch routine ([LlmStructureBackfill]) and does not need this mutation.
     */
    @RequireAuth
    fun llmStructureTitles(
        @GraphQLIgnore userId: Int,
        source: Long? = null,
        dryRun: Boolean = true,
        limit: Int = LlmStructureBackfill.DEFAULT_LIMIT,
    ): LlmTitleStructureResult =
        LlmStructureBackfill.fillBatch(dryRun = dryRun, limit = limit, source = source, userId = userId)
            ?: LlmStructureBackfill.empty(dryRun)

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

        /** What a source writes when it could not find a name. */
        const val NO_AUTHOR = "某绅士"
    }
}
