package suwayomi.tachidesk.graphql.types

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import suwayomi.tachidesk.manga.impl.RefreshManager

class RefreshProgressType(
    event: RefreshManager.RefreshProgressEvent,
) {
    val type: RefreshEventTypeEnum =
        when (event.type) {
            RefreshManager.RefreshEventType.STARTED -> RefreshEventTypeEnum.STARTED
            RefreshManager.RefreshEventType.REFRESHING_MANGA -> RefreshEventTypeEnum.REFRESHING_MANGA
            RefreshManager.RefreshEventType.MANGA_REFRESHED -> RefreshEventTypeEnum.MANGA_REFRESHED
            RefreshManager.RefreshEventType.MANGA_FAILED -> RefreshEventTypeEnum.MANGA_FAILED
            RefreshManager.RefreshEventType.STOPPED -> RefreshEventTypeEnum.STOPPED
            RefreshManager.RefreshEventType.COMPLETED -> RefreshEventTypeEnum.COMPLETED
        }

    val totalManga: Int = event.totalManga
    val refreshedManga: Int = event.refreshedManga
    val currentMangaTitle: String? = event.currentMangaTitle
    val mangaResult: MangaRefreshResultType? =
        event.mangaResult?.let {
            MangaRefreshResultType(
                mangaId = it.mangaId,
                mangaTitle = it.mangaTitle,
            )
        }
    val mangaError: MangaRefreshErrorType? =
        event.mangaError?.let {
            MangaRefreshErrorType(
                mangaId = it.mangaId,
                mangaTitle = it.mangaTitle,
                error = it.error,
            )
        }
    val totalRefreshed: Int = event.totalRefreshed
    val totalFailed: Int = event.totalFailed
}

enum class RefreshEventTypeEnum {
    STARTED,
    REFRESHING_MANGA,
    MANGA_REFRESHED,
    MANGA_FAILED,
    STOPPED,
    COMPLETED,
}
