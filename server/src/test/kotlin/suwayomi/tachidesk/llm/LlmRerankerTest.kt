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

class LlmRerankerTest {
    private val candidates =
        listOf(
            LlmCandidate(1, "Fate 玛修"),
            LlmCandidate(2, "NTR 人妻"),
            LlmCandidate(3, "百合"),
        )

    @Test
    fun `valid ids are kept in the order the model returned them`() {
        val hits =
            LlmReranker.parseAnswer(
                """{"hits":[{"id":3,"score":80,"reason":"百合"},{"id":1,"score":95,"reason":"系列 Fate"}]}""",
                candidates,
                limit = 10,
            )

        assertEquals(listOf(3, 1), hits?.map { it.id })
    }

    @Test
    fun `ids outside the candidate set are dropped instead of trusted`() {
        val hits =
            LlmReranker.parseAnswer(
                """{"hits":[{"id":1,"score":90,"reason":"ok"},{"id":999,"score":99,"reason":"编的"}]}""",
                candidates,
                limit = 10,
            )

        assertEquals(listOf(1), hits?.map { it.id })
    }

    @Test
    fun `an empty ranking is a valid answer, broken json is not`() {
        assertEquals(emptyList(), LlmReranker.parseAnswer("""{"hits":[]}""", candidates, limit = 10))
        assertNull(LlmReranker.parseAnswer("抱歉，我不能帮你。", candidates, limit = 10))
    }

    @Test
    fun `a bare array answer is accepted`() {
        val hits =
            LlmReranker.parseAnswer(
                """[{"id":2,"score":70,"reason":"NTR"}]""",
                candidates,
                limit = 10,
            )

        assertEquals(listOf(2), hits?.map { it.id })
    }

    @Test
    fun `json wrapped in a fenced block is extracted`() {
        val answer = "好的：\n```json\n{\"hits\":[{\"id\":1,\"score\":88,\"reason\":\"Fate\"}]}\n```"

        assertEquals(listOf(1), LlmReranker.parseAnswer(answer, candidates, limit = 10)?.map { it.id })
    }

    @Test
    fun `scores are clamped, reasons are normalized and duplicates collapsed`() {
        val answer =
            """
            {"hits":[
              {"id":1,"score":420,"reason":"  Fate   玛修 "},
              {"id":1,"score":10,"reason":"重复"},
              {"id":2,"score":-5,"reason":""}
            ]}
            """.trimIndent()

        val hits = LlmReranker.parseAnswer(answer, candidates, limit = 10)!!

        assertEquals(listOf(1, 2), hits.map { it.id })
        assertEquals(100, hits[0].score)
        assertEquals(0, hits[1].score)
        assertEquals("Fate 玛修", hits[0].reason)
    }

    @Test
    fun `the ranking is cut to the requested limit`() {
        val answer =
            """{"hits":[{"id":1},{"id":2},{"id":3}]}"""

        assertEquals(2, LlmReranker.parseAnswer(answer, candidates, limit = 2)?.size)
    }

    @Test
    fun `a truncated answer keeps the complete hits`() {
        val answer =
            """{"hits":[{"id":1,"score":90,"reason":"ok"},{"id":2,"score":80,"reason":"ok"},{"id":3,"sco"""

        val hits = LlmReranker.parseAnswer(answer, candidates, limit = 10)

        assertEquals(listOf(1, 2), hits?.map { it.id })
    }

    @Test
    fun `long reasons are truncated`() {
        val longReason = "很".repeat(LlmReranker.MAX_REASON_LENGTH + 20)
        val hits =
            LlmReranker.parseAnswer(
                """{"hits":[{"id":1,"score":50,"reason":"$longReason"}]}""",
                candidates,
                limit = 5,
            )!!

        assertTrue(hits.single().reason.length <= LlmReranker.MAX_REASON_LENGTH)
    }
}
