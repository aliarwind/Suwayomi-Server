package suwayomi.tachidesk.graphql.queries

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.types.LlmSearchHit
import suwayomi.tachidesk.graphql.types.LlmSearchResult
import suwayomi.tachidesk.graphql.types.MangaType
import suwayomi.tachidesk.llm.LlmLibrarySearch
import suwayomi.tachidesk.llm.LlmQueryPlan
import suwayomi.tachidesk.llm.LlmSearchService
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.getWithUserData
import suwayomi.tachidesk.server.serverConfig

/**
 * Entry points of the optional AI search.
 *
 * [llmQueryPlan] shows what the model made of a query without running the library search (tuning aid),
 * [llmSearch] is the two step search: local recall, model ranking with a reason per hit.
 */
class LlmQuery {
    fun llmQueryPlan(query: String): String {
        if (!serverConfig.llmQueryEnabled.value) {
            return "{\"enabled\":false}"
        }

        val plan = LlmSearchService.plan(query)

        return Json.encodeToString(
            LlmQueryPlan.serializer(),
            plan ?: LlmQueryPlan(),
        ).let { if (plan == null) "{\"parsed\":false,\"plan\":$it}" else "{\"parsed\":true,\"plan\":$it}" }
    }

    /**
     * Ranked library search: the model reads the recalled titles and returns the matches in relevance
     * order with a short reason. Returns no hits (and `enabled=false`) when the feature is disabled, and
     * `degraded=true` when the model could not be used - callers keep their normal search in both cases.
     */
    @RequireAuth
    fun llmSearch(
        @GraphQLIgnore userId: Int,
        query: String,
        limit: Int = 10,
    ): LlmSearchResult {
        if (!LlmLibrarySearch.isEnabled()) {
            return LlmSearchResult(
                enabled = false,
                degraded = false,
                parsed = false,
                candidates = 0,
                tookMs = 0,
                hits = emptyList(),
            )
        }

        val started = System.currentTimeMillis()
        val result = LlmLibrarySearch.search(userId, query, limit.coerceIn(1, MAX_LIMIT))

        val ids = result.hits.map { it.id }
        val mangas =
            if (ids.isEmpty()) {
                emptyMap()
            } else {
                transaction {
                    MangaTable
                        .getWithUserData(userId)
                        .selectAll()
                        .where { MangaTable.id inList ids }
                        .associateBy { it[MangaTable.id].value }
                }
            }

        val hits =
            result.hits.mapNotNull { hit ->
                mangas[hit.id]?.let { row -> LlmSearchHit(MangaType(row), hit.score, hit.reason) }
            }

        return LlmSearchResult(
            enabled = true,
            degraded = result.degraded,
            parsed = result.parsed,
            candidates = result.candidates,
            tookMs = (System.currentTimeMillis() - started).toInt(),
            hits = hits,
        )
    }

    private companion object {
        /** The ranking list is a card strip in the UI; more than this would only cost tokens. */
        const val MAX_LIMIT = 30
    }
}
