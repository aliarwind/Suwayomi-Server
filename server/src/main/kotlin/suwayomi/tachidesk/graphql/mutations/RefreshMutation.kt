@file:Suppress("RedundantNullableReturnType", "unused")

package suwayomi.tachidesk.graphql.mutations

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.manga.impl.Manga
import suwayomi.tachidesk.manga.impl.RefreshManager
import suwayomi.tachidesk.manga.impl.util.source.GetSource
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.server.JavalinSetup.future
import java.util.concurrent.CompletableFuture

class RefreshMutation {
    data class StartRefreshInput(
        val clientMutationId: String? = null,
        val sourceId: Long? = null,
        val dateFrom: Long? = null,
        val dateTo: Long? = null,
    )

    data class StartRefreshPayload(
        val clientMutationId: String?,
        val started: Boolean,
    )

    data class StopRefreshInput(
        val clientMutationId: String? = null,
    )

    data class StopRefreshPayload(
        val clientMutationId: String?,
        val stopped: Boolean = true,
    )

    data class RetryRefreshMangaInput(
        val clientMutationId: String? = null,
        val mangaId: Int,
    )

    data class RetryRefreshMangaPayload(
        val clientMutationId: String?,
        val success: Boolean,
        val mangaId: Int,
        val mangaTitle: String?,
        val error: String?,
    )

    @RequireAuth
    fun startRefresh(input: StartRefreshInput): CompletableFuture<StartRefreshPayload> =
        future {
            GlobalScope.launch { RefreshManager.startRefresh(input.sourceId, input.dateFrom, input.dateTo) }
            StartRefreshPayload(
                clientMutationId = input.clientMutationId,
                started = true,
            )
        }

    @RequireAuth
    fun stopRefresh(input: StopRefreshInput): CompletableFuture<StopRefreshPayload> =
        future {
            RefreshManager.stopRefresh()
            StopRefreshPayload(
                clientMutationId = input.clientMutationId,
                stopped = true,
            )
        }

    @RequireAuth
    fun retryRefreshManga(input: RetryRefreshMangaInput): CompletableFuture<RetryRefreshMangaPayload> =
        future {
            try {
                // Clear cached source so the latest extension JAR is reloaded.
                // This prevents stale-source errors like "URL updated, restart required"
                // when an extension has been updated since the source was first loaded.
                val sourceId =
                    transaction { MangaTable.selectAll().where { MangaTable.id eq input.mangaId }.firstOrNull() }
                        ?.get(MangaTable.sourceReference)
                if (sourceId != null) {
                    GetSource.unregisterSource(sourceId)
                }

                Manga.updateMangaAndChapters(
                    mangaId = input.mangaId,
                    updateManga = true,
                    updateChapters = true,
                )
                RetryRefreshMangaPayload(
                    clientMutationId = input.clientMutationId,
                    success = true,
                    mangaId = input.mangaId,
                    mangaTitle = null, // title will be updated on next query from DB
                    error = null,
                )
            } catch (e: Exception) {
                RetryRefreshMangaPayload(
                    clientMutationId = input.clientMutationId,
                    success = false,
                    mangaId = input.mangaId,
                    mangaTitle = null,
                    error = e.message ?: "Unknown error",
                )
            }
        }
}
