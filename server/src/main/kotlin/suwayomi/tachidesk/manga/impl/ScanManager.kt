package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.manga.impl.chapter.updateChapterPersistence
import suwayomi.tachidesk.manga.impl.util.getMangaDownloadDir
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import xyz.nulldev.androidcompat.util.SafePath
import java.io.File

object ScanManager {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // region: Data Classes

    data class ScanProgressEvent(
        val type: ScanEventType,
        val totalManga: Int = 0,
        val scannedManga: Int = 0,
        val mangaTitle: String? = null,
        val chapterFixed: FixedChapterInfo? = null,
        val chapterError: ScanErrorInfo? = null,
        val totalChaptersScanned: Int = 0,
        val totalChaptersFixed: Int = 0,
        val chaptersAlreadyCorrect: Int = 0,
        val totalErrors: Int = 0,
    )

    enum class ScanEventType {
        STARTED,
        SCANNING_MANGA,
        CHAPTER_FIXED,
        CHAPTER_ERROR,
        COMPLETED,
    }

    data class FixedChapterInfo(
        val chapterId: Int,
        val mangaId: Int,
        val mangaTitle: String,
        val chapterName: String,
        val pageCountFound: Int,
        val wasMarkedDownloaded: Boolean,
        val wasPageCountWrong: Boolean,
    )

    data class ScanErrorInfo(
        val chapterId: Int?,
        val mangaId: Int?,
        val mangaTitle: String?,
        val error: String,
    )

    /**
     * Full accumulated scan status returned by [getStatus].
     * Includes all fixed chapters and errors collected so far (or from the last completed scan).
     */
    data class ScanStatus(
        val isRunning: Boolean,
        val type: ScanEventType,
        val totalManga: Int,
        val scannedManga: Int,
        val mangaTitle: String?,
        val totalChaptersScanned: Int,
        val totalChaptersFixed: Int,
        val chaptersAlreadyCorrect: Int,
        val totalErrors: Int,
        val fixedChapters: List<FixedChapterInfo>,
        val errors: List<ScanErrorInfo>,
    )

    // endregion

    // region: Flow

    private val progressFlow = MutableSharedFlow<ScanProgressEvent>()

    /** Subscribe to scan progress. Emits current state on subscription. */
    val progress: MutableSharedFlow<ScanProgressEvent> = progressFlow

    private var lastEvent: ScanProgressEvent =
        ScanProgressEvent(
            type = ScanEventType.COMPLETED,
            totalManga = 0,
            scannedManga = 0,
            totalChaptersScanned = 0,
            totalChaptersFixed = 0,
            totalErrors = 0,
        )

    // endregion

    // region: Accumulated State

    @Volatile
    var isRunning: Boolean = false
        private set

    private val fixedChapters = mutableListOf<FixedChapterInfo>()
    private val errors = mutableListOf<ScanErrorInfo>()

    /** Returns the full accumulated scan status. Safe to call at any time (idle, running, or after completion). */
    fun getStatus(): ScanStatus =
        synchronized(this) {
            ScanStatus(
                isRunning = isRunning,
                type = lastEvent.type,
                totalManga = lastEvent.totalManga,
                scannedManga = lastEvent.scannedManga,
                mangaTitle = lastEvent.mangaTitle,
                totalChaptersScanned = lastEvent.totalChaptersScanned,
                totalChaptersFixed = lastEvent.totalChaptersFixed,
                chaptersAlreadyCorrect = lastEvent.chaptersAlreadyCorrect,
                totalErrors = lastEvent.totalErrors,
                fixedChapters = fixedChapters.toList(),
                errors = errors.toList(),
            )
        }

    /** Returns the last scan event (for initial state when subscribing). */
    fun getLastEvent(): ScanProgressEvent = lastEvent

    // endregion

    /**
     * Scans all manga in the library and fixes the [ChapterTable.isDownloaded] flag
     * for chapters that have download files on disk but are not marked as downloaded in the database.
     *
     * Emits progress events via [progress] Flow. Idempotent — running multiple times is safe.
     */
    suspend fun startScan() {
        // Clear previous state
        synchronized(this) {
            isRunning = true
            fixedChapters.clear()
            errors.clear()
        }

        var chaptersAlreadyCorrect = 0

        // Step 1: Get all manga in the library
        val libraryManga =
            transaction {
                MangaTable
                    .selectAll()
                    .where { MangaTable.inLibrary eq true }
                    .map { row ->
                        Pair(row[MangaTable.id].value, row[MangaTable.title])
                    }
            }

        // Emit STARTED
        lastEvent =
            ScanProgressEvent(
                type = ScanEventType.STARTED,
                totalManga = libraryManga.size,
                scannedManga = 0,
                totalChaptersScanned = 0,
                totalChaptersFixed = 0,
                chaptersAlreadyCorrect = 0,
                totalErrors = 0,
            )
        scope.launch { progressFlow.emit(lastEvent) }
        logger.info { "Starting download mark scan: ${libraryManga.size} manga in library" }

        var totalChaptersScanned = 0
        var totalErrors = 0

        // Step 2: For each manga, scan its chapters
        for ((index, mangaData) in libraryManga.withIndex()) {
            val (mangaId, mangaTitle) = mangaData

            // Emit SCANNING_MANGA
            lastEvent =
                lastEvent.copy(
                    type = ScanEventType.SCANNING_MANGA,
                    scannedManga = index + 1,
                    mangaTitle = mangaTitle,
                )
            scope.launch { progressFlow.emit(lastEvent) }
            logger.info { "Scanning manga: $mangaTitle (id=$mangaId)" }

            // Get manga download directory once for this manga
            val mangaDownloadDir =
                runCatching {
                    getMangaDownloadDir(mangaId)
                }.getOrNull()

            val chapters =
                transaction {
                    ChapterTable
                        .selectAll()
                        .where { ChapterTable.manga eq mangaId }
                        .orderBy(ChapterTable.sourceOrder to SortOrder.ASC)
                        .map { row ->
                            ChapterEntry(
                                id = row[ChapterTable.id].value,
                                name = row[ChapterTable.name],
                                scanlator = row[ChapterTable.scanlator],
                                isDownloaded = row[ChapterTable.isDownloaded],
                                pageCount = row[ChapterTable.pageCount],
                                lastPageRead = row[ChapterTable.lastPageRead],
                            )
                        }
                }

            for (chapter in chapters) {
                totalChaptersScanned++

                // Skip chapters that are already correctly marked as downloaded
                if (chapter.isDownloaded && chapter.pageCount > 0) {
                    chaptersAlreadyCorrect++
                    continue
                }

                // Quick filesystem check before expensive DB lookups
                if (mangaDownloadDir == null) {
                    continue
                }

                val chapterDirName =
                    SafePath.buildValidFilename(
                        if (chapter.scanlator != null) {
                            "${chapter.scanlator}_${chapter.name}"
                        } else {
                            chapter.name
                        },
                    )
                val chapterDir = File("$mangaDownloadDir/$chapterDirName")
                val chapterCbz = File("$mangaDownloadDir/$chapterDirName.cbz")

                if (!chapterDir.exists() && !chapterCbz.exists()) {
                    continue // No download files on disk
                }

                // Files exist — now do the full check
                val downloadPageCount =
                    runCatching {
                        ChapterDownloadHelper.getImageCount(mangaId, chapter.id)
                    }.onFailure { e ->
                        logger.warn(e) {
                            "Failed to check download files for chapter \"${chapter.name}\" " +
                                "(id=${chapter.id}) in manga \"$mangaTitle\" (id=$mangaId)"
                        }
                        val errorInfo =
                            ScanErrorInfo(
                                chapterId = chapter.id,
                                mangaId = mangaId,
                                mangaTitle = mangaTitle,
                                error = e.message ?: "Unknown error",
                            )
                        synchronized(this) { errors.add(errorInfo) }
                        totalErrors++
                        lastEvent =
                            lastEvent.copy(
                                type = ScanEventType.CHAPTER_ERROR,
                                chapterError = errorInfo,
                                totalErrors = totalErrors,
                                totalChaptersScanned = totalChaptersScanned,
                            )
                        scope.launch { progressFlow.emit(lastEvent) }
                    }.getOrDefault(0)

                if (downloadPageCount == 0) {
                    continue
                }

                // Files exist but DB is out of sync — fix it
                val wasMarkedDownloaded = !chapter.isDownloaded
                val wasPageCountWrong = chapter.pageCount != downloadPageCount

                if (wasMarkedDownloaded || wasPageCountWrong) {
                    val fixed =
                        updateChapterPersistence(
                            chapterId = chapter.id,
                            isMarkedAsDownloaded = chapter.isDownloaded,
                            dbPageCount = chapter.pageCount,
                            downloadPageCount = downloadPageCount,
                            lastPageRead = chapter.lastPageRead,
                            logger = logger,
                        )

                    if (fixed) {
                        logger.info {
                            "Fixed chapter \"${chapter.name}\" in \"$mangaTitle\": " +
                                "markedDownloaded=$wasMarkedDownloaded, pageCountWrong=$wasPageCountWrong, " +
                                "actualPages=$downloadPageCount"
                        }
                        val fixedInfo =
                            FixedChapterInfo(
                                chapterId = chapter.id,
                                mangaId = mangaId,
                                mangaTitle = mangaTitle,
                                chapterName = chapter.name,
                                pageCountFound = downloadPageCount,
                                wasMarkedDownloaded = wasMarkedDownloaded,
                                wasPageCountWrong = wasPageCountWrong,
                            )
                        synchronized(this) { fixedChapters.add(fixedInfo) }
                        lastEvent =
                            lastEvent.copy(
                                type = ScanEventType.CHAPTER_FIXED,
                                chapterFixed = fixedInfo,
                                totalChaptersFixed = fixedChapters.size,
                                totalChaptersScanned = totalChaptersScanned,
                            )
                        scope.launch { progressFlow.emit(lastEvent) }
                    }
                }
            }
        }

        // Emit COMPLETED
        synchronized(this) {
            isRunning = false
            lastEvent =
                ScanProgressEvent(
                    type = ScanEventType.COMPLETED,
                    totalManga = libraryManga.size,
                    scannedManga = libraryManga.size,
                    totalChaptersScanned = totalChaptersScanned,
                    totalChaptersFixed = fixedChapters.size,
                    chaptersAlreadyCorrect = chaptersAlreadyCorrect,
                    totalErrors = totalErrors,
                )
        }
        scope.launch { progressFlow.emit(lastEvent) }

        logger.info {
            "Download mark scan complete: ${libraryManga.size} manga, " +
                "$totalChaptersScanned chapters scanned, " +
                "${fixedChapters.size} chapters fixed, " +
                "$chaptersAlreadyCorrect already correct, " +
                "$totalErrors errors"
        }
    }

    // region: Internal

    private data class ChapterEntry(
        val id: Int,
        val name: String,
        val scanlator: String?,
        val isDownloaded: Boolean,
        val pageCount: Int,
        val lastPageRead: Int,
    )

    // endregion
}
