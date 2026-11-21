package suwayomi.tachidesk.manga.impl.search

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.manga.model.table.MangaLlmIndexTable

/**
 * The structured title of one manga, as written by
 * [suwayomi.tachidesk.llm.LlmTitleStructurer].
 *
 * [MangaSearchText.index] takes this and puts the series/characters/name fields into the index, which is
 * what makes `ブルアカ` find the books whose title only says `(ブルーアーカイブ)`. Only [contentFlags] go
 * into the index: `汉化`/`中国翻訳`/`DL版` are release markers, and indexing them would undo the title
 * cleaning (`汉化` alone matches a third of the library).
 */
object StructuredTitle {
    private val json = Json { ignoreUnknownKeys = true }
    private val stringList = ListSerializer(String.serializer())

    /** Attributes of the book itself, as opposed to who translated/released it. */
    private val CONTENT_FLAGS =
        setOf("無修正", "無碼", "有碼", "全彩", "上色", "同人誌", "AI生成")

    data class Fields(
        val cleanTitle: String?,
        val series: String?,
        val characters: List<String>,
        val circle: String?,
        val artist: String?,
        val aliases: List<String>,
        val flags: List<String>,
    ) {
        /** Flags worth searching for; release markers stay in the table only. */
        val contentFlags: List<String> get() = flags.filter { it in CONTENT_FLAGS }
    }

    /** Structure of one manga, or null when the model has not processed it yet. */
    fun load(mangaId: Int): Fields? = loadAll(listOf(mangaId))[mangaId]

    /** Batch variant for the library update path, which touches thousands of books at once. */
    fun loadAll(mangaIds: Collection<Int>): Map<Int, Fields> {
        if (mangaIds.isEmpty()) return emptyMap()
        return transaction {
            MangaLlmIndexTable
                .selectAll()
                .where { MangaLlmIndexTable.manga inList mangaIds }
                .mapNotNull(::of)
                .associateBy({ it.first }, { it.second })
        }
    }

    /** Reads the structure columns out of a joined row; null when the left join found no structure. */
    internal fun of(row: ResultRow): Pair<Int, Fields>? {
        val mangaId = row.getOrNull(MangaLlmIndexTable.manga)?.value ?: return null
        return mangaId to
            Fields(
                cleanTitle = row[MangaLlmIndexTable.cleanTitle],
                series = row[MangaLlmIndexTable.series],
                characters = decodeList(row[MangaLlmIndexTable.characters]),
                circle = row[MangaLlmIndexTable.circle],
                artist = row[MangaLlmIndexTable.artist],
                aliases = decodeList(row[MangaLlmIndexTable.aliases]),
                flags = row[MangaLlmIndexTable.flags]?.split(' ')?.filter { it.isNotBlank() }.orEmpty(),
            )
    }

    private fun decodeList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(stringList, raw) }.getOrDefault(emptyList())
    }
}
