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
import suwayomi.tachidesk.test.GraphQLTest
import suwayomi.tachidesk.test.clearTables
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `作者是ie` has to find the artist called `ie`, not every book with "pie" or "friend" in its title.
 *
 * The live library showed the size of the problem: the query matched 961 books while only 139 of them have
 * `ie` as an author/name token (`piece`, `frieren`, `cuvie`, `friend` made up the rest).
 */
class MangaSearchNameIntentTest : GraphQLTest() {
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

    private fun search(search: String): GraphQLResponse<*> =
        graphql(
            """
            query {
                mangas(search: "$search") {
                    totalCount
                    nodes {
                        id
                        title
                    }
                }
            }
            """.trimIndent(),
        )

    private fun titles(response: GraphQLResponse<*>): List<Any?> {
        val nodes = response.dataPath("mangas", "nodes") as? List<*> ?: return emptyList()
        return nodes.map { node -> (node as Map<*, *>)["title"] }
    }

    private fun indexLibrary() {
        libraryManga("pie の本") // title token "pie": only a substring match for "ie"
        libraryManga("memo", author = "ie") // the artist the query asks about
    }

    @Test
    fun `an author intent only matches names`() {
        indexLibrary()

        val response = search("作者是ie")

        response.assertNoErrors()
        assertEquals(1, response.dataPath("mangas", "totalCount"), "the title with 'pie' must not match")
        assertEquals(listOf("memo"), titles(response))
    }

    @Test
    fun `without the intent the word still matches inside titles`() {
        indexLibrary()

        val response = search("ie")

        response.assertNoErrors()
        assertEquals(2, response.dataPath("mangas", "totalCount"))
    }

    @Test
    fun `a circle intent is restricted the same way`() {
        libraryManga("suite の本") // title token "suite" only
        libraryManga("memo", author = "suite")

        val response = search("社团是suite")

        response.assertNoErrors()
        assertEquals(listOf("memo"), titles(response))
    }

    @AfterEach
    fun tearDown() {
        clearTables(
            MangaUserTable,
            MangaTable,
        )
    }
}
