package suwayomi.tachidesk.graphql.types

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

/**
 * One book as [suwayomi.tachidesk.graphql.mutations.LlmMutation.llmStructureTitles] would store it.
 *
 * [rejects] lists the fields the model proposed and the source contradicted (`series:アリス・マーガトロイド`,
 * `cleanTitle:…`); it is only shown, never persisted.
 */
data class LlmTitleStructureRow(
    val mangaId: Int,
    val title: String,
    val cleanTitle: String?,
    val series: String?,
    val characters: List<String>,
    val circle: String?,
    val artist: String?,
    val lang: String?,
    val aliases: List<String>,
    val flags: List<String>,
    val rejects: List<String>,
)

/**
 * Outcome of one batch of [suwayomi.tachidesk.graphql.mutations.LlmMutation.llmStructureTitles].
 *
 * The backfill is caller-driven: a script asks for [limit] books, then calls again while [candidates]
 * equals the limit. Books the model could not answer (API failure) are not written, so they stay pending
 * and are retried on the next call; [written] below [candidates] therefore means "retry".
 */
data class LlmTitleStructureResult(
    val enabled: Boolean,
    val dryRun: Boolean,
    val version: Int,
    val candidates: Int,
    val asked: Int,
    val written: Int,
    val rows: List<LlmTitleStructureRow>,
)
