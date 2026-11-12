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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.MangaUserTable
import kotlin.coroutines.cancellation.CancellationException

object RefreshManager {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // region: Data Classes

    data class RefreshProgressEvent(
        val type: RefreshEventType,
        val totalManga: Int = 0,
        val refreshedManga: Int = 0,
        val currentMangaTitle: String? = null,
        val mangaResult: MangaRefreshResult? = null,
        val mangaError: MangaRefreshError? = null,
        val totalRefreshed: Int = 0,
        val totalFailed: Int = 0,
    )

    enum class RefreshEventType {
        STARTED,
        REFRESHING_MANGA,
        MANGA_REFRESHED,
        MANGA_FAILED,
        STOPPED,
        COMPLETED,
    }

    data class MangaRefreshResult(
        val mangaId: Int,
        val mangaTitle: String,
    )

    data class MangaRefreshError(
        val mangaId: Int?,
        val mangaTitle: String?,
        val error: String,
    )

    /**
     * Full accumulated refresh status returned by [getStatus].
     * Includes all refreshed manga and errors collected so far (or from the last completed refresh).
     */
    data class RefreshStatus(
        val isRunning: Boolean,
        val type: RefreshEventType,
        val sourceId: Long?,
        val dateFrom: Long?,
        val dateTo: Long?,
        val totalManga: Int,
        val refreshedManga: Int,
        val totalRefreshed: Int,
        val totalFailed: Int,
        val refreshedMangaList: List<MangaRefreshResult>,
        val failedMangaList: List<MangaRefreshError>,
    )

    // endregion

    // region: Flow

    private val progressFlow = MutableSharedFlow<RefreshProgressEvent>(
        replay = 0,
        extraBufferCapacity = 500_000,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    /** Subscribe to refresh progress. */
    val progress: MutableSharedFlow<RefreshProgressEvent> = progressFlow

    private var lastEvent: RefreshProgressEvent =
        RefreshProgressEvent(
            type = RefreshEventType.COMPLETED,
            totalManga = 0,
            refreshedManga = 0,
            totalRefreshed = 0,
            totalFailed = 0,
        )

    // endregion

    // region: Accumulated State

    @Volatile
    var isRunning: Boolean = false
        private set

    private var lastSourceId: Long? = null
    private var lastDateFrom: Long? = null
    private var lastDateTo: Long? = null
    private val refreshedMangaList = mutableListOf<MangaRefreshResult>()
    private val failedMangaList = mutableListOf<MangaRefreshError>()

    /** The Job of the currently running refresh, or null if idle. Used for cancellation. */
    private var refreshJob: Job? = null

    /** Returns the full accumulated refresh status. Safe to call at any time (idle, running, or after completion). */
    fun getStatus(): RefreshStatus =
        synchronized(this) {
            RefreshStatus(
                isRunning = isRunning,
                type = lastEvent.type,
                sourceId = lastSourceId,
                dateFrom = lastDateFrom,
                dateTo = lastDateTo,
                totalManga = lastEvent.totalManga,
                refreshedManga = lastEvent.refreshedManga,
                totalRefreshed = lastEvent.totalRefreshed,
                totalFailed = lastEvent.totalFailed,
                refreshedMangaList = refreshedMangaList.toList(),
                failedMangaList = failedMangaList.toList(),
            )
        }

    // endregion

    // region: Cancellation

    /**
     * Cancels the currently running refresh (if any).
     * Preserves accumulated results and emits a STOPPED event.
     */
    suspend fun stopRefresh() {
        val job = synchronized(this) { refreshJob }
        if (job != null) {
            logger.info { "Stopping refresh..." }
            job.cancelAndJoin()
            logger.info { "Refresh stopped by user" }
        }
    }

    // endregion

    /**
     * Batch refresh manga info (metadata + chapters) from their source plugins.
     *
     * @param sourceId If non-null, only refresh manga from this source. If null, refresh all library manga.
     * @param dateFrom If non-null, only refresh manga added to library on or after this Unix timestamp (milliseconds).
     * @param dateTo If non-null, only refresh manga added to library on or before this Unix timestamp (milliseconds).
     */
    suspend fun startRefresh(
        sourceId: Long?,
        dateFrom: Long? = null,
        dateTo: Long? = null,
    ) {
        // Cancel any already-running refresh
        synchronized(this) {
            refreshJob?.cancel()
            refreshJob = null
        }

        // Launch the actual work as a tracked job so it can be cancelled
        refreshJob = scope.launch {
            try {
                doRefresh(sourceId, dateFrom, dateTo)
            } catch (e: CancellationException) {
                // User-initiated stop — preserve accumulated results
                synchronized(this@RefreshManager) {
                    isRunning = false
                    lastEvent =
                        lastEvent.copy(
                            type = RefreshEventType.STOPPED,
                        )
                }
                progressFlow.tryEmit(lastEvent)
                logger.info { "Refresh cancelled" }
                throw e // re-throw so the coroutine infrastructure knows it was cancelled
            }
        }
    }

    private suspend fun doRefresh(
        sourceId: Long?,
        dateFrom: Long?,
        dateTo: Long?,
    ) {
        // Clear previous state
        synchronized(this) {
            isRunning = true
            lastSourceId = sourceId
            lastDateFrom = dateFrom
            lastDateTo = dateTo
            refreshedMangaList.clear()
            failedMangaList.clear()
        }

        // Step 1: Get all manga to refresh
        val mangaList =
            transaction {
                MangaTable
                    .selectAll()
                    .where {
                        var condition: Op<Boolean> =
                            MangaTable.id.inSubQuery(
                                MangaUserTable
                                    .select(MangaUserTable.manga)
                                    .where {
                                        var userCondition: Op<Boolean> = MangaUserTable.inLibrary eq true
                                        if (dateFrom != null) {
                                            userCondition = userCondition and (MangaUserTable.inLibraryAt greaterEq dateFrom)
                                        }
                                        if (dateTo != null) {
                                            userCondition = userCondition and (MangaUserTable.inLibraryAt lessEq dateTo)
                                        }
                                        userCondition
                                    },
                            )
                        if (sourceId != null) {
                            condition = condition and (MangaTable.sourceReference eq sourceId)
                        }
                        condition
                    }
                    .map { row ->
                        Pair(row[MangaTable.id].value, row[MangaTable.title])
                    }
            }

        if (mangaList.isEmpty()) {
            logger.info { "No manga to refresh (sourceId=$sourceId, dateFrom=$dateFrom, dateTo=$dateTo)" }
            synchronized(this) {
                isRunning = false
                lastEvent =
                    RefreshProgressEvent(
                        type = RefreshEventType.COMPLETED,
                        totalManga = 0,
                        refreshedManga = 0,
                        totalRefreshed = 0,
                        totalFailed = 0,
                    )
            }
            progressFlow.tryEmit(lastEvent)
            return
        }

        // Emit STARTED
        lastEvent =
            RefreshProgressEvent(
                type = RefreshEventType.STARTED,
                totalManga = mangaList.size,
                refreshedManga = 0,
                totalRefreshed = 0,
                totalFailed = 0,
            )
        progressFlow.tryEmit(lastEvent)
        logger.info { "Starting manga info refresh: ${mangaList.size} manga" + if (sourceId != null) " from source $sourceId" else "" + if (dateFrom != null || dateTo != null) " with date filter" else "" }

        var totalRefreshed = 0
        var totalFailed = 0

        // Step 2: Refresh each manga
        for ((index, mangaData) in mangaList.withIndex()) {
            // Cooperative cancellation check
            currentCoroutineContext().ensureActive()

            val (mangaId, mangaTitle) = mangaData

            // Emit REFRESHING_MANGA
            lastEvent =
                lastEvent.copy(
                    type = RefreshEventType.REFRESHING_MANGA,
                    refreshedManga = index + 1,
                    currentMangaTitle = mangaTitle,
                )
            progressFlow.tryEmit(lastEvent)

            val maxAttempts = 3 // 1 initial attempt + 2 retries
            var success = false
            var lastError: Exception? = null

            for (attempt in 1..maxAttempts) {
                // Cooperative cancellation check before each attempt
                currentCoroutineContext().ensureActive()

                try {
                    Manga.updateMangaAndChapters(
                        mangaId = mangaId,
                        updateManga = true,
                        updateChapters = true,
                    )
                    success = true
                    break
                } catch (e: Exception) {
                    // Don't propagate CancellationException — it's caught at the outer level
                    if (e is CancellationException) throw e
                    lastError = e
                    if (attempt < maxAttempts) {
                        logger.warn(e) {
                            "Attempt $attempt/$maxAttempts failed for $mangaTitle (id=$mangaId), retrying..."
                        }
                    }
                }
            }

            if (success) {
                // Success
                totalRefreshed++
                val result =
                    MangaRefreshResult(
                        mangaId = mangaId,
                        mangaTitle = mangaTitle,
                    )
                synchronized(this) { refreshedMangaList.add(result) }
                lastEvent =
                    lastEvent.copy(
                        type = RefreshEventType.MANGA_REFRESHED,
                        mangaResult = result,
                        totalRefreshed = totalRefreshed,
                    )
                progressFlow.tryEmit(lastEvent)
                logger.debug { "Refreshed manga: $mangaTitle (id=$mangaId)" }
            } else {
                // All attempts failed — add to failed list
                totalFailed++
                val error =
                    MangaRefreshError(
                        mangaId = mangaId,
                        mangaTitle = mangaTitle,
                        error = lastError?.message ?: "Unknown error",
                    )
                synchronized(this) { failedMangaList.add(error) }
                lastEvent =
                    lastEvent.copy(
                        type = RefreshEventType.MANGA_FAILED,
                        mangaError = error,
                        totalFailed = totalFailed,
                    )
                progressFlow.tryEmit(lastEvent)
                logger.warn(lastError) { "Failed to refresh manga after $maxAttempts attempts: $mangaTitle (id=$mangaId)" }
            }
        }

        // Emit COMPLETED
        synchronized(this) {
            isRunning = false
            lastEvent =
                RefreshProgressEvent(
                    type = RefreshEventType.COMPLETED,
                    totalManga = mangaList.size,
                    refreshedManga = mangaList.size,
                    totalRefreshed = totalRefreshed,
                    totalFailed = totalFailed,
                )
        }
        progressFlow.tryEmit(lastEvent)

        logger.info {
            "Manga info refresh complete: ${mangaList.size} manga, " +
                "$totalRefreshed succeeded, $totalFailed failed"
        }
    }
}
