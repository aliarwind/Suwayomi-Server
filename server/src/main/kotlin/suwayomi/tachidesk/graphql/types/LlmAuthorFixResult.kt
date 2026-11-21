package suwayomi.tachidesk.graphql.types

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

/** One manga whose author the model would rewrite; [before] is what the library stores today. */
data class LlmAuthorFixChange(
    val mangaId: Int,
    val title: String,
    val before: String?,
    val author: String,
    val artist: String,
)

/**
 * Outcome of [suwayomi.tachidesk.graphql.mutations.LlmMutation.llmFixAuthors].
 *
 * [changes] is the preview (all of them on a dry run, the same list after applying), [candidates] counts
 * the rows that needed fixing and [changed] how many the model actually answered for.
 */
data class LlmAuthorFixResult(
    val enabled: Boolean,
    val dryRun: Boolean,
    val candidates: Int,
    val changed: Int,
    val changes: List<LlmAuthorFixChange>,
)
