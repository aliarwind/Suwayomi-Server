package suwayomi.tachidesk.llm

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.serialization.Serializable
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

    companion object {
        const val TAG_MODE_AND = "AND"
        const val TAG_MODE_OR = "OR"
        const val MAX_TITLE_TERMS = 5
        const val MAX_TAGS = 6
        const val MAX_TERM_LENGTH = 40
    }
}
