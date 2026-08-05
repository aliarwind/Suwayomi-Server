package suwayomi.tachidesk.graphql.types

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import suwayomi.tachidesk.manga.impl.ScanManager

class ScanStatusType(
    status: ScanManager.ScanStatus,
) {
    val isRunning: Boolean = status.isRunning
    val type: ScanEventTypeEnum =
        when (status.type) {
            ScanManager.ScanEventType.STARTED -> ScanEventTypeEnum.STARTED
            ScanManager.ScanEventType.SCANNING_MANGA -> ScanEventTypeEnum.SCANNING_MANGA
            ScanManager.ScanEventType.CHAPTER_FIXED -> ScanEventTypeEnum.CHAPTER_FIXED
            ScanManager.ScanEventType.CHAPTER_ERROR -> ScanEventTypeEnum.CHAPTER_ERROR
            ScanManager.ScanEventType.COMPLETED -> ScanEventTypeEnum.COMPLETED
        }
    val totalManga: Int = status.totalManga
    val scannedManga: Int = status.scannedManga
    val mangaTitle: String? = status.mangaTitle
    val totalChaptersScanned: Int = status.totalChaptersScanned
    val totalChaptersFixed: Int = status.totalChaptersFixed
    val chaptersAlreadyCorrect: Int = status.chaptersAlreadyCorrect
    val totalErrors: Int = status.totalErrors
    val fixedChapters: List<FixedChapterType> =
        status.fixedChapters.map {
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
    val errors: List<ScanErrorType> =
        status.errors.map {
            ScanErrorType(
                chapterId = it.chapterId,
                mangaId = it.mangaId,
                mangaTitle = it.mangaTitle,
                error = it.error,
            )
        }
}
