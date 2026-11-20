package suwayomi.tachidesk.manga.impl.search

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MangaSearchAliasesTest {
    @Test
    fun `object form is parsed and normalized`() {
        val groups =
            MangaSearchAliases.parseGroups(
                """
                {"version":1,"groups":[{"terms":["上位女","御姐"],"source":"llm"}]}
                """.trimIndent(),
            )

        assertEquals(listOf(listOf("上位女", "御姐")), groups)
    }

    @Test
    fun `array form is accepted`() {
        val groups = MangaSearchAliases.parseGroups("""{"groups":[["大奶","巨乳"]]}""")

        assertEquals(listOf(listOf("大奶", "巨乳")), groups)
    }

    @Test
    fun `entries are normalized like the index`() {
        val groups = MangaSearchAliases.parseGroups("""{"groups":[["マシュ","瑪修"]]}""")

        // katakana is folded to hiragana and traditional to simplified, because that is how tokens are stored
        assertEquals(listOf(listOf("ましゅ", "玛修")), groups)
    }

    @Test
    fun `groups without at least two usable terms are dropped`() {
        val groups =
            MangaSearchAliases.parseGroups(
                """
                {"groups":[["只有一個"],["", "  "],{"note":"no terms"},[1,2,3],["a","b"]]}
                """.trimIndent(),
            )

        assertEquals(listOf(listOf("a", "b")), groups)
    }

    @Test
    fun `duplicates are collapsed and oversized groups are capped`() {
        val many = (1..20).joinToString(",") { "\"t$it\"" }
        val groups = MangaSearchAliases.parseGroups("""{"groups":[["x","x","y"],[$many]]}""")

        assertEquals(listOf("x", "y"), groups[0])
        assertTrue(groups[1].size <= 12)
    }

    @Test
    fun `broken json fails loudly so the caller can keep the defaults`() {
        val result = runCatching { MangaSearchAliases.parseGroups("not json") }

        assertTrue(result.isFailure)
    }

    @Test
    fun `built in groups still expand`() {
        assertTrue(MangaSearchAliases.aliasesOf("大奶").contains("巨乳"))
        assertTrue(MangaSearchAliases.isAlias("上位女"))
        assertTrue(MangaSearchAliases.aliasesOf("不存在的词").isEmpty())
    }
}
