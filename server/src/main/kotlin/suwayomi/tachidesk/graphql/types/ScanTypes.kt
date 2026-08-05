package suwayomi.tachidesk.graphql.types

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

/**
 * Shared GraphQL types for scan-related queries and subscriptions.
 */
data class FixedChapterType(
    val chapterId: Int,
    val mangaId: Int,
    val mangaTitle: String,
    val chapterName: String,
    val pageCountFound: Int,
    val wasMarkedDownloaded: Boolean,
    val wasPageCountWrong: Boolean,
)

data class ScanErrorType(
    val chapterId: Int?,
    val mangaId: Int?,
    val mangaTitle: String?,
    val error: String,
)
