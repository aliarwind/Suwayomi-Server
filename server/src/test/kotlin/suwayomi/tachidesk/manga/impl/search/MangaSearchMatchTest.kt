package suwayomi.tachidesk.manga.impl.search

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The index stores one token per word with a field marker in front, so a multi word alias no longer appears
 * as one substring. These are the cases that broke when the markers were added (five `ブルアカ` entries were
 * lost through the alias "blue archive").
 */
class MangaSearchMatchTest {
    private fun library() =
        listOf(
            MangaSearchText.index("光と塩非合理 (ブルーアーカイブ)", "Yanje", null, "全彩"),
            MangaSearchText.index("白露很白 – 飞鸟马时", "40P", null, "blue_archive,toki_asuma"),
            MangaSearchText.index("Kakudate Karin - Blue Archive", "AI Player", null, null),
            MangaSearchText.index("何かの本", null, null, null),
        )

    @Test
    fun `a multi word alias matches when all of its words are indexed`() {
        val words = MangaSearchMatch.wordsOf("Blue Archive")
        assertEquals(listOf("blue", "archive"), words)

        val matches = library().count { text -> MangaSearchMatch.matches(words, text) }
        assertEquals(2, matches, "both books that store the two words as separate tokens have to match")
    }

    @Test
    fun `a multi word alias does not match a book with only one of its words`() {
        val words = MangaSearchMatch.wordsOf("blue archive")
        val onlyBlue = MangaSearchText.index("blue だけの本", null, null, null)
        assertFalse(MangaSearchMatch.matches(words, onlyBlue))
    }

    @Test
    fun `a single word keeps substring matching`() {
        assertTrue(MangaSearchMatch.matches(MangaSearchMatch.wordsOf("怀孕"), MangaSearchText.index("怀孕中の本", null, null, null)))
        assertEquals(listOf("怀孕"), MangaSearchMatch.wordsOf("怀孕"))
    }

    @Test
    fun `a term with no words matches everything, like the empty like pattern did`() {
        assertTrue(MangaSearchMatch.matches(MangaSearchMatch.wordsOf("!!!"), "anything"))
    }
}
