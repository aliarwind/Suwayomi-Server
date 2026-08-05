package suwayomi.tachidesk.graphql.types

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import suwayomi.tachidesk.manga.impl.RefreshManager

class RefreshStatusType(
    status: RefreshManager.RefreshStatus,
) {
    val isRunning: Boolean = status.isRunning
    val sourceId: Long? = status.sourceId
    val dateFrom: Long? = status.dateFrom
    val dateTo: Long? = status.dateTo
    val totalManga: Int = status.totalManga
    val refreshedManga: Int = status.refreshedManga
    val totalRefreshed: Int = status.totalRefreshed
    val totalFailed: Int = status.totalFailed
    val refreshedMangaList: List<MangaRefreshResultType> =
        status.refreshedMangaList.map {
            MangaRefreshResultType(
                mangaId = it.mangaId,
                mangaTitle = it.mangaTitle,
            )
        }
    val failedMangaList: List<MangaRefreshErrorType> =
        status.failedMangaList.map {
            MangaRefreshErrorType(
                mangaId = it.mangaId,
                mangaTitle = it.mangaTitle,
                error = it.error,
            )
        }
}
