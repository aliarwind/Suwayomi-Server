/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package suwayomi.tachidesk.graphql.types

/** One ranked library hit: the book, a 0-100 relevance score and the model's one line reason. */
class LlmSearchHit(
    val manga: MangaType,
    val score: Int,
    val reason: String,
)

/**
 * Result of the optional AI search ([suwayomi.tachidesk.llm.LlmLibrarySearch]).
 *
 * [enabled] is false when the feature is off, [degraded] is true when the model answered unusably (the
 * caller then shows nothing extra instead of a wrong ranking). [candidates] and [tookMs] exist for tuning.
 */
class LlmSearchResult(
    val enabled: Boolean,
    val degraded: Boolean,
    val parsed: Boolean,
    val candidates: Int,
    val tookMs: Int,
    val hits: List<LlmSearchHit>,
)
