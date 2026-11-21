/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package suwayomi.tachidesk.graphql.queries

import com.expediagroup.graphql.generator.annotations.GraphQLDeprecated
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import com.expediagroup.graphql.server.extensions.getValueFromDataLoader
import graphql.schema.DataFetchingEnvironment
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.intLiteral
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.notInSubQuery
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.queries.CategoryQuery.CategoryFilter
import suwayomi.tachidesk.graphql.queries.filter.BooleanFilter
import suwayomi.tachidesk.graphql.queries.filter.ComparableScalarFilter
import suwayomi.tachidesk.graphql.queries.filter.Filter
import suwayomi.tachidesk.graphql.queries.filter.HasGetOp
import suwayomi.tachidesk.graphql.queries.filter.IntFilter
import suwayomi.tachidesk.graphql.queries.filter.LongFilter
import suwayomi.tachidesk.graphql.queries.filter.OpAnd
import suwayomi.tachidesk.graphql.queries.filter.StringFilter
import suwayomi.tachidesk.graphql.queries.filter.andExistsWhere
import suwayomi.tachidesk.graphql.queries.filter.andFilterWithCompare
import suwayomi.tachidesk.graphql.queries.filter.andFilterWithCompareEntity
import suwayomi.tachidesk.graphql.queries.filter.andFilterWithCompareString
import suwayomi.tachidesk.graphql.queries.filter.applyOps
import suwayomi.tachidesk.graphql.queries.filter.coalesceDefault
import suwayomi.tachidesk.graphql.server.primitives.Cursor
import suwayomi.tachidesk.graphql.server.primitives.Order
import suwayomi.tachidesk.graphql.server.primitives.OrderBy
import suwayomi.tachidesk.graphql.server.primitives.PageInfo
import suwayomi.tachidesk.graphql.server.primitives.PaginationNeeds
import suwayomi.tachidesk.graphql.server.primitives.QueryResults
import suwayomi.tachidesk.graphql.server.primitives.applyBeforeAfter
import suwayomi.tachidesk.graphql.server.primitives.applySortAndGetPaginationInfo
import suwayomi.tachidesk.graphql.server.primitives.greaterNotUnique
import suwayomi.tachidesk.graphql.server.primitives.lessNotUnique
import suwayomi.tachidesk.graphql.types.MangaNodeList
import suwayomi.tachidesk.graphql.types.MangaType
import suwayomi.tachidesk.llm.LlmSearchService
import suwayomi.tachidesk.manga.impl.search.MangaSearchAliases
import suwayomi.tachidesk.manga.impl.search.MangaSearchRanking
import suwayomi.tachidesk.manga.impl.search.MangaSearchText
import suwayomi.tachidesk.manga.impl.search.MangaSearchVocabulary
import suwayomi.tachidesk.manga.model.table.CategoryMangaTable
import suwayomi.tachidesk.manga.model.table.CategoryTable
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.ChapterUserTable
import suwayomi.tachidesk.manga.model.table.MangaStatus
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.MangaUserTable
import suwayomi.tachidesk.manga.model.table.getWithUserData
import suwayomi.tachidesk.server.serverConfig
import java.util.concurrent.CompletableFuture

class MangaQuery {
    private companion object {
        /** An LLM plan matching fewer rows than this is treated as too strict and skipped. */
        const val LLM_MIN_HITS = 20L
    }

    /** One interpretation of the search text: what has to match, and what the hits are ranked by. */
    private data class SearchTier(
        val op: Op<Boolean>,
        /** At least this many rows have to match for the tier to be used. */
        val minHits: Long,
        /** The terms the tier matched with; [MangaSearchRanking] scores the hits with them. */
        val scoreTerms: List<String>,
    )

    @RequireAuth
    fun manga(
        dataFetchingEnvironment: DataFetchingEnvironment,
        id: Int,
    ): CompletableFuture<MangaType> = dataFetchingEnvironment.getValueFromDataLoader("MangaDataLoader", id)

    enum class MangaOrderBy(
        override val column: Column<*>,
    ) : OrderBy<MangaType> {
        ID(MangaTable.id),
        TITLE(MangaTable.title),
        IN_LIBRARY_AT(MangaUserTable.inLibraryAt),
        LAST_FETCHED_AT(MangaTable.lastFetchedAt),
        ;

        override fun greater(cursor: Cursor): Op<Boolean> =
            when (this) {
                ID -> MangaTable.id greater cursor.value.toInt()
                TITLE -> greaterNotUnique(MangaTable.title, MangaTable.id, cursor, String::toString)
                IN_LIBRARY_AT -> greaterNotUnique(MangaUserTable.inLibraryAt, MangaTable.id, cursor, String::toLong)
                LAST_FETCHED_AT -> greaterNotUnique(MangaTable.lastFetchedAt, MangaTable.id, cursor, String::toLong)
            }

        override fun less(cursor: Cursor): Op<Boolean> =
            when (this) {
                ID -> MangaTable.id less cursor.value.toInt()
                TITLE -> lessNotUnique(MangaTable.title, MangaTable.id, cursor, String::toString)
                IN_LIBRARY_AT -> lessNotUnique(MangaUserTable.inLibraryAt, MangaTable.id, cursor, String::toLong)
                LAST_FETCHED_AT -> lessNotUnique(MangaTable.lastFetchedAt, MangaTable.id, cursor, String::toLong)
            }

        override fun asCursor(type: MangaType): Cursor {
            val value =
                when (this) {
                    ID -> type.id.toString()
                    TITLE -> type.id.toString() + "-" + type.title
                    IN_LIBRARY_AT -> type.id.toString() + "-" + type.inLibraryAt.toString()
                    LAST_FETCHED_AT -> type.id.toString() + "-" + type.lastFetchedAt.toString()
                }
            return Cursor(value)
        }
    }

    data class MangaOrder(
        override val by: MangaOrderBy,
        override val byType: SortOrder? = null,
    ) : Order<MangaOrderBy>

    data class MangaCondition(
        val id: Int? = null,
        val sourceId: Long? = null,
        val url: String? = null,
        val title: String? = null,
        val thumbnailUrl: String? = null,
        val initialized: Boolean? = null,
        val artist: String? = null,
        val author: String? = null,
        val description: String? = null,
        val genre: List<String>? = null,
        val status: MangaStatus? = null,
        val inLibrary: Boolean? = null,
        val inLibraryAt: Long? = null,
        val realUrl: String? = null,
        val lastFetchedAt: Long? = null,
        val chaptersLastFetchedAt: Long? = null,
        val categoryIds: List<Int>? = null,
    ) : HasGetOp<Int> {
        override fun getOp(value: Int): Op<Boolean>? {
            val opAnd = OpAnd()
            opAnd.eq(id, MangaTable.id)
            opAnd.eq(sourceId, MangaTable.sourceReference)
            opAnd.eq(url, MangaTable.url)
            opAnd.eq(title, MangaTable.title)
            opAnd.eq(thumbnailUrl, MangaTable.thumbnail_url)
            opAnd.eq(initialized, MangaTable.initialized)
            opAnd.eq(artist, MangaTable.artist)
            opAnd.eq(author, MangaTable.author)
            opAnd.eq(description, MangaTable.description)
            opAnd.andWhereAll(genre) { MangaTable.genre like "%$it%" }
            opAnd.eq(status?.value, MangaTable.status)
            opAnd.eq(inLibrary, coalesceDefault(MangaUserTable.inLibrary, false))
            opAnd.eq(inLibraryAt, coalesceDefault(MangaUserTable.inLibraryAt, 0))
            opAnd.eq(realUrl, MangaTable.realUrl)
            opAnd.eq(lastFetchedAt, MangaTable.lastFetchedAt)
            opAnd.eq(chaptersLastFetchedAt, MangaTable.chaptersLastFetchedAt)
            opAnd.andWhere(
                andExistsWhere(
                    userId = value,
                    query =
                        CategoryMangaTable
                            .innerJoin(CategoryTable)
                            .select(intLiteral(1)),
                    correlation =
                        (CategoryMangaTable.manga eq MangaTable.id) and
                            (CategoryMangaTable.user eq value),
                    filter =
                        categoryIds?.let {
                            CategoryFilter(
                                id = IntFilter(`in` = categoryIds),
                            )
                        },
                ),
            )

            return opAnd.op
        }
    }

    data class MangaStatusFilter(
        override val isNull: Boolean? = null,
        override val equalTo: MangaStatus? = null,
        override val notEqualTo: MangaStatus? = null,
        override val notEqualToAll: List<MangaStatus>? = null,
        override val notEqualToAny: List<MangaStatus>? = null,
        override val distinctFrom: MangaStatus? = null,
        override val distinctFromAll: List<MangaStatus>? = null,
        override val distinctFromAny: List<MangaStatus>? = null,
        override val notDistinctFrom: MangaStatus? = null,
        override val `in`: List<MangaStatus>? = null,
        override val notIn: List<MangaStatus>? = null,
        override val lessThan: MangaStatus? = null,
        override val lessThanOrEqualTo: MangaStatus? = null,
        override val greaterThan: MangaStatus? = null,
        override val greaterThanOrEqualTo: MangaStatus? = null,
    ) : ComparableScalarFilter<MangaStatus> {
        fun asIntFilter() =
            IntFilter(
                equalTo = equalTo?.value,
                notEqualTo = notEqualTo?.value,
                notEqualToAll = notEqualToAll?.map { it.value },
                notEqualToAny = notEqualToAny?.map { it.value },
                distinctFrom = distinctFrom?.value,
                distinctFromAll = distinctFromAll?.map { it.value },
                distinctFromAny = distinctFromAny?.map { it.value },
                notDistinctFrom = notDistinctFrom?.value,
                `in` = `in`?.map { it.value },
                notIn = notIn?.map { it.value },
                lessThan = lessThan?.value,
                lessThanOrEqualTo = lessThanOrEqualTo?.value,
                greaterThan = greaterThan?.value,
                greaterThanOrEqualTo = greaterThanOrEqualTo?.value,
            )
    }

    data class MangaFilter(
        val id: IntFilter? = null,
        val sourceId: LongFilter? = null,
        val url: StringFilter? = null,
        val title: StringFilter? = null,
        val thumbnailUrl: StringFilter? = null,
        val initialized: BooleanFilter? = null,
        val artist: StringFilter? = null,
        val author: StringFilter? = null,
        val description: StringFilter? = null,
        val genre: StringFilter? = null,
        val status: MangaStatusFilter? = null,
        val inLibrary: BooleanFilter? = null,
        val inLibraryAt: LongFilter? = null,
        val realUrl: StringFilter? = null,
        val lastFetchedAt: LongFilter? = null,
        val chaptersLastFetchedAt: LongFilter? = null,
        val categoryId: IntFilter? = null,
        val hasDownloadedChapters: BooleanFilter? = null,
        val hasUnreadChapters: BooleanFilter? = null,
        val hasBookmarkedChapters: BooleanFilter? = null,
        val hasDuplicateChapters: BooleanFilter? = null,
        override val and: List<MangaFilter>? = null,
        override val or: List<MangaFilter>? = null,
        override val not: MangaFilter? = null,
    ) : Filter<MangaFilter, Int> {
        override fun getOpList(value: Int): List<Op<Boolean>> =
            listOfNotNull(
                andFilterWithCompareEntity(MangaTable.id, id),
                andFilterWithCompare(MangaTable.sourceReference, sourceId),
                andFilterWithCompareString(MangaTable.url, url),
                andFilterWithCompareString(MangaTable.title, title),
                andFilterWithCompareString(MangaTable.thumbnail_url, thumbnailUrl),
                andFilterWithCompare(MangaTable.initialized, initialized),
                andFilterWithCompareString(MangaTable.artist, artist),
                andFilterWithCompareString(MangaTable.author, author),
                andFilterWithCompareString(MangaTable.description, description),
                andFilterWithCompareString(MangaTable.genre, genre),
                andFilterWithCompare(MangaTable.status, status?.asIntFilter()),
                andFilterWithCompare(coalesceDefault(MangaUserTable.inLibrary, false), inLibrary),
                andFilterWithCompare(coalesceDefault(MangaUserTable.inLibraryAt, 0), inLibraryAt),
                andFilterWithCompareString(MangaTable.realUrl, realUrl),
                andFilterWithCompare(MangaTable.lastFetchedAt, lastFetchedAt),
                andFilterWithCompare(MangaTable.chaptersLastFetchedAt, chaptersLastFetchedAt),
                // Chapter-level filters via subqueries
                hasDownloadedChapters.toChapterExistsOp { ChapterTable.isDownloaded eq true },
                hasUnreadChapters.toChapterUserExistsOp(value) { coalesceDefault(ChapterUserTable.isRead, false) eq false },
                hasBookmarkedChapters.toChapterUserExistsOp(value) { coalesceDefault(ChapterUserTable.isBookmarked, false) eq true },
                hasDuplicateChapters.toDuplicateExistsOp(),
                andExistsWhere(
                    userId = value,
                    query =
                        CategoryMangaTable
                            .innerJoin(CategoryTable)
                            .select(intLiteral(1)),
                    correlation =
                        (CategoryMangaTable.manga eq MangaTable.id) and
                            (CategoryMangaTable.user eq value),
                    filter =
                        if (this.isFilteringForDefaultCategory()) {
                            CategoryFilter()
                        } else {
                            categoryId?.let {
                                CategoryFilter(
                                    id = categoryId,
                                )
                            }
                        },
                    not = this.isFilteringForDefaultCategory(),
                ),
            )

        fun isFilteringForDefaultCategory(inverted: Boolean = false): Boolean =
            this.categoryId?.isNull == !inverted ||
                this.or?.any { it.isFilteringForDefaultCategory(inverted) } == true ||
                this.and?.any { it.isFilteringForDefaultCategory(inverted) } == true ||
                this.not?.isFilteringForDefaultCategory(!inverted) == true

        companion object {
            private fun BooleanFilter?.toChapterExistsOp(condition: () -> Op<Boolean>): Op<Boolean>? {
                val filter = this ?: return null
                return when (filter.equalTo) {
                    true -> MangaTable.id.inSubQuery(
                        ChapterTable.select(ChapterTable.manga).where { condition() },
                    )
                    false -> MangaTable.id.notInSubQuery(
                        ChapterTable.select(ChapterTable.manga).where { condition() },
                    )
                    else -> null
                }
            }

            private fun BooleanFilter?.toChapterUserExistsOp(userId: Int, condition: () -> Op<Boolean>): Op<Boolean>? {
                val filter = this ?: return null
                return when (filter.equalTo) {
                    true -> MangaTable.id.inSubQuery(
                        ChapterTable.getWithUserData(userId).select(ChapterTable.manga).where { condition() },
                    )
                    false -> MangaTable.id.notInSubQuery(
                        ChapterTable.getWithUserData(userId).select(ChapterTable.manga).where { condition() },
                    )
                    else -> null
                }
            }

            private fun BooleanFilter?.toDuplicateExistsOp(): Op<Boolean>? {
                val filter = this ?: return null
                return when (filter.equalTo) {
                    true -> MangaTable.id.inSubQuery(
                        ChapterTable
                            .select(ChapterTable.manga)
                            .where { ChapterTable.chapter_number greaterEq 0f }
                            .groupBy(ChapterTable.manga, ChapterTable.chapter_number)
                            .having { ChapterTable.chapter_number.count() greater 1 },
                    )
                    false -> MangaTable.id.notInSubQuery(
                        ChapterTable
                            .select(ChapterTable.manga)
                            .where { ChapterTable.chapter_number greaterEq 0f }
                            .groupBy(ChapterTable.manga, ChapterTable.chapter_number)
                            .having { ChapterTable.chapter_number.count() greater 1 },
                    )
                    else -> null
                }
            }
        }
    }

    @RequireAuth
    fun mangas(
        @GraphQLIgnore
        userId: Int,
        dataFetchingEnvironment: DataFetchingEnvironment,
        condition: MangaCondition? = null,
        filter: MangaFilter? = null,
        /**
         * Free text search over the normalized title/author/artist/genre index
         * ([suwayomi.tachidesk.manga.impl.search.MangaSearchText]).
         *
         * The query is folded (traditional -> simplified, katakana -> hiragana, lowercase), split into
         * tokens and every token has to match, so "FGO 玛修" finds `[社团] ... (Fate/Grand Order) 瑪修`.
         */
        search: String? = null,
        @GraphQLDeprecated(
            "Replaced with order",
            replaceWith = ReplaceWith("order"),
        )
        orderBy: MangaOrderBy? = null,
        @GraphQLDeprecated(
            "Replaced with order",
            replaceWith = ReplaceWith("order"),
        )
        orderByType: SortOrder? = null,
        order: List<MangaOrder>? = null,
        before: Cursor? = null,
        after: Cursor? = null,
        first: Int? = null,
        last: Int? = null,
        offset: Int? = null,
    ): MangaNodeList {
        val strictTokens = MangaSearchText.queryTokens(search)
        val plan = MangaSearchVocabulary.plan(search)

        // Literal interpretation: every token has to appear somewhere. Keeps precision when the words do
        // exist in the library ("女主被NTR怀孕" stays 10 hits instead of degenerating to all NTR).
        val strictOps: List<Op<Boolean>> =
            strictTokens.map { token -> MangaTable.searchText like "%$token%" }
        val strictOp: Op<Boolean>? = strictOps.reduceOrNull { acc, op -> acc and op }

        // Relaxed interpretation: unknown words dropped, synonyms OR-ed inside a token, genre tags OR-ed.
        val textOps: List<Op<Boolean>> =
            plan.textGroups.mapNotNull { group ->
                val alternatives: List<Op<Boolean>> = group.map { token -> MangaTable.searchText like "%$token%" }
                alternatives.reduceOrNull { acc, op -> acc or op }
            }
        val tagOps: List<Op<Boolean>> =
            plan.anyTerms.map { token -> MangaTable.searchText like "%$token%" }
        val tagOp: Op<Boolean>? = tagOps.reduceOrNull { acc, op -> acc or op }
        val relaxedOp: Op<Boolean>? = (textOps + listOfNotNull(tagOp)).reduceOrNull { acc, op -> acc and op }

        // LLM interpretation (optional): the model decides what is a title, what is a tag and whether the
        // tags have to all match. Only used when enabled and the model answered; otherwise everything below
        // stays exactly as before, which is also the degradation path.
        val llmPlan = LlmSearchService.plan(search)
        val llmOp: Op<Boolean>? = llmPlan?.let { plan ->
            // The model knows the meaning but not this library's wording, so every term it returns is widened
            // with the synonym table ("上位女" -> "御姐", "ブルアカ" -> "蔚蓝档案") before it is used. Titles
            // need this as much as tags: a series name is returned as a title term and the library stores it
            // under whichever language the uploader used.
            fun opFor(term: String): Op<Boolean> {
                val alternatives: List<Op<Boolean>> =
                    (listOf(term) + MangaSearchAliases.aliasesOf(term))
                        .distinct()
                        .map { candidate -> MangaTable.searchText like "%$candidate%" }
                return alternatives.reduce { acc, op -> acc or op }
            }

            // Several writings of one entity ("舰これ"/"舰c"/"舰队collection") are alternatives, not extra
            // requirements: OR them inside the group, AND the groups (a series plus one of its characters).
            val titleOps: List<Op<Boolean>> =
                plan.titleGroups().map { group ->
                    group.map { term -> opFor(term) }.reduce { acc, op -> acc or op }
                }

            val ops = titleOps.toMutableList()
            if (plan.tags.isNotEmpty()) {
                val opsForTags: List<Op<Boolean>> = plan.tags.map { tag -> opFor(tag) }
                ops +=
                    if (plan.tagsAreAny) {
                        opsForTags.reduce { acc, op -> acc or op }
                    } else {
                        opsForTags.reduce { acc, op -> acc and op }
                    }
            }
            plan.author?.let { author -> ops += opFor(author) }
            ops.reduceOrNull { acc, op -> acc and op }
        }

        // What the LLM tier's hits are ranked by: the writings the model proposed, one per entity. Aliases
        // are not added here: a book whose series is only stored under a cross language alias would score 0
        // either way, and the alias expansion of the recall path already decided it is a hit.
        val llmScoreTerms: List<String> =
            llmPlan?.let { plan ->
                plan.titleGroups().flatten() + plan.tags + listOfNotNull(plan.author)
            }.orEmpty()

        // Always try the literal interpretation first: it is the only one that keeps "虐心的NTR" at the few
        // books matching both words instead of degenerating to all NTR entries.
        val strictFirst = strictOp != null && strictTokens.size > 1

        val paginationNeeds = PaginationNeeds.of(dataFetchingEnvironment)

        /**
         * Runs one interpretation of the search and returns its hits.
         *
         * [scoreTerms] are the words this interpretation matched with; they decide the order of the hits
         * when the caller did not ask for one itself (see [MangaSearchRanking]).
         */
        fun runQuery(
            searchOp: Op<Boolean>?,
            scoreTerms: List<String> = emptyList(),
        ): QueryResults<Int?> =
            transaction {
                val res = MangaTable.getWithUserData(userId).selectAll().applyOps(userId, condition, filter)
                if (searchOp != null) {
                    res.adjustWhere { (this ?: Op.TRUE) and searchOp }
                }

                val baseSort = listOf(MangaOrder(MangaOrderBy.ID, SortOrder.ASC))
                val deprecatedSort = listOfNotNull(orderBy?.let { MangaOrder(orderBy, orderByType) })
                val actualSort = (order.orEmpty() + deprecatedSort + baseSort)

                // Relevance ordering is only possible for a plain, forward window of a search: a cursor is
                // built from a real column, and an explicit sort is the caller's own choice.
                val relevanceTerms =
                    scoreTerms.takeIf {
                        it.isNotEmpty() &&
                            searchOp != null &&
                            before == null && after == null && last == null &&
                            order.isNullOrEmpty() && orderBy == null &&
                            serverConfig.searchRelevanceSortEnabled.value
                    }

                // The tier is only accepted when it has enough hits, so the count is needed anyway; above
                // [MangaSearchRanking.MAX_SORTED_HITS] it is also the reason not to sort.
                val score = relevanceTerms?.let { terms ->
                    val hits = res.copy().count()
                    MangaSearchRanking
                        .score(terms)
                        ?.takeIf { hits in 1L..MangaSearchRanking.MAX_SORTED_HITS }
                        ?.let { expression -> expression to hits }
                }

                if (score == null) {
                    val (total, firstResult, lastResult) =
                        res.applySortAndGetPaginationInfo(
                            actualSort,
                            before,
                            last,
                            MangaTable.id,
                            paginationNeeds,
                        )

                    res.applyBeforeAfter(
                        before = before,
                        after = after,
                        orderBy = order?.firstOrNull()?.by ?: MangaOrderBy.ID,
                        orderByType = order?.firstOrNull()?.byType,
                    )

                    if (first != null) {
                        res.limit(first).offset(offset?.toLong() ?: 0)
                    } else if (last != null) {
                        res.limit(last)
                    }

                    return@transaction QueryResults(total, firstResult, lastResult, res.toList())
                }

                val (expression, hits) = score

                // Same as the id sort above: the best match first, the oldest book first among equals, so
                // paging through the result stays stable.
                fun sortedQuery(descending: Boolean) =
                    res
                        .copy()
                        .orderBy(expression to SortOrder.DESC)
                        .orderBy(
                            MangaTable.id to if (descending) SortOrder.DESC else SortOrder.ASC,
                        )

                val (firstKey, lastKey) =
                    if (paginationNeeds.bounds) {
                        sortedQuery(descending = false).limit(1).firstOrNull()?.get(MangaTable.id)?.value to
                            sortedQuery(descending = true).limit(1).firstOrNull()?.get(MangaTable.id)?.value
                    } else {
                        null to null
                    }

                val sorted = sortedQuery(descending = false)
                if (first != null) {
                    sorted.limit(first).offset(offset?.toLong() ?: 0)
                }

                QueryResults(hits, firstKey, lastKey, sorted.toList())
            }

        // Last resort: at least one of the known tokens has to match.
        val orTerms = (plan.allTerms + plan.anyTerms).distinct()
        val orOps: List<Op<Boolean>> = orTerms.map { token -> MangaTable.searchText like "%$token%" }
        val orOp: Op<Boolean>? = orOps.takeIf { it.size > 1 }?.reduce { acc, op -> acc or op }

        // A candidate is only accepted when it matches at least [minHits] rows. The LLM is stricter than the
        // rule based tiers ("傲慢的上位女" -> 傲慢 AND 上位女 = 3 books), and a precise-but-empty result is
        // worse for browsing than the broader rule based one, so a thin LLM plan falls through.
        val candidates =
            buildList {
                if (llmOp != null) add(SearchTier(llmOp, LLM_MIN_HITS, llmScoreTerms))
                if (strictFirst && strictOp != null) add(SearchTier(strictOp, 1L, strictTokens))
                if (relaxedOp != null) add(SearchTier(relaxedOp, 1L, plan.allTerms + plan.anyTerms))
                if (orOp != null) add(SearchTier(orOp, 1L, orTerms))
            }

        var queryResults: QueryResults<Int?>? = null
        for (tier in candidates) {
            val attempt = runQuery(tier.op, tier.scoreTerms)
            if (attempt.total >= tier.minHits && (attempt.total > 0L || attempt.results.isNotEmpty())) {
                queryResults = attempt
                break
            }
            if (queryResults == null) queryResults = attempt
        }
        val finalResults = queryResults ?: runQuery(null)

        val getAsCursor: (MangaType) -> Cursor = (order?.firstOrNull()?.by ?: MangaOrderBy.ID)::asCursor

        val resultsAsType = finalResults.results.map { MangaType(it) }

        return MangaNodeList(
            resultsAsType,
            if (resultsAsType.isEmpty()) {
                emptyList()
            } else {
                listOfNotNull(
                    resultsAsType.firstOrNull()?.let {
                        MangaNodeList.MangaEdge(
                            getAsCursor(it),
                            it,
                        )
                    },
                    resultsAsType.lastOrNull()?.let {
                        MangaNodeList.MangaEdge(
                            getAsCursor(it),
                            it,
                        )
                    },
                )
            },
            pageInfo =
                PageInfo(
                    hasNextPage = finalResults.lastKey != resultsAsType.lastOrNull()?.id,
                    hasPreviousPage = finalResults.firstKey != resultsAsType.firstOrNull()?.id,
                    startCursor = resultsAsType.firstOrNull()?.let { getAsCursor(it) },
                    endCursor = resultsAsType.lastOrNull()?.let { getAsCursor(it) },
                ),
            totalCount = finalResults.total.toInt(),
        )
    }
}
