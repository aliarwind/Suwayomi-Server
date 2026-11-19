package suwayomi.tachidesk.llm

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One recalled book as the model sees it: an id to answer with and the visible title to judge.
 *
 * Only the title is sent on purpose - it holds the series, the circle and most tags in brackets, while
 * the description adds privacy and tokens without adding signal.
 */
data class LlmCandidate(
    val id: Int,
    val title: String,
)

/** What the model answered about one candidate. [score] is 0..100, [reason] is a short Chinese phrase. */
@Serializable
data class LlmRerankHit(
    val id: Int,
    val score: Int = 0,
    val reason: String = "",
)

@Serializable
private data class LlmRerankAnswer(
    val hits: List<LlmRerankHit> = emptyList(),
)

/**
 * Second call of the two step search: the local search recalls a few hundred books, the model orders the
 * ones that actually match the query and says why.
 *
 * The model cannot invent results: every id is checked against the candidate set and everything else is
 * dropped. A failed call (timeout, refusal, broken JSON) returns `null`, which the caller reports as
 * "degraded" instead of showing a wrong ranking.
 */
object LlmReranker {
    private val logger = KotlinLogging.logger {}
    private val json = Json { ignoreUnknownKeys = true }

    /** Longest title sent per candidate; longer ones are truncated, the first part is the informative one. */
    private const val MAX_TITLE_INPUT = 100

    /** Reasons are shown in a card subtitle, anything longer gets cut. */
    const val MAX_REASON_LENGTH = 40

    private const val MIN_ANSWER_TOKENS = 1500
    private const val MAX_ANSWER_TOKENS = 8000

    private val SYSTEM_PROMPT =
        """
        你是漫画库的搜索助手。用户想找一些漫画，下面给你一批检索到的候选（JSON 数组，每项有 id 和 t 两个字段，
        t 是标题）。请按"和用户需求的匹配程度"从高到低挑出最相关的，并给每本一句理由。

        只输出 JSON，格式：
        {"hits":[{"id":123,"score":90,"reason":"系列 Fate，角色瑪修"}]}

        规则：
        1. id 只能来自候选，禁止编造；不确定算不算相关的就不要放进结果。
        2. score 是 0-100 的整数，越高越匹配。
        3. reason 用中文，不超过 12 个字，只说"为什么匹配"（如系列、角色、作者、标签），不要描述内容细节。
        4. 用户没说过的属性不要当理由；如果候选都不相关，返回 {"hits":[]}。
        """.trimIndent()

    /** Asks the model to order [candidates]; returns them best first, or `null` when the call failed. */
    fun rerank(
        query: String,
        candidates: List<LlmCandidate>,
        limit: Int,
    ): List<LlmRerankHit>? {
        if (candidates.isEmpty() || limit <= 0) return emptyList()

        val payload =
            buildJsonArray {
                candidates.forEach { candidate ->
                    add(
                        buildJsonObject {
                            put("id", candidate.id)
                            put("t", candidate.title.take(MAX_TITLE_INPUT))
                        },
                    )
                }
            }.toString()

        // One line per hit, Chinese reasons: ~25 tokens each, plus the JSON scaffolding. The model tends to
        // write more than asked, so the budget is generous - a truncated answer loses the whole ranking.
        val maxTokens = (limit * 80).coerceIn(MIN_ANSWER_TOKENS, MAX_ANSWER_TOKENS)

        val answer =
            LlmClient.complete(
                SYSTEM_PROMPT,
                "搜索需求：$query\n\n候选：$payload",
                maxTokens = maxTokens,
            ) ?: return null

        val parsed = parseAnswer(answer, candidates, limit)
        if (parsed == null) {
            logger.warn { "could not use the LLM rerank answer for '$query': ${answer.take(300)}" }
        }

        return parsed
    }

    /**
     * Validates one answer. Returns `null` when the JSON itself is unusable, an empty list when the model
     * decided that nothing matches (a valid, intentionally empty ranking).
     */
    fun parseAnswer(
        answer: String,
        candidates: List<LlmCandidate>,
        limit: Int,
    ): List<LlmRerankHit>? {
        val body = extractJson(answer) ?: return null

        fun decode(text: String): List<LlmRerankHit>? =
            runCatching {
                if (text.trimStart().startsWith("[")) {
                    val serializer = ListSerializer(LlmRerankHit.serializer())
                    json.decodeFromString(serializer, text)
                } else {
                    json.decodeFromString(LlmRerankAnswer.serializer(), text).hits
                }
            }.getOrNull()

        val rawHits: List<LlmRerankHit> =
            decode(body)
                ?: repairTruncated(body)?.let(::decode)
                ?: return null

        val allowed = candidates.mapTo(HashSet()) { it.id }
        val used = HashSet<Int>()

        return rawHits
            .asSequence()
            .filter { it.id in allowed && used.add(it.id) }
            .map { hit ->
                hit.copy(
                    score = hit.score.coerceIn(0, 100),
                    reason = hit.reason.replace(Regex("\\s+"), " ").trim().take(MAX_REASON_LENGTH),
                )
            }.take(limit)
            .toList()
    }

    /** Models sometimes wrap JSON in a fenced block or add a sentence around it. */
    private fun extractJson(answer: String): String? {
        val objectStart = answer.indexOf('{')
        val objectEnd = answer.lastIndexOf('}')
        val arrayStart = answer.indexOf('[')
        val arrayEnd = answer.lastIndexOf(']')

        val asObject =
            if (objectStart >= 0 && objectEnd > objectStart) answer.substring(objectStart, objectEnd + 1) else null
        val asArray =
            if (arrayStart >= 0 && arrayEnd > arrayStart) answer.substring(arrayStart, arrayEnd + 1) else null

        // {"hits":[...]} starts with '{', a bare [...] ranking starts with '['. Prefer whichever appears first.
        return when {
            asObject == null -> asArray
            asArray == null -> asObject
            arrayStart in 0 until objectStart -> asArray
            else -> asObject
        }
    }

    /**
     * Cut off answers keep everything up to the last complete object and close the array, so a token limit
     * hit costs the tail of the ranking instead of all of it.
     */
    private fun repairTruncated(body: String): String? {
        val arrayStart = body.indexOf('[')
        if (arrayStart < 0) return null

        val lastObjectEnd = body.lastIndexOf('}')
        if (lastObjectEnd <= arrayStart) return null

        val kept = body.substring(0, lastObjectEnd + 1).trimEnd().removeSuffix(",")
        return if (kept.trimStart().startsWith("[")) "$kept]" else "$kept]}"
    }
}
