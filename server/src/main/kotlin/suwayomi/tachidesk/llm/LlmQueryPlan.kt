package suwayomi.tachidesk.llm

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.serialization.Serializable
import suwayomi.tachidesk.manga.impl.search.MangaSearchAliases
import suwayomi.tachidesk.manga.impl.search.MangaSearchText

/**
 * What a free text query means, according to the language model.
 *
 * [titleTerms] are work / character / series names, [tags] are library tags ("NTR", "巨乳"), [author] is an
 * explicitly named author or circle. [tagMode] decides whether a tag list means "all of them" (AND, the
 * default) or "any of them" (OR), which is the part the rule based vocabulary cannot express.
 */
@Serializable
data class LlmQueryPlan(
    val titleTerms: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val tagMode: String = TAG_MODE_AND,
    val author: String? = null,
) {
    val tagsAreAny: Boolean get() = tagMode.equals(TAG_MODE_OR, ignoreCase = true)

    fun isEmpty(): Boolean = titleTerms.isEmpty() && tags.isEmpty() && author.isNullOrBlank()

    /**
     * Normalized and capped copy: the index is stored normalized, so every term has to go through
     * [MangaSearchText.normalize] before it is matched with LIKE. Absurd terms are dropped instead of being
     * forwarded to SQL.
     */
    fun sanitized(): LlmQueryPlan {
        fun clean(terms: List<String>, max: Int): List<String> =
            terms
                .map { MangaSearchText.normalize(it).trim() }
                .filter { it.isNotEmpty() && it.length <= MAX_TERM_LENGTH }
                .distinct()
                .take(max)

        return LlmQueryPlan(
            titleTerms = clean(titleTerms, MAX_TITLE_TERMS),
            tags = clean(tags, MAX_TAGS),
            tagMode = if (tagsAreAny) TAG_MODE_OR else TAG_MODE_AND,
            author = author?.let { MangaSearchText.normalize(it).trim() }?.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * [titleTerms] grouped by "same entity, written differently".
     *
     * The model is asked for one writing per entity, but it still answers `[舰これ, 舰c, 舰队collection]`.
     * Callers AND the terms (every one has to appear), which for three writings of the same series leaves
     * only the books that use all three - the synonyms cancel each other out. Two terms are treated as the
     * same entity when one contains the other or when the synonym table relates them, so the group is OR-ed
     * while genuinely different entities (a series and a character) stay in separate groups.
     */
    fun titleGroups(aliases: (String) -> List<String> = MangaSearchAliases::aliasesOf): List<List<String>> {
        fun variants(term: String): List<String> = (listOf(term) + aliases(term)).distinct()

        fun sameEntity(
            a: String,
            b: String,
        ): Boolean = variants(a).any { it.contains(b) } || variants(b).any { it.contains(a) }

        val clusters = mutableListOf<MutableList<String>>()
        titleTerms.forEach { term ->
            val related = clusters.filter { cluster -> cluster.any { other -> sameEntity(term, other) } }
            val cluster = mutableListOf(term)
            related.forEach { cluster += it }
            clusters -= related.toSet()
            clusters += cluster
        }

        // Rebuilt in the model's order so the output does not depend on the merge order.
        return clusters.map { cluster -> titleTerms.filter { term -> term in cluster } }
    }

    companion object {
        const val TAG_MODE_AND = "AND"
        const val TAG_MODE_OR = "OR"
        const val MAX_TITLE_TERMS = 5
        const val MAX_TAGS = 6
        const val MAX_TERM_LENGTH = 40
    }
}
