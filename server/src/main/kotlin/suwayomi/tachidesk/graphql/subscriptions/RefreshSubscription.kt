package suwayomi.tachidesk.graphql.subscriptions

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.types.RefreshProgressType
import suwayomi.tachidesk.manga.impl.RefreshManager

class RefreshSubscription {
    @RequireAuth
    fun refreshProgressChanged(): Flow<RefreshProgressType> =
        RefreshManager.progress.map { event ->
            RefreshProgressType(event)
        }
}
