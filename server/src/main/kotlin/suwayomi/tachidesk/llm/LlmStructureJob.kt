package suwayomi.tachidesk.llm

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import suwayomi.tachidesk.graphql.types.LlmTitleStructureResult
import suwayomi.tachidesk.manga.impl.search.MangaSearchVocabulary
import suwayomi.tachidesk.server.serverConfig
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.minutes

/**
 * Keeps [suwayomi.tachidesk.manga.model.table.MangaLlmIndexTable] current without anyone running the
 * backfill script.
 *
 * The structured title index is what makes the library search find a book by its series or characters
 * (`ブルアカ`, `マシュ`) instead of only by the words in its title. New books and books the source
 * refreshed get a new `last_fetched_at`, so they are pending until the model has seen them; this job
 * looks at that pending set every [serverConfig.llmStructureJobIntervalMinutes] and drains it in model
 * batches.
 *
 * Scheduling notes:
 * - it never runs before [serverConfig.llmStructureJobInitialDelayMinutes], so a restart does not add
 *   model calls to the startup path (the search index rebuild runs there already);
 * - one run is capped at [MAX_BATCHES_PER_RUN] model calls, so a large backlog (enabling this on an
 *   unstructured library) drains over several intervals instead of an hour of continuous API traffic;
 * - the settings are read on every iteration, so turning it off (or changing the interval) in the
 *   settings takes effect on the next run without a restart;
 * - nothing is written while another fill is running (the manual mutation), see
 *   [LlmStructureBackfill.fillBatch].
 */
object LlmStructureJob {
    /** Books per model call; matches the manual backfill so both paths share the same cost shape. */
    private const val BATCH_LIMIT = 200

    /** Ten model calls per run: roughly 2,000 books. The rest waits for the next interval. */
    private const val MAX_BATCHES_PER_RUN = 10

    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)

    /** What one scheduled run did; [morePending] means the cap was reached and the backlog is not empty. */
    data class Run(
        val batches: Int,
        val written: Int,
        val morePending: Boolean,
    )

    fun startAsync() {
        if (!started.compareAndSet(false, true)) return

        scope.launch {
            val initialDelay = serverConfig.llmStructureJobInitialDelayMinutes.value
            logger.info {
                "AI structured titles: incremental job starts in $initialDelay min " +
                    "(interval ${serverConfig.llmStructureJobIntervalMinutes.value} min, " +
                    "enabled=${serverConfig.llmStructureJobEnabled.value})"
            }
            delay(initialDelay.minutes)

            while (isActive) {
                runCatching { runOnce() }
                    .onSuccess { run ->
                        if (run.written > 0) {
                            logger.info {
                                "AI structured titles: wrote ${run.written} books in ${run.batches} batches" +
                                    if (run.morePending) ", more pending - continuing next interval" else ""
                            }
                        } else {
                            logger.debug { "AI structured titles: nothing pending" }
                        }
                    }.onFailure { logger.error(it) { "AI structured titles: incremental run failed" } }

                delay(serverConfig.llmStructureJobIntervalMinutes.value.minutes)
            }
        }
    }

    /**
     * One scheduled run: skips when the feature is off or the API is not configured, otherwise drains up to
     * [MAX_BATCHES_PER_RUN] batches.
     *
     * The vocabulary is rebuilt when rows were written, because the words the model adds (`series`,
     * `characters`, `aliases`) are exactly what `MangaSearchVocabulary.plan()` decides about: a new series
     * name would be dropped as "not in the library" until the vocabulary has seen it.
     */
    fun runOnce(): Run {
        if (!serverConfig.llmStructureJobEnabled.value) {
            logger.debug { "AI structured titles: incremental job is disabled" }
            return Run(0, 0, false)
        }
        if (!LlmClient.isConfigured()) {
            logger.debug { "AI structured titles: no LLM API configured, skipping" }
            return Run(0, 0, false)
        }

        val run = drain { limit -> LlmStructureBackfill.fillBatch(dryRun = false, limit = limit) }
        if (run.written > 0) MangaSearchVocabulary.build()
        return run
    }

    /**
     * The batch loop, with the fill injected so it can be tested without a database or an API.
     *
     * Stops when the backlog is drained (`candidates < limit`), when another fill holds the lock (null),
     * when the model produced nothing for a non-empty batch (a failing API would otherwise spin), or when
     * the per-run cap is reached.
     */
    internal fun drain(fill: (Int) -> LlmTitleStructureResult?): Run {
        var batches = 0
        var written = 0
        var morePending = false

        while (batches < MAX_BATCHES_PER_RUN) {
            val batch = fill(BATCH_LIMIT) ?: break
            batches++
            written += batch.written

            if (batch.candidates < BATCH_LIMIT) {
                morePending = false
                break
            }

            morePending = true
            // the batch had books to ask about but none were written: the API is failing, back off
            if (batch.asked > 0 && batch.written == 0) break
        }

        return Run(batches, written, morePending)
    }
}
