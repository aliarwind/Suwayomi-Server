package suwayomi.tachidesk.manga.impl.search

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.like
import suwayomi.tachidesk.manga.model.table.MangaTable

/**
 * How a search term is matched against [MangaTable.searchText].
 *
 * The index stores one token per word, each prefixed with the field it came from, so a term that spans
 * several words — "blue archive", an alias of ブルアカ — is not one contiguous substring of the column any
 * more: it is stored as `X:blue X:archive`. Matching it literally finds nothing, and worse, the term then
 * also looks absent from the library, so the vocabulary drops it from the plan (measured: five library
 * entries of `ブルアカ` disappeared exactly this way). A multi word term therefore matches when all of its
 * words appear, anywhere.
 *
 * Single word terms keep plain substring matching: Chinese has no word boundaries and the index stores whole
 * phrases, so "怀孕" has to keep matching the token of "怀孕中".
 */
object MangaSearchMatch {
    /**
     * The words [term] is looked up by. Empty when the term has no letters or digits at all (which matches
     * everything, like the empty `LIKE` pattern did before).
     */
    fun wordsOf(term: String): List<String> = MangaSearchText.tokensOf(term).distinct()

    /** The `WHERE` side: [term] has to match the indexed text of a row. */
    fun op(term: String): Op<Boolean> {
        val words = wordsOf(term)
        val single = words.singleOrNull()
        if (single != null || words.isEmpty()) {
            return MangaTable.searchText like "%${single ?: term}%"
        }

        val ops: List<Op<Boolean>> = words.map { word -> MangaTable.searchText like "%$word%" }
        return ops.reduce { acc, next -> acc and next }
    }

    /**
     * [term] matched only where names live (the `N:` tokens), as a whole token.
     *
     * The query said "作者是ie", so the answer is the artist called ie — not the 822 books whose index
     * merely contains `ie` inside `piece`, `frieren` or `friend`. Measured on the live library: `ie`
     * matches 961 rows as a substring but 139 as a name token, while real names are untouched
     * (`武田弘光` 94 both ways, `あんこまん` 267 both ways — they only ever appear as `N:` tokens).
     */
    fun nameOp(term: String): Op<Boolean> {
        val words = wordsOf(term)
        if (words.isEmpty()) return op(term)

        val marker = MangaSearchText.Field.NAME.marker
        val ops: List<Op<Boolean>> = words.map { word -> MangaTable.searchText like "%$marker:$word %" }
        return ops.reduce { acc, next -> acc and next }
    }

    /**
     * The in-memory side, for the vocabulary and the reranker's group counting. Takes [words] rather than the
     * term because these run inside a loop over every indexed text.
     */
    fun matches(
        words: List<String>,
        text: String,
    ): Boolean {
        val single = words.singleOrNull()
        if (single != null || words.isEmpty()) return text.contains(single.orEmpty())
        return words.all { word -> text.contains(word) }
    }
}
