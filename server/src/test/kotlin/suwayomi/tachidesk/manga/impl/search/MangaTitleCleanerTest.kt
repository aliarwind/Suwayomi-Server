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

class MangaTitleCleanerTest {
    @Test
    fun `circle and artist brackets are separated from the work name`() {
        val cleaned =
            MangaTitleCleaner.clean(
                "[聖性屋 (聖シロー)] 聖女と魔女の乱交パーティ (Fate／Grand Order) [黎欧出资汉化] [DL版]",
            )

        // normalize() folds katakana to hiragana and traditionals to simplified, hence the expected forms.
        assertEquals("圣女と魔女の乱交ぱーてぃ (fate/grand order)", cleaned.title)
        assertTrue(cleaned.names.contains("圣性屋"), cleaned.names)
        assertTrue(cleaned.names.contains("しろー"), cleaned.names)
    }

    @Test
    fun `release metadata is dropped from the index`() {
        val indexed =
            MangaSearchText.index(
                "[聖性屋 (聖シロー)] 聖女と魔女の乱交パーティ [黎欧出资汉化] [DL版]",
                null,
                null,
                null,
            )

        assertFalse(indexed.contains("汉化"), indexed)
        assertFalse(indexed.contains("dl版"), indexed)
        assertTrue(indexed.contains("圣性屋"), indexed)
        assertTrue(indexed.contains("しろー"), indexed)
    }

    @Test
    fun `language markers and comic market editions are dropped`() {
        val cleaned = MangaTitleCleaner.clean("[さくら] 放課後の教室 (C97) [中国翻訳]")

        assertEquals("放课后の教室", cleaned.title)
        assertFalse(MangaSearchText.index("[さくら] 放課後の教室 (C97) [中国翻訳]", null, null, null).contains("c97"))
    }

    @Test
    fun `content flags are kept as search terms`() {
        val indexed = MangaSearchText.index("[ぷりんたい] 家の風紀は乱れてる！ [中国翻訳] [無修正]", null, null, null)

        assertTrue(indexed.contains("无修正"), indexed)
        assertFalse(indexed.contains("翻訳"), indexed)
    }

    @Test
    fun `the translated side of a title is kept`() {
        val indexed = MangaSearchText.index("[社团] 日本語タイトル｜中文标题 [中国翻訳]", null, null, null)

        assertTrue(indexed.contains("たいとる"), indexed)
        assertTrue(indexed.contains("中文标题"), indexed)
    }

    @Test
    fun `parenthesised text that belongs to the title is kept`() {
        val cleaned = MangaTitleCleaner.clean("Nekokoyoshi (爆机少女喵小吉) - Sweet Maid")

        assertTrue(cleaned.title.contains("(爆机少女喵小吉)"), cleaned.title)
        assertEquals("", cleaned.names)
    }

    @Test
    fun `original work markers are dropped`() {
        val cleaned = MangaTitleCleaner.clean("[みつ] ひどいこと (オリジナル) [DL版]")

        assertEquals("ひどいこと", cleaned.title)
    }

    @Test
    fun `magazine issues are dropped`() {
        val cleaned = MangaTitleCleaner.clean("[アシオミマサト] サマーラブセイバー (COMIC 快楽天 2021年8月号) [中国翻訳]")

        assertEquals("さまーらぶせいばー", cleaned.title)
    }

    @Test
    fun `a title made only of metadata falls back to the raw text`() {
        val cleaned = MangaTitleCleaner.clean("[白杨汉化组]")

        assertEquals("[白杨汉化组]", cleaned.title)
        assertTrue(MangaSearchText.index("[白杨汉化组]", null, null, null).contains("白杨汉化组"))
    }

    @Test
    fun `scanlation groups stored in the author column are not indexed`() {
        val indexed = MangaSearchText.index("[ぷりんたい] 作品名", "中国翻訳", "彩漫社上色组", "NTR")

        assertFalse(indexed.contains("汉化"), indexed)
        assertFalse(indexed.contains("翻訳"), indexed)
        assertFalse(indexed.contains("上色"), indexed)
        assertTrue(indexed.contains("ntr"), indexed)
        assertTrue(indexed.contains("作品名"), indexed)
    }

    @Test
    fun `real author names are still indexed`() {
        val indexed = MangaSearchText.index("作品名", "武田弘光", null, null)

        assertTrue(indexed.contains("武田弘光"), indexed)
    }

    @Test
    fun `blank titles clean to empty`() {
        assertEquals("", MangaTitleCleaner.clean(null).title)
        assertEquals("", MangaTitleCleaner.clean("   ").title)
    }
}
