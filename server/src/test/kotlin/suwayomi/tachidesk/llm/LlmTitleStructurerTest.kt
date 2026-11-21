package suwayomi.tachidesk.llm

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LlmTitleStructurerTest {
    private val row =
        LlmTitleStructurer.Row(
            id = 1,
            title = "[翔玄亭 (久遠)] しんしゅ (艦隊これくしょん-艦これ-) [糖分超标汉化组] [DL版]",
            tags = "艦これ,神州丸,肛交,巨乳",
            author = "翔玄亭 (久遠)",
        )

    private fun parse(
        answer: String,
        rows: List<LlmTitleStructurer.Row> = listOf(row),
    ): LlmTitleStructurer.Structure = LlmTitleStructurer.parseAnswer(answer, rows)!!.single()

    @Test
    fun `a faithful answer is accepted field by field`() {
        val structure =
            parse(
                """
                {"rows":[{"id":1,"cleanTitle":"しんしゅ","series":"艦隊これくしょん-艦これ-","characters":["神州丸"],
                  "circle":"翔玄亭","artist":"久遠","lang":"ja","flags":["汉化","DL版"],"aliases":["KanColle","舰队Collection"]}]}
                """.trimIndent(),
            )

        assertEquals("しんしゅ", structure.cleanTitle)
        assertEquals("艦隊これくしょん-艦これ-", structure.series)
        assertEquals(listOf("神州丸"), structure.characters)
        assertEquals("翔玄亭", structure.circle)
        assertEquals("久遠", structure.artist)
        assertEquals("ja", structure.lang)
        assertEquals(listOf("汉化", "DL版"), structure.flags)
        assertEquals(listOf("KanColle", "舰队Collection"), structure.aliases)
        assertTrue(structure.rejects.isEmpty())
    }

    @Test
    fun `a translated name is rejected because it is not in the source`() {
        val structure =
            parse(
                """
                {"rows":[{"id":1,"cleanTitle":"しんしゅ","series":"舰队Collection","characters":["アリス・マーガトロイド"],
                  "circle":"","artist":"","lang":"ja","flags":[],"aliases":[]}]}
                """.trimIndent(),
            )

        assertNull(structure.series)
        assertTrue(structure.characters.isEmpty())
        assertTrue(structure.rejects.any { it.startsWith("series:") })
        assertTrue(structure.rejects.any { it.startsWith("characters:") })
    }

    @Test
    fun `cleanTitle may only delete characters`() {
        // "圣女的魔女" is a rewrite of "聖女と魔女", not a deletion
        val structure =
            parse(
                """
                {"rows":[{"id":1,"cleanTitle":"圣女的魔女","series":"","characters":[],"circle":"","artist":"",
                  "lang":"ja","flags":[],"aliases":[]}]}
                """.trimIndent(),
            )

        assertNull(structure.cleanTitle)
        assertTrue(structure.rejects.any { it.startsWith("cleanTitle:") })
    }

    @Test
    fun `release metadata is not accepted as a name`() {
        val structure =
            parse(
                """
                {"rows":[{"id":1,"cleanTitle":"しんしゅ","series":"","characters":[],"circle":"糖分超标汉化组",
                  "artist":"DL版","lang":"ja","flags":[],"aliases":[]}]}
                """.trimIndent(),
            )

        assertNull(structure.circle)
        assertNull(structure.artist)
        assertEquals(2, structure.rejects.count { it.startsWith("circle:") || it.startsWith("artist:") })
    }

    @Test
    fun `unknown flags and languages fall back instead of being stored`() {
        val structure =
            parse(
                """
                {"rows":[{"id":1,"cleanTitle":"しんしゅ","series":"","characters":[],"circle":"","artist":"",
                  "lang":"kr","flags":["合订本","无码"],"aliases":[]}]}
                """.trimIndent(),
            )

        assertEquals("unknown", structure.lang)
        assertEquals(listOf("無碼"), structure.flags)
        assertTrue(structure.rejects.any { it == "flags:合订本" })
        assertTrue(structure.rejects.any { it == "lang:kr" })
    }

    @Test
    fun `a row the model skipped is still marked processed`() {
        val structures = LlmTitleStructurer.parseAnswer("""{"rows":[]}""", listOf(row))!!

        assertEquals(1, structures.size)
        assertNull(structures.single().cleanTitle)
        assertEquals(listOf("missing"), structures.single().rejects)
    }

    @Test
    fun `broken json loses the batch instead of storing garbage`() {
        assertNull(LlmTitleStructurer.parseAnswer("抱歉，我不能帮你。", listOf(row)))
    }

    @Test
    fun `tidy removes the event code, the artist prefix, trailing metadata and page counts`() {
        assertEquals("ラクガキ帳", LlmTitleStructurer.tidy("C97ラクガキ帳", null))
        assertEquals("Samsung Sam", LlmTitleStructurer.tidy("Hana Bunny - Samsung Sam", "Hana Bunny"))
        assertEquals("Bulma", LlmTitleStructurer.tidy("Peachuu ｜ Bulma", "Peachuu"))
        // the stored author follows the same "name - work" convention even when the model left artist empty
        assertEquals("碧蓝航线 爱宕", LlmTitleStructurer.tidy("阿半今天很开心 - 碧蓝航线 爱宕", null, "阿半今天很开心"))
        // only the metadata goes: "本" is a counter, a rule must not guess that it belongs to the noise
        assertEquals("槍間家6本", LlmTitleStructurer.tidy("槍間家6本汉化全集", null))
        assertEquals("观者", LlmTitleStructurer.tidy("观者 合集", null))
        assertEquals("3a46.ゆかゆゆBBC 西行寺幽々子&八雲紫", LlmTitleStructurer.tidy("3a46.ゆかゆゆBBC 西行寺幽々子&八雲紫【111p】", null))
    }

    @Test
    fun `an event code that is part of the work name is kept`() {
        // "C101のほん" is the whole name; stripping the code would leave three kana
        assertEquals("C101のほん", LlmTitleStructurer.tidy("C101のほん", null))
        assertEquals("ラクガキ帳", LlmTitleStructurer.tidy("C97ラクガキ帳", null))
    }

    @Test
    fun `a series marker is not a series`() {
        val answer =
            """
            {"rows":[{"id":1,"cleanTitle":"しんしゅ","series":"オリジナル","characters":[],"circle":"","artist":"",
              "lang":"ja","flags":[],"aliases":[]}]}
            """.trimIndent()

        val structure =
            parse(
                answer,
                listOf(row.copy(title = "[翔玄亭 (久遠)] しんしゅ (オリジナル) [DL版]")),
            )

        assertNull(structure.series)
        assertTrue(structure.rejects.any { it.startsWith("series:") })
    }

    @Test
    fun `a name with different spacing is still the same name`() {
        val structure =
            parse(
                """
                {"rows":[{"id":1,"cleanTitle":"しんしゅ","series":"","characters":["周防院 涼華"],"circle":"","artist":"",
                  "lang":"ja","flags":[],"aliases":[]}]}
                """.trimIndent(),
                listOf(row.copy(title = "天使学園の寮姦性活 Side Story -周防院涼華-", tags = "")),
            )

        assertEquals(listOf("周防院 涼華"), structure.characters)
    }

    @Test
    fun `the input hash ignores traditional-simplified and katakana differences`() {
        val simplified = LlmTitleStructurer.inputHash(LlmTitleStructurer.Row(1, "圣女和魔女", "舰これ", null))
        val traditional = LlmTitleStructurer.inputHash(LlmTitleStructurer.Row(2, "聖女和魔女", "艦これ", null))
        val other = LlmTitleStructurer.inputHash(LlmTitleStructurer.Row(3, "聖女和魔女", "艦これ", "作者"))

        assertEquals(simplified, traditional)
        assertTrue(simplified != other)
    }
}
