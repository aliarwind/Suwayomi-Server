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

class LlmQueryPlanTest {
    @Test
    fun `terms are normalized and capped`() {
        val plan =
            LlmQueryPlan(
                titleTerms = List(9) { "作品$it" },
                tags = List(9) { "標籤$it" },
                tagMode = "OR",
                author = "武田弘光",
            ).sanitized()

        assertEquals(LlmQueryPlan.MAX_TITLE_TERMS, plan.titleTerms.size)
        assertEquals(LlmQueryPlan.MAX_TAGS, plan.tags.size)
        assertTrue(plan.tagsAreAny)
        assertEquals("武田弘光", plan.author)
    }

    @Test
    fun `traditional terms are folded to the indexed form`() {
        val plan = LlmQueryPlan(tags = listOf("無修正", "雙馬尾")).sanitized()

        assertEquals(listOf("无修正", "双马尾"), plan.tags)
    }

    @Test
    fun `unknown tag mode falls back to and`() {
        assertTrue(!LlmQueryPlan(tags = listOf("ntr"), tagMode = "whatever").sanitized().tagsAreAny)
    }

    @Test
    fun `blank and overlong terms are dropped`() {
        val plan = LlmQueryPlan(titleTerms = listOf("  ", "x".repeat(80), "百合")).sanitized()

        assertEquals(listOf("百合"), plan.titleTerms)
        assertTrue(plan.author == null)
    }

    @Test
    fun `empty plan is detected`() {
        assertTrue(LlmQueryPlan().isEmpty())
        assertNull(LlmQueryPlan(tagMode = "AND", tags = listOf("")).sanitized().author)
    }

    @Test
    fun `writings of one entity end up in one group`() {
        val plan = LlmQueryPlan(titleTerms = listOf("舰これ", "舰c", "舰队collection")).sanitized()
        // Shape of the real alias file: "舰c"/"舰队collection" share a group whose long member also
        // contains the literal "舰これ".
        val kancolle = listOf("舰队これくしょん -舰これ-", "舰colle", "kancolle")
        val aliases = mapOf("舰c" to kancolle, "舰队collection" to kancolle)

        assertEquals(
            listOf(listOf("舰これ", "舰c", "舰队collection")),
            plan.titleGroups { aliases[it].orEmpty() },
        )
    }

    @Test
    fun `different entities stay in separate groups`() {
        val plan = LlmQueryPlan(titleTerms = listOf("ブルアカ", "マシュ")).sanitized()

        assertEquals(listOf(listOf("ぶるあか"), listOf("ましゅ")), plan.titleGroups { emptyList() })
    }

    @Test
    fun `a term that contains another one is the same entity`() {
        val plan = LlmQueryPlan(titleTerms = listOf("fate", "fate stay night")).sanitized()

        assertEquals(1, plan.titleGroups { emptyList() }.size)
    }
}
