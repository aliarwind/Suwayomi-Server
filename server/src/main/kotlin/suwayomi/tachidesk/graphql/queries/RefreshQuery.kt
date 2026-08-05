package suwayomi.tachidesk.graphql.queries

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.types.RefreshStatusType
import suwayomi.tachidesk.manga.impl.RefreshManager
import suwayomi.tachidesk.server.JavalinSetup.future
import java.util.concurrent.CompletableFuture

class RefreshQuery {
    @RequireAuth
    fun refreshStatus(): CompletableFuture<RefreshStatusType> =
        future { RefreshStatusType(RefreshManager.getStatus()) }
}
