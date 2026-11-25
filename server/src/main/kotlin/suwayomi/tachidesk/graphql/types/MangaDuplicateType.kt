package suwayomi.tachidesk.graphql.types

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import suwayomi.tachidesk.graphql.server.primitives.PageInfo

class MangaDuplicateEntryType(
    @GraphQLDescription("The id of the library entry.")
    val mangaId: Int,
    @GraphQLDescription("The title the entry is stored with, release metadata included.")
    val title: String,
    @GraphQLDescription("The work name extracted from the title.")
    val cleanTitle: String,
    val thumbnailUrl: String,
    val author: String?,
    val artist: String?,
    val sourceId: Long,
    val sourceName: String?,
    val chapterCount: Int,
    val downloadedChapterCount: Int,
    @GraphQLDescription("0-100, higher means a better copy of the work.")
    val score: Int,
    @GraphQLDescription("Why the entry got this score, shown in the UI.")
    val reasons: List<String>,
    @GraphQLDescription("Whether this is the entry the server would keep.")
    val recommended: Boolean,
)

class MangaDuplicateGroupType(
    @GraphQLDescription("Normalized work name the entries were grouped by.")
    val key: String,
    @GraphQLDescription("Work name shared by the entries, shown as the group title.")
    val title: String,
    val entries: List<MangaDuplicateEntryType>,
    val recommendedKeepId: Int,
    @GraphQLDescription("The entries name different authors, so they are probably not the same work.")
    val conflictingAuthors: Boolean,
)

class MangaDuplicateGroupList(
    val nodes: List<MangaDuplicateGroupType>,
    val pageInfo: PageInfo,
    @GraphQLDescription("Number of duplicate groups.")
    val totalCount: Int,
    @GraphQLDescription("Number of library entries that are part of a duplicate group.")
    val duplicateMangaCount: Int,
)
