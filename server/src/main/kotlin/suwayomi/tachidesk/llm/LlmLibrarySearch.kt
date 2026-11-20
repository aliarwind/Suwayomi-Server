package suwayomi.tachidesk.llm

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.manga.impl.search.MangaSearchAliases
import suwayomi.tachidesk.manga.impl.search.MangaSearchVocabulary
import suwayomi.tachidesk.manga.model.table.CategoryMangaTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.MangaUserTable
import suwayomi.tachidesk.manga.model.table.getWithUserData
import suwayomi.tachidesk.server.serverConfig

/**
 * Two step library search: recall locally, rank with the model.
 *
 * 1. Recall: the LLM query plan (or the rule based vocabulary when the model has nothing to say) decides
 *    which terms matter. Books matching all of them come first, books matching only some fill the rest,
 *    newest library additions first. The result is capped by `llmRerankCandidates`.
 * 2. Rerank: [LlmReranker] gets the query and the candidate titles, orders them and writes a reason.
 *
 * Everything is optional: when the feature is off, the model fails or the answer is unusable, the caller
 * gets no hits and falls back to the normal search instead of a wrong ranking.
 */
object LlmLibrarySearch {
    private val logger = KotlinLogging.logger {}

    private const val POSITIVE_TTL_MS = 10 * 60 * 1000L
    private const val NEGATIVE_TTL_MS = 60 * 1000L
    private const val CACHE_SIZE = 100

    /** Ranking results are per user (the library differs) and per query. */
    private class CacheEntry(
        val result: Result,
        val timestamp: Long,
    )

    private val cache =
        object : LinkedHashMap<String, CacheEntry>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>): Boolean =
                size > CACHE_SIZE
        }

    data class Result(
        val hits: List<LlmRerankHit>,
        val candidates: Int,
        /** The model answer could not be used; the caller should not show a ranking. */
        val degraded: Boolean,
        /** The query was interpreted by the model (as opposed to the rule based vocabulary). */
        val parsed: Boolean,
    )

    /** A recalled row: enough to rank it locally and to answer the first pass. */
    private class Recalled(
        val id: Int,
        val title: String,
        val searchText: String,
        val inLibraryAt: Long,
    )

    fun isEnabled(): Boolean = LlmSearchService.isEnabled()

    /**
     * Ranked hits for [query] inside the library of [userId], best first. When [categoryId] is set only
     * books of that category are ranked, so the result matches the tab the user is looking at.
     */
    fun search(
        userId: Int,
        query: String?,
        limit: Int,
        categoryId: Int? = null,
    ): Result {
        val trimmed = query?.trim().orEmpty()
        if (trimmed.isEmpty() || !isEnabled() || limit <= 0) {
            return Result(emptyList(), candidates = 0, degraded = false, parsed = false)
        }

        val cacheKey = "$userId\u0000$trimmed\u0000$limit\u0000${categoryId ?: -1}"
        val now = System.currentTimeMillis()
        synchronized(cache) {
            cache[cacheKey]?.let { entry ->
                val ttl = if (entry.result.degraded) NEGATIVE_TTL_MS else POSITIVE_TTL_MS
                if (now - entry.timestamp < ttl) return entry.result
            }
        }

        val started = System.currentTimeMillis()
        val plan = LlmSearchService.plan(trimmed)
        val candidateLimit = serverConfig.llmRerankCandidates.value
        val candidates = recall(userId, trimmed, plan, candidateLimit, categoryId)

        val result =
            if (candidates.isEmpty()) {
                Result(emptyList(), candidates = 0, degraded = false, parsed = plan != null)
            } else {
                val ranked = LlmReranker.rerank(trimmed, candidates, limit)
                if (ranked == null) {
                    Result(emptyList(), candidates.size, degraded = true, parsed = plan != null)
                } else {
                    Result(ranked, candidates.size, degraded = false, parsed = plan != null)
                }
            }

        synchronized(cache) {
            cache[cacheKey] = CacheEntry(result, System.currentTimeMillis())
        }

        logger.debug {
            "LLM library search for '$trimmed' (user $userId) in ${System.currentTimeMillis() - started}ms: " +
                "${result.hits.size} hits, ${result.candidates} candidates, degraded=${result.degraded}, " +
                "parsed=${result.parsed}"
        }

        return result
    }

    /**
     * Local recall. Every group is an OR of equivalent terms (synonyms); books matching every group are
     * preferred, then books matching only some of them. Both passes are single LIKE scans.
     */
    private fun recall(
        userId: Int,
        query: String,
        plan: LlmQueryPlan?,
        limit: Int,
        categoryId: Int? = null,
    ): List<LlmCandidate> {
        val groups = groupsOf(plan, query)
        if (groups.isEmpty() || limit <= 0) return emptyList()

        // Restrict to one category (a tab) without a join: the category membership is user scoped already,
        // the user id is added so a stale category id can never reach another account's entries.
        val categoryFilter: Op<Boolean>? =
            categoryId?.let { id ->
                MangaTable.id inSubQuery
                    CategoryMangaTable
                        .select(CategoryMangaTable.manga)
                        .where { (CategoryMangaTable.category eq id) and (CategoryMangaTable.user eq userId) }
            }

        fun termOp(term: String): Op<Boolean> = MangaTable.searchText like "%$term%"

        fun groupOp(group: List<String>): Op<Boolean> = group.map(::termOp).reduce { acc, op -> acc or op }

        fun allOp(): Op<Boolean> = groups.map(::groupOp).reduce { acc, op -> acc and op }

        fun anyOp(): Op<Boolean> = groups.map(::groupOp).reduce { acc, op -> acc or op }

        val recalled =
            transaction {
                fun fetch(
                    op: Op<Boolean>,
                    max: Int,
                ): List<Recalled> =
                    MangaTable
                        .getWithUserData(userId)
                        .select(MangaTable.id, MangaTable.title, MangaTable.searchText, MangaUserTable.inLibraryAt)
                        .where {
                            val base = (MangaUserTable.inLibrary eq true) and op
                            categoryFilter?.let { base and it } ?: base
                        }
                        .orderBy(MangaUserTable.inLibraryAt to SortOrder.DESC)
                        .limit(max)
                        .map { row ->
                            Recalled(
                                row[MangaTable.id].value,
                                row[MangaTable.title],
                                row[MangaTable.searchText].orEmpty(),
                                row[MangaUserTable.inLibraryAt],
                            )
                        }

                val byId = LinkedHashMap<Int, Recalled>()
                fetch(allOp(), limit).forEach { byId.putIfAbsent(it.id, it) }
                if (groups.size > 1) {
                    fetch(anyOp(), limit * 2).forEach { byId.putIfAbsent(it.id, it) }
                }
                byId.values.toList()
            }

        return recalled
            .map { candidate ->
                candidate to groups.count { group -> group.any { term -> candidate.searchText.contains(term) } }
            }.sortedWith(
                compareByDescending<Pair<Recalled, Int>> { it.second }
                    .thenByDescending { it.first.inLibraryAt },
            ).take(limit)
            .map { (candidate, _) -> LlmCandidate(candidate.id, candidate.title) }
    }

    /**
     * AND-ed groups of OR-able terms. With a model plan the tags keep their AND/OR semantics; without one
     * the vocabulary plan is used, where the genre tags are "any of them".
     */
    private fun groupsOf(
        plan: LlmQueryPlan?,
        query: String,
    ): List<List<String>> {
        if (plan != null) {
            val groups = mutableListOf<List<String>>()
            plan.titleTerms.forEach { groups += listOf(it) }
            plan.author?.let { groups += listOf(it) }

            val tagsWithAliases =
                plan.tags.associateWith { tag -> (listOf(tag) + MangaSearchAliases.aliasesOf(tag)).distinct() }
            if (plan.tagsAreAny) {
                tagsWithAliases.values.flatten().distinct().takeIf { it.isNotEmpty() }?.let { groups += it }
            } else {
                tagsWithAliases.values.forEach { groups += it }
            }

            return groups.filter { it.isNotEmpty() }
        }

        val vocabularyPlan = MangaSearchVocabulary.plan(query)
        val groups = vocabularyPlan.textGroups.map { it.distinct() }.toMutableList()
        if (vocabularyPlan.anyTerms.isNotEmpty()) groups += vocabularyPlan.anyTerms.distinct()

        return groups.filter { it.isNotEmpty() }
    }
}
