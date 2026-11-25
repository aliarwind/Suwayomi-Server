package suwayomi.tachidesk.graphql.queries

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.server.primitives.Cursor
import suwayomi.tachidesk.graphql.server.primitives.PageInfo
import suwayomi.tachidesk.graphql.types.MangaDuplicateEntryType
import suwayomi.tachidesk.graphql.types.MangaDuplicateGroupList
import suwayomi.tachidesk.graphql.types.MangaDuplicateGroupType
import suwayomi.tachidesk.manga.impl.duplicates.MangaDuplicates
import suwayomi.tachidesk.server.JavalinSetup.future
import java.util.concurrent.CompletableFuture

class MangaDuplicateQuery {
    /**
     * Groups the library entries that share the same work name, so duplicates can be reviewed and removed
     * without pulling the whole library into the client.
     */
    @RequireAuth
    fun mangaDuplicates(
        @GraphQLIgnore
        userId: Int,
        @GraphQLDescription("Groups with fewer entries are not reported (at least 2).")
        minGroupSize: Int? = null,
        @GraphQLDescription("Groups with more entries are not reported, e.g. 3 shows only groups of 2 or 3.")
        maxGroupSize: Int? = null,
        @GraphQLDescription("Number of groups to return, all of them when omitted.")
        first: Int? = null,
        offset: Int? = null,
        @GraphQLDescription("Recompute instead of using the cached result.")
        refresh: Boolean? = null,
    ): CompletableFuture<MangaDuplicateGroupList> =
        future {
            val minSize = (minGroupSize ?: MangaDuplicates.DEFAULT_MIN_GROUP_SIZE).coerceAtLeast(2)
            // the result is cached per user for a few seconds, the size filters are applied here so the cache
            // can serve every caller
            val allGroups =
                MangaDuplicates
                    .groups(userId, refresh == true)
                    .filter { it.entries.size >= minSize && (maxGroupSize == null || it.entries.size <= maxGroupSize) }

            val skip = (offset ?: 0).coerceAtLeast(0)
            val page = allGroups.drop(skip).let { if (first == null) it else it.take(first) }

            MangaDuplicateGroupList(
                nodes =
                    page.map { group ->
                        MangaDuplicateGroupType(
                            key = group.key,
                            title = group.title,
                            entries =
                                group.entries.map { entry ->
                                    MangaDuplicateEntryType(
                                        mangaId = entry.mangaId,
                                        title = entry.title,
                                        cleanTitle = entry.cleanTitle,
                                        thumbnailUrl = entry.thumbnailUrl,
                                        author = entry.author,
                                        artist = entry.artist,
                                        sourceId = entry.sourceId,
                                        sourceName = entry.sourceName,
                                        chapterCount = entry.chapterCount,
                                        downloadedChapterCount = entry.downloadedChapterCount,
                                        score = entry.score,
                                        reasons = entry.reasons,
                                        recommended = entry.mangaId == group.recommendedKeepId,
                                    )
                                },
                            recommendedKeepId = group.recommendedKeepId,
                            conflictingAuthors = group.conflictingAuthors,
                        )
                    },
                pageInfo =
                    PageInfo(
                        hasNextPage = skip + page.size < allGroups.size,
                        hasPreviousPage = skip > 0,
                        startCursor = Cursor(skip.toString()),
                        endCursor = Cursor((skip + page.size).toString()),
                    ),
                totalCount = allGroups.size,
                duplicateMangaCount = allGroups.sumOf { it.entries.size },
            )
        }
}
