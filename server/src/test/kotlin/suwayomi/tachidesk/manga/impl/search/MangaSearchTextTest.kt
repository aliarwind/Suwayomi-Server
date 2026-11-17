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

class MangaSearchTextTest {
    @Test
    fun `traditional text is folded to simplified`() {
        assertEquals("双马尾", MangaSearchText.normalize("雙馬尾"))
        assertEquals("玛修", MangaSearchText.normalize("瑪修"))
    }

    @Test
    fun `katakana is folded to hiragana`() {
        assertEquals("ましゅ", MangaSearchText.normalize("マシュ"))
    }

    @Test
    fun `full width characters and case are folded`() {
        assertEquals("ntr", MangaSearchText.normalize("ＮＴＲ"))
        assertEquals("fgo", MangaSearchText.normalize("FGO"))
    }

    @Test
    fun `simplified query token matches a traditional title index`() {
        val indexed = MangaSearchText.index("雙馬尾の少女", "作者A", null, "全彩,NTR")
        val tokens = MangaSearchText.queryTokens("双马尾")
        assertEquals(listOf("双马尾"), tokens)
        tokens.forEach { token ->
            assertTrue(indexed.contains(token), "expected '$indexed' to contain '$token'")
        }
    }

    @Test
    fun `latin and cjk runs are split`() {
        assertEquals(listOf("fgo", "玛修"), MangaSearchText.queryTokens("FGO玛修"))
        assertEquals(listOf("fgo", "玛修"), MangaSearchText.queryTokens("FGO 玛修"))
    }

    @Test
    fun `chinese particles split the query`() {
        assertEquals(listOf("玛修"), MangaSearchText.queryTokens("玛修的本"))
        assertEquals(listOf("fgo"), MangaSearchText.queryTokens("求FGO的本子"))
    }

    @Test
    fun `punctuation is ignored`() {
        assertEquals(listOf("ntr"), MangaSearchText.queryTokens("NTR!!!"))
        assertEquals(listOf("fgo", "玛修"), MangaSearchText.queryTokens("【FGO】玛修……"))
    }

    @Test
    fun `query made only of stop words yields no tokens`() {
        assertEquals(emptyList(), MangaSearchText.queryTokens("本"))
        assertEquals(emptyList(), MangaSearchText.queryTokens(""))
        assertEquals(emptyList(), MangaSearchText.queryTokens(null))
    }

    @Test
    fun `index joins all fields and drops nulls`() {
        val indexed = MangaSearchText.index("Title", null, "Artist", "GenreA, GenreB")
        assertTrue(indexed.contains("title"))
        assertTrue(indexed.contains("artist"))
        assertTrue(indexed.contains("genrea"))
        assertTrue(indexed.contains("genreb"))
    }

    @Test
    fun `query tokens are capped`() {
        val tokens = MangaSearchText.queryTokens("a b c d e f g h i j k l")
        assertEquals(MangaSearchText.MAX_QUERY_TOKENS, tokens.size)
    }
}
