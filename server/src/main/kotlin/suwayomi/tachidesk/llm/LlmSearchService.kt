package suwayomi.tachidesk.llm

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Parses queries with the LLM and remembers the result.
 *
 * The cache is what keeps a library search from costing one API call per keystroke: the same query is only
 * sent once per [POSITIVE_TTL_MS]. Failures are cached separately and much shorter, so a transient outage
 * does not hammer the provider, but a fixed configuration is picked up again quickly.
 */
object LlmSearchService {
    private val logger = KotlinLogging.logger {}

    private const val POSITIVE_TTL_MS = 10 * 60 * 1000L
    private const val NEGATIVE_TTL_MS = 60 * 1000L
    private const val CACHE_SIZE = 200

    private class Entry(
        val plan: LlmQueryPlan?,
        val timestamp: Long,
    )

    private val cache =
        object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
                size > CACHE_SIZE
        }

    fun isEnabled(): Boolean = LlmClient.isConfigured()

    /** The plan for [query], or `null` when the feature is off, the model failed, or it found nothing. */
    fun plan(query: String?): LlmQueryPlan? {
        val trimmed = query?.trim().orEmpty()
        if (trimmed.isEmpty() || !isEnabled()) return null

        val now = System.currentTimeMillis()
        synchronized(cache) {
            cache[trimmed]?.let { entry ->
                val ttl = if (entry.plan != null) POSITIVE_TTL_MS else NEGATIVE_TTL_MS
                if (now - entry.timestamp < ttl) return entry.plan
            }
        }

        val started = System.currentTimeMillis()
        val plan = runCatching { LlmQueryParser.parse(trimmed) }.getOrElse { error ->
            logger.warn(error) { "LLM query parsing failed" }
            null
        }

        synchronized(cache) {
            cache[trimmed] = Entry(plan, System.currentTimeMillis())
        }

        logger.debug {
            "LLM plan for '$trimmed' in ${System.currentTimeMillis() - started}ms: " +
                (plan ?: "none")
        }

        return plan
    }
}
