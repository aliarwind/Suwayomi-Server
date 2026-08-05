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
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.manga.impl.ScanManager
import suwayomi.tachidesk.server.JavalinSetup.future
import java.util.concurrent.CompletableFuture

class ScanMutation {
    data class StartScanInput(
        val clientMutationId: String? = null,
    )

    data class StartScanPayload(
        val clientMutationId: String?,
        val started: Boolean,
    )

    @RequireAuth
    fun startScan(input: StartScanInput): CompletableFuture<StartScanPayload> =
        future {
            GlobalScope.launch { ScanManager.startScan() }
            StartScanPayload(
                clientMutationId = input.clientMutationId,
                started = true,
            )
        }
}
