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

class MangaSearchVocabularyTest {
    private val texts =
        listOf(
            "武田弘光 ツンデロ 新装版",
            "婊子 妓女 娼妇 百合 肉欲 傲慢 肉铠 全彩",
            "cos 服装 小孩 开大车",
            "fgo 玛修 fate grand order",
        )

    private val tags = setOf("婊子", "妓女", "娼妇", "百合", "肉欲", "傲慢", "肉铠", "开大车")

    @Test
    fun `filler glued to a known name is resolved to the name`() {
        val plan = MangaSearchVocabulary.plan("像武田弘光的画风", texts, tags)

        assertEquals(listOf("武田弘光"), plan.allTerms)
        assertEquals(emptyList(), plan.anyTerms)
        assertTrue(plan.droppedTerms.contains("画风"))
    }

    @Test
    fun `glued filler without the particle is still resolved`() {
        val plan = MangaSearchVocabulary.plan("像武田弘光", texts, tags)

        assertEquals(listOf("武田弘光"), plan.allTerms)
        assertEquals(emptyList(), plan.droppedTerms)
    }

    @Test
    fun `synonym list becomes an OR group of tags`() {
        val plan = MangaSearchVocabulary.plan("女主是妓女、娼妇、婊子", texts, tags)

        assertEquals(emptyList(), plan.allTerms)
        assertEquals(setOf("妓女", "娼妇", "婊子"), plan.anyTerms.toSet())
        assertTrue(plan.droppedTerms.contains("女主"))
    }

    @Test
    fun `unknown words do not empty the query`() {
        val plan = MangaSearchVocabulary.plan("傲慢的上位女", texts, tags)

        assertEquals(emptyList(), plan.allTerms)
        assertEquals(listOf("傲慢"), plan.anyTerms)
        assertTrue(plan.droppedTerms.contains("上位女"))
    }

    @Test
    fun `multiple genre tags are OR-ed`() {
        val plan = MangaSearchVocabulary.plan("肉欲的百合", texts, tags)

        assertEquals(emptyList(), plan.allTerms)
        assertEquals(setOf("肉欲", "百合"), plan.anyTerms.toSet())
    }

    @Test
    fun `known text terms and tags are combined`() {
        val plan = MangaSearchVocabulary.plan("fgo 玛修 婊子", texts, tags)

        assertEquals(setOf("fgo", "玛修"), plan.allTerms.toSet())
        assertEquals(listOf("婊子"), plan.anyTerms)
    }

    @Test
    fun `two character words are found inside the indexed text`() {
        val plan = MangaSearchVocabulary.plan("武田弘光", texts, tags)

        assertEquals(listOf("武田弘光"), plan.allTerms)
        assertEquals(emptyList(), plan.droppedTerms)
    }

    @Test
    fun `empty vocabulary degrades to plain AND`() {
        val plan = MangaSearchVocabulary.plan("fgo 玛修", emptyList(), emptySet())

        assertEquals(setOf("fgo", "玛修"), plan.allTerms.toSet())
        assertEquals(emptyList(), plan.anyTerms)
        assertEquals(emptyList(), plan.droppedTerms)
    }

    @Test
    fun `query of only unknown words falls back to the raw token`() {
        val plan = MangaSearchVocabulary.plan("画风", texts, tags)

        assertEquals(listOf("画风"), plan.allTerms)
        assertEquals(emptyList(), plan.droppedTerms)
    }

    @Test
    fun `blank query yields an empty plan`() {
        assertTrue(MangaSearchVocabulary.plan("", texts, tags).isEmpty)
        assertTrue(MangaSearchVocabulary.plan(null, texts, tags).isEmpty)
    }
}
