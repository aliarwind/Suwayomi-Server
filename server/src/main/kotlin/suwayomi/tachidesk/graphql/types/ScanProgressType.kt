package suwayomi.tachidesk.graphql.types

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import suwayomi.tachidesk.manga.impl.ScanManager

class ScanProgressType(
    event: ScanManager.ScanProgressEvent,
) {
    val type: ScanEventTypeEnum =
        when (event.type) {
            ScanManager.ScanEventType.STARTED -> ScanEventTypeEnum.STARTED
            ScanManager.ScanEventType.SCANNING_MANGA -> ScanEventTypeEnum.SCANNING_MANGA
            ScanManager.ScanEventType.CHAPTER_FIXED -> ScanEventTypeEnum.CHAPTER_FIXED
            ScanManager.ScanEventType.CHAPTER_ERROR -> ScanEventTypeEnum.CHAPTER_ERROR
            ScanManager.ScanEventType.COMPLETED -> ScanEventTypeEnum.COMPLETED
        }

    val totalManga: Int = event.totalManga
    val scannedManga: Int = event.scannedManga
    val mangaTitle: String? = event.mangaTitle
    val chapterFixed: FixedChapterType? =
        event.chapterFixed?.let {
            FixedChapterType(
                chapterId = it.chapterId,
                mangaId = it.mangaId,
                mangaTitle = it.mangaTitle,
                chapterName = it.chapterName,
                pageCountFound = it.pageCountFound,
                wasMarkedDownloaded = it.wasMarkedDownloaded,
                wasPageCountWrong = it.wasPageCountWrong,
            )
        }
    val chapterError: ScanErrorType? =
        event.chapterError?.let {
            ScanErrorType(
                chapterId = it.chapterId,
                mangaId = it.mangaId,
                mangaTitle = it.mangaTitle,
                error = it.error,
            )
        }
    val totalChaptersScanned: Int = event.totalChaptersScanned
    val totalChaptersFixed: Int = event.totalChaptersFixed
    val chaptersAlreadyCorrect: Int = event.chaptersAlreadyCorrect
    val totalErrors: Int = event.totalErrors
}

enum class ScanEventTypeEnum {
    STARTED,
    SCANNING_MANGA,
    CHAPTER_FIXED,
    CHAPTER_ERROR,
    COMPLETED,
}
