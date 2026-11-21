package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.manga.model.table.MangaMetaTable

/**
 * Keys of the per-manga metadata the server itself reads back.
 *
 * Sources derive the author from the title, so every refresh rewrites it. Once the value was corrected
 * (manually or by [suwayomi.tachidesk.graphql.mutations.LlmMutation.llmFixAuthors]) it has to survive the
 * refreshes: `authorLocked=true` makes [Manga.updateMangaDatabase] keep the stored author and artist.
 */
object MangaMetaKeys {
    const val AUTHOR_LOCKED = "authorLocked"
}

/** Read/write side of [MangaMetaKeys.AUTHOR_LOCKED]. */
object MangaAuthorLock {
    /** True when the stored author must not be replaced by a source refresh. */
    fun isLocked(mangaId: Int): Boolean =
        transaction {
            MangaMetaTable
                .selectAll()
                .where {
                    (MangaMetaTable.ref eq mangaId) and
                        (MangaMetaTable.key eq MangaMetaKeys.AUTHOR_LOCKED) and
                        (MangaMetaTable.value eq "true")
                }.any()
        }

    fun lock(
        mangaId: Int,
        userId: Int,
    ) {
        val exists =
            MangaMetaTable
                .selectAll()
                .where {
                    (MangaMetaTable.ref eq mangaId) and
                        (MangaMetaTable.key eq MangaMetaKeys.AUTHOR_LOCKED) and
                        (MangaMetaTable.user eq userId)
                }.any()

        if (exists) {
            MangaMetaTable.update({
                (MangaMetaTable.ref eq mangaId) and
                    (MangaMetaTable.key eq MangaMetaKeys.AUTHOR_LOCKED) and
                    (MangaMetaTable.user eq userId)
            }) {
                it[value] = "true"
            }
        } else {
            MangaMetaTable.insert {
                it[ref] = mangaId
                it[key] = MangaMetaKeys.AUTHOR_LOCKED
                it[value] = "true"
                it[user] = userId
            }
        }
    }
}
