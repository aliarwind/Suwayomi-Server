package suwayomi.tachidesk.llm

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import suwayomi.tachidesk.graphql.types.LlmTitleStructureResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The incremental job is mostly a loop around [LlmStructureBackfill.fillBatch]; these cover the stopping
 * rules, which are the part that decides how much API traffic one interval can produce.
 */
class LlmStructureJobTest {
    private fun batch(
        candidates: Int,
        asked: Int,
        written: Int,
    ) = LlmTitleStructureResult(
        enabled = true,
        dryRun = false,
        version = LlmTitleStructurer.VERSION,
        candidates = candidates,
        asked = asked,
        written = written,
        rows = emptyList(),
    )

    @Test
    fun `drains the backlog until a batch comes back short`() {
        val sizes = ArrayDeque(listOf(200, 200, 37))
        val limits = mutableListOf<Int>()

        val run =
            LlmStructureJob.drain { limit ->
                limits += limit
                val size = sizes.removeFirst()
                batch(size, size, size)
            }

        assertEquals(3, run.batches)
        assertEquals(437, run.written)
        assertFalse(run.morePending)
        assertTrue(limits.all { it == 200 })
    }

    @Test
    fun `stops when another fill holds the lock`() {
        val run = LlmStructureJob.drain { null }

        assertEquals(0, run.batches)
        assertEquals(0, run.written)
        assertFalse(run.morePending)
    }

    @Test
    fun `stops instead of spinning when the model writes nothing`() {
        var calls = 0

        val run =
            LlmStructureJob.drain {
                calls++
                batch(200, 200, 0)
            }

        assertEquals(1, calls)
        assertEquals(1, run.batches)
        assertEquals(0, run.written)
        assertTrue(run.morePending)
    }

    @Test
    fun `caps one run at ten batches`() {
        var calls = 0

        val run =
            LlmStructureJob.drain {
                calls++
                batch(200, 200, 200)
            }

        assertEquals(10, calls)
        assertEquals(2000, run.written)
        assertTrue(run.morePending)
    }

    @Test
    fun `a full batch that only refreshed timestamps keeps the loop going`() {
        val sizes = ArrayDeque(listOf(200, 200, 12))

        val run =
            LlmStructureJob.drain {
                val size = sizes.removeFirst()
                batch(size, 0, 0)
            }

        assertEquals(3, run.batches)
        assertEquals(0, run.written)
        assertFalse(run.morePending)
    }

    @Test
    fun `an empty first batch ends the run without a model call`() {
        val run = LlmStructureJob.drain { batch(0, 0, 0) }

        assertEquals(1, run.batches)
        assertEquals(0, run.written)
        assertFalse(run.morePending)
    }
}
