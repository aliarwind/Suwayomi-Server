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
    fun `structured series and characters are indexed`() {
        val indexed =
            MangaSearchText.index(
                "光と塩非合理 (ブルーアーカイブ) [中国翻訳]",
                "Yanje",
                null,
                "蔚藍檔案,調月莉央",
                StructuredTitle.Fields(
                    cleanTitle = "光と塩非合理",
                    series = "ブルーアーカイブ",
                    characters = listOf("調月莉央"),
                    circle = null,
                    artist = "Yanje",
                    aliases = listOf("蔚蓝档案", "Blue Archive"),
                    flags = listOf("中国翻訳"),
                ),
            )

        // the series is what a `ブルアカ` query has to hit, even when the title only has the kana
        assertTrue(indexed.contains("ぶるーあーかいぶ"), indexed)
        assertTrue(indexed.contains("调月莉央"), indexed)
        assertTrue(indexed.contains("blue"), indexed)
        // the model's release markers stay out of the index, same as the rule based cleaner
        assertTrue(!indexed.contains("中国翻訳"), indexed)
    }

    @Test
    fun `only the content flags of the structure are indexed`() {
        val indexed =
            MangaSearchText.index(
                "何かの本 [汉化] [DL版] [無修正]",
                null,
                null,
                null,
                StructuredTitle.Fields(
                    cleanTitle = "何かの本",
                    series = null,
                    characters = emptyList(),
                    circle = null,
                    artist = null,
                    aliases = emptyList(),
                    flags = listOf("汉化", "DL版", "無修正"),
                ),
            )

        assertTrue(indexed.contains("无修正"), indexed)
        assertTrue(!indexed.contains("汉化"), indexed)
        assertTrue(!indexed.contains("dl版"), indexed)
    }

    @Test
    fun `an empty structured title falls back to the title`() {
        val indexed =
            MangaSearchText.index(
                "[PixivFANBOX] [中国翻訳]",
                null,
                null,
                null,
                StructuredTitle.Fields(
                    cleanTitle = null,
                    series = null,
                    characters = emptyList(),
                    circle = null,
                    artist = null,
                    aliases = emptyList(),
                    flags = emptyList(),
                ),
            )

        // a book whose title carries no work name must not disappear from the search
        assertTrue(indexed.contains("pixivfanbox"), indexed)
    }

    @Test
    fun `query tokens are capped`() {
        val tokens = MangaSearchText.queryTokens("a b c d e f g h i j k l")
        assertEquals(MangaSearchText.MAX_QUERY_TOKENS, tokens.size)
    }

    @Test
    fun `every indexed token carries the field it came from`() {
        val indexed =
            MangaSearchText.index(
                "光と塩非合理 (ブルーアーカイブ) [中国翻訳]",
                "Yanje",
                null,
                "全彩",
                StructuredTitle.Fields(
                    cleanTitle = "光と塩非合理",
                    series = "ブルーアーカイブ",
                    characters = listOf("調月莉央"),
                    circle = "翔玄亭",
                    artist = "Yanje",
                    aliases = listOf("蔚蓝档案"),
                    flags = listOf("中国翻訳"),
                ),
            )

        assertTrue(indexed.contains("T:光と塩非合理 "), indexed)
        assertTrue(indexed.contains("S:ぶるーあーかいぶ "), indexed)
        assertTrue(indexed.contains("C:调月莉央 "), indexed)
        assertTrue(indexed.contains("N:翔玄亭 "), indexed)
        assertTrue(indexed.contains("N:yanje "), indexed)
        assertTrue(indexed.contains("X:蔚蓝档案 "), indexed)
        assertTrue(indexed.contains("G:全彩 "), indexed)
    }

    @Test
    fun `the index ends with a separator so an exact token can be matched`() {
        assertTrue(MangaSearchText.index("Title", null, null, null).endsWith(" "))
        assertEquals("", MangaSearchText.index(null, null, null, null))
    }

    @Test
    fun `field markers stay out of the way of lowercase queries`() {
        val indexed = MangaSearchText.index("Title", null, null, null)
        // markers are uppercase because everything indexed is lowercased: "t:title" can never appear, so a
        // one letter query cannot look like a field marker
        assertTrue(!indexed.contains("t:title"), indexed)
    }

    @Test
    fun `tokensOf splits a multi word value the way the index does`() {
        assertEquals(listOf("fate", "grand", "order"), MangaSearchText.tokensOf("Fate/Grand Order"))
        assertEquals(listOf("ぶるあか"), MangaSearchText.tokensOf("ブルアカ"))
        assertEquals(emptyList(), MangaSearchText.tokensOf("  "))
    }
}
