package suwayomi.tachidesk.graphql

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.expediagroup.graphql.server.types.GraphQLResponse
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import suwayomi.tachidesk.manga.impl.search.MangaSearchText
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.MangaUserTable
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.test.GraphQLTest
import suwayomi.tachidesk.test.clearTables
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The order of the hits of `mangas(search:)`, end to end through the GraphQL schema.
 *
 * The fixture is the shape of the real problem: a book whose *title* is the word ("ie"), a book that has it
 * as an author name (`N:ie`), and a book whose title merely contains it (`iesu`). All three have to be
 * found — relevance sorting changes the order, never the `totalCount`.
 */
class MangaSearchRankingTest : GraphQLTest() {
    /** Insertion order is the id order, so the "wrong" book comes first and id order cannot hide a bug. */
    private fun indexLibrary() {
        libraryManga("iesu")
        libraryManga("ie")
        libraryManga("memo", author = "ie")
    }

    private fun libraryManga(
        title: String,
        author: String? = null,
    ): Int =
        transaction {
            val mangaId =
                MangaTable
                    .insertAndGetId {
                        it[MangaTable.title] = title
                        it[MangaTable.url] = title
                        it[MangaTable.sourceReference] = 1
                        it[MangaTable.author] = author
                        it[MangaTable.searchText] = MangaSearchText.index(title, author, null, null)
                        it[MangaTable.searchTextVer] = MangaSearchText.VERSION
                    }.value

            MangaUserTable.insert {
                it[MangaUserTable.manga] = mangaId
                it[MangaUserTable.user] = 1
                it[MangaUserTable.inLibrary] = true
            }

            mangaId
        }

    private fun search(
        search: String,
        order: String? = null,
    ): GraphQLResponse<*> {
        val orderArgument = order?.let { ", order: [$it]" }.orEmpty()
        return graphql(
            """
            query {
                mangas(search: "$search"$orderArgument) {
                    totalCount
                    nodes {
                        id
                        title
                    }
                }
            }
            """.trimIndent(),
        )
    }

    private fun nodeTitles(response: GraphQLResponse<*>): List<Any?> {
        val nodes = response.dataPath("mangas", "nodes") as? List<*> ?: return emptyList()
        return nodes.map { node -> (node as Map<*, *>)["title"] }
    }

    @Test
    fun `a title hit is ranked above a name hit and above a mere substring`() {
        indexLibrary()

        val response = search("ie")

        response.assertNoErrors()
        assertEquals(3, response.dataPath("mangas", "totalCount"))
        assertEquals(listOf("ie", "memo", "iesu"), nodeTitles(response))
    }

    @Test
    fun `ranking does not change which books are found`() {
        indexLibrary()

        val ranked = search("ie")
        val byId = search("ie", order = "{by: ID}")

        ranked.assertNoErrors()
        byId.assertNoErrors()
        assertEquals(byId.dataPath("mangas", "totalCount"), ranked.dataPath("mangas", "totalCount"))
        assertEquals(listOf("iesu", "ie", "memo"), nodeTitles(byId))
    }

    @Test
    fun `an explicit sort is left alone`() {
        indexLibrary()

        val response = search("ie", order = "{by: ID, byType: DESC}")

        response.assertNoErrors()
        assertEquals(listOf("memo", "ie", "iesu"), nodeTitles(response))
    }

    @Test
    fun `the relevance sort can be turned off`() {
        indexLibrary()

        val previous = serverConfig.searchRelevanceSortEnabled.value
        try {
            serverConfig.searchRelevanceSortEnabled.value = false
            assertEquals(listOf("iesu", "ie", "memo"), nodeTitles(search("ie")))
        } finally {
            serverConfig.searchRelevanceSortEnabled.value = previous
        }
    }

    @Test
    fun `the relevance sort defaults to on`() {
        assertTrue(serverConfig.searchRelevanceSortEnabled.value)
    }

    @AfterEach
    fun tearDown() {
        clearTables(
            MangaUserTable,
            MangaTable,
        )
    }
}
