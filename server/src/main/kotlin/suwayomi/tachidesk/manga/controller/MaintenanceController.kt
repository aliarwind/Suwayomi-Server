package suwayomi.tachidesk.manga.controller

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.javalin.http.HttpStatus
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import suwayomi.tachidesk.manga.impl.ScanManager
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.JavalinSetup.getAttribute
import suwayomi.tachidesk.server.user.requireUser
import suwayomi.tachidesk.server.util.handler
import suwayomi.tachidesk.server.util.withOperation

object MaintenanceController {
    val scanDownloadMarks =
        handler(
            documentWith = {
                withOperation {
                    summary("Start a scan to fix download marks")
                    description(
                        "Starts an asynchronous scan of all manga in the library, checking if download files " +
                            "exist on disk and restoring the isDownloaded database flag where needed. " +
                            "Subscribe to scanProgressChanged for real-time updates."
                    )
                }
            },
            behaviorOf = { ctx ->
                ctx.getAttribute(Attribute.TachideskUser).requireUser()
                GlobalScope.launch { ScanManager.startScan() }
                ctx.status(HttpStatus.OK)
            },
            withResults = {
                httpCode(HttpStatus.OK)
            },
        )
}
