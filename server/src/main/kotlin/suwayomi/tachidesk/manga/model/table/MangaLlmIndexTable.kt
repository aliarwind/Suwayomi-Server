package suwayomi.tachidesk.manga.model.table

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * Structured view of a manga title, produced by the model instead of the regex cleaner.
 *
 * The regex cleaner ([suwayomi.tachidesk.manga.impl.search.MangaTitleCleaner]) can split
 * `[circle (artist)] work [translation] [DL版]` but it only knows the bracket conventions. The model also
 * recovers the series and the characters of a book, which is what the caller wants when they search
 * `ブルアカ` and the title only says `(ブルーアーカイブ)`.
 *
 * One row per manga, keyed by [manga]; deleting the manga removes the row. Nothing in the search path
 * reads this table yet: v1 writes it, then builds `Manga.search_text` from it
 * ([suwayomi.tachidesk.manga.impl.search.MangaSearchText]).
 *
 * [characters] and [aliases] are JSON arrays, [flags] is the space separated list of release attributes
 * (`汉化 無修正 DL版` …) that v0 already keeps in the index. [version] is the prompt/rules version the row
 * was produced with; rows below the current version are re-extracted, which doubles as the backfill cursor.
 * [inputHash] is a hash of the title/tags/author the row was built from, so a refresh that only changed
 * the chapters does not cost another model call.
 */
object MangaLlmIndexTable : Table() {
    val manga = reference("manga_ref", MangaTable, ReferenceOption.CASCADE)

    /** The work name without circle/artist/scanlation/release metadata. */
    val cleanTitle = varchar("clean_title", 2048).nullable()

    /** Franchise the book belongs to, in the wording of the title (may be empty). */
    val series = varchar("series", 512).nullable()

    /** JSON array of character names. */
    val characters = varchar("characters", 2048).nullable()

    /** Doujin circle, when the title names one. */
    val circle = varchar("circle", 512).nullable()

    /** Artist/author person name, when the title names one. */
    val artist = varchar("artist", 512).nullable()

    /** Language of the title text itself (`zh`, `ja`, `en`, `other`, `unknown`). */
    val lang = varchar("lang", 16).nullable()

    /** JSON array of the same series/characters written in another language. */
    val aliases = varchar("aliases", 2048).nullable()

    /** Space separated release attributes (`汉化`, `無修正`, `DL版`, …). */
    val flags = varchar("flags", 256).nullable()

    /** Hash of the input row, so an unchanged title is not sent to the model again. */
    val inputHash = varchar("input_hash", 64).nullable()

    /** Model that produced the row, for debugging a bad batch. */
    val model = varchar("model", 64).nullable()

    /** Prompt/rules version the row was produced with; a missing row means "not extracted yet", a lower version is stale. */
    val version = integer("version").default(0)

    val updatedAt = long("updated_at").default(0)

    override val primaryKey = PrimaryKey(manga)
}
