package suwayomi.tachidesk.llm

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json

/**
 * Turns a free text query into an [LlmQueryPlan] with one chat completion call.
 *
 * The model is asked for JSON only and the answer is validated: a plan that does not parse, or that is
 * empty, is treated as "the model has nothing to add" and the caller keeps the rule based search.
 */
object LlmQueryParser {
    private val logger = KotlinLogging.logger {}
    private val json = Json { ignoreUnknownKeys = true }

    private val SYSTEM_PROMPT =
        """
        你是漫画库的搜索助手。把用户的一句话搜索需求解析成 JSON，只输出 JSON，不要解释。

        字段：
        - titleTerms: 作品名、角色名的写法（最多 5 个）。
        - tags: 类型标签（最多 6 个），用这类漫画库里常见的说法，例如 NTR、巨乳、人妻、调教、催眠、无修正。
        - tagMode: "AND" 表示这些标签要同时满足；"OR" 表示任意一个即可。用户说"或者/之一/随便哪种"时用 OR，否则用 AND。
        - author: 用户明确提到的作者或社团名，没有就 null。

        规则：
        1. 只抽取用户真正表达的意思；**不要添加用户没提到的标签或作品名**。"傲慢的上位女" 里的"上位女"是一个意思，不要额外补"巨乳""女上司"这类用户没说的属性。
        2. 保留用户自己的说法（上位女、御姐、肉铠…），不要替换成你以为更标准的词；除非用户明显用的是同义俗称（如"寝取"可为"ntr"）。
        3. **一个实体只给一个写法**：同一个作品、系列或角色只出现一次，不要同时给中文译名、日文原名、英文名或缩写
           （"舰C" 只给一个写法，不要再给"舰队collection""KanColle"）。服务端有同义词表会自动扩展别名，
           多写会被当成"这些词都必须出现"，结果反而互相抵消。
        4. 用户提到多个**不同**实体时（作品 + 角色、两部作品），每个实体各给一个写法。
        5. 一个标签只给一个词，不要把一个意思拆成多个标签。
        6. 判断不了就给空数组或 null。
        """.trimIndent()

    /** Returns a sanitized plan, or `null` when the model failed or produced nothing usable. */
    fun parse(query: String): LlmQueryPlan? {
        val answer = LlmClient.complete(SYSTEM_PROMPT, "搜索需求：$query") ?: return null

        val plan =
            runCatching {
                json.decodeFromString(LlmQueryPlan.serializer(), extractJsonObject(answer))
            }.onFailure { logger.warn(it) { "could not decode the LLM answer: ${answer.take(200)}" } }
                .getOrNull() ?: return null

        val sanitized = plan.sanitized()
        if (sanitized.isEmpty()) {
            logger.debug { "LLM returned an empty plan for: $query" }
            return null
        }

        return sanitized
    }

    /** Models sometimes wrap JSON in a fenced block or add a sentence around it. */
    private fun extractJsonObject(answer: String): String {
        val start = answer.indexOf('{')
        val end = answer.lastIndexOf('}')
        if (start < 0 || end <= start) return answer

        return answer.substring(start, end + 1)
    }
}
