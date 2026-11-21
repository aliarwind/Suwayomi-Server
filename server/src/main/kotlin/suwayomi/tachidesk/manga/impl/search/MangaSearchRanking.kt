package suwayomi.tachidesk.manga.impl.search

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.Case
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.PlusOp
import org.jetbrains.exposed.v1.core.intLiteral
import org.jetbrains.exposed.v1.core.like
import suwayomi.tachidesk.manga.impl.search.MangaSearchText.Field
import suwayomi.tachidesk.manga.model.table.MangaTable

/**
 * Orders the hits of a free text search by where the words were found.
 *
 * [MangaSearchText] is a flat bag of words: before the field markers, a query like "ie" matched the circle
 * `ie` exactly as well as the `ie` buried inside `iesu`, and the books came back in id order — so the book
 * the user meant was somewhere in the middle of 687 hits. The markers turn that into a score:
 *
 * ```
 * score = sum over terms of (title 4 + series 3 + character 3 + name 2 + alias 2 + tag 1)
 * ```
 *
 * Every term is matched as an *exact token* (`LIKE '%N:ie %'`), not a substring: the marker plus the
 * trailing space the index ends with is what makes `ie` different from `iesu`. Recall never uses these
 * patterns, so the set of hits (`totalCount`) is exactly the same as with plain `LIKE '%ie%'`.
 *
 * A title outweighs everything because the library is browsed by work name; series and characters follow
 * because they are what the model adds to a title that only carries the kana; circle/artist/aliases come
 * next, and genre tags last (a tag hit is the weakest signal a book is about what was typed).
 */
object MangaSearchRanking {
    /**
     * Above this many hits the order is left to the database (id ascending).
     *
     * The score cannot use an index — it is a `CASE` per term and field over every matching row — so a
     * query like `汉化` (7k+ hits) would pay for thousands of `LIKE` evaluations to produce an order nobody
     * scrolls to. Search still works above the limit, it just is not reordered.
     */
    const val MAX_SORTED_HITS = 5_000L

    /** Upper bound for the tokens one search is scored with; keeps the generated `CASE` chain bounded. */
    const val MAX_SCORE_HITS = 16

    /** `(field, weight)` pairs, strongest first. Also the order the `CASE` branches are emitted in. */
    private val WEIGHTS =
        listOf(
            Field.TITLE to 4,
            Field.SERIES to 3,
            Field.CHARACTER to 3,
            Field.NAME to 2,
            Field.ALIAS to 2,
            Field.TAG to 1,
        )

    /**
     * The score of one row for the given [terms], or null when there is nothing to score.
     *
     * [terms] are the words the winning search tier matched with (raw tokens, vocabulary groups, or the
     * model's plan); they are re-split into index tokens here, because a term can be a multi word title
     * ("fate grand order") while the index stores each word on its own.
     */
    fun score(terms: Collection<String>): Expression<Int>? {
        val tokens =
            terms
                .asSequence()
                .flatMap { term -> MangaSearchText.tokensOf(term).asSequence() }
                .distinct()
                .take(MAX_SCORE_HITS)
                .toList()
        if (tokens.isEmpty()) return null

        val cases: List<Expression<Int>> =
            tokens.flatMap { token ->
                WEIGHTS.map { (field, weight) -> caseFor(field, weight, token) }
            }

        return cases.reduce { acc, next -> PlusOp(acc, next, IntegerColumnType()) }
    }

    /**
     * `CASE WHEN search_text LIKE '%T:token %' THEN weight ELSE 0 END`.
     *
     * `search_text` is nullable, but a `LIKE` against null is null and the `CASE` falls through to `ELSE`.
     */
    private fun caseFor(
        field: Field,
        weight: Int,
        token: String,
    ): Expression<Int> =
        Case()
            .When(MangaTable.searchText like "%${field.marker}:$token %", intLiteral(weight))
            .Else(intLiteral(0))
}
