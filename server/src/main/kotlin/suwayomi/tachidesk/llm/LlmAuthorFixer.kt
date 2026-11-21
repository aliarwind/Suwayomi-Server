package suwayomi.tachidesk.llm

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Repairs author values that the source's title regex got wrong.
 *
 * A source that derives the author from the title can only see the title: it leaves "某绅士" behind when
 * no bracket carries a name and it lets scanner groups or "AI Generated" labels through. The model gets the
 * same title plus the stored tags and has to answer in the site's own convention (`circle (person)`), and
 * an answer is only accepted when the name really appears in the title or the tags.
 */
object LlmAuthorFixer {
    private val logger = KotlinLogging.logger {}
    private val json = Json { ignoreUnknownKeys = true }

    /** Enough rows to keep the prompt short; one answer covers ten to twenty books. */
    const val BATCH_SIZE = 20

    private const val MAX_ANSWER_TOKENS = 1500
    private const val MAX_NAME_LENGTH = 80

    data class Row(
        val id: Int,
        val title: String,
        val tags: String,
    )

    data class Proposal(
        val id: Int,
        val author: String,
        val artist: String,
    )

    @Serializable
    private data class Answer(
        val rows: List<AnswerRow> = emptyList(),
    )

    @Serializable
    private data class AnswerRow(
        val id: Int = 0,
        val author: String? = null,
    )

    private val SYSTEM_PROMPT =
        """
        你在修复一个漫画库里被正则抓坏的作者字段。每行是 "id<TAB>标题<TAB>标签"。
        标题里的 [社团 (作者)] 是社团加作者，[作者] 是只有作者；"汉化组"、"翻译组"、"DL版"、"无修正"、
        "AI Generated"、"Patreon"、"Fanbox" 这类都不是作者。
        请给出每行的作者：
        1. 名字必须原样出现在标题或标签里，不要翻译、不要改写、不要补全、不要臆造。
        2. 标题里是 "社团 (作者)" 这种写法时，作者照抄成 "社团 (作者)"，社团要保留。
        3. 标题是 "名字 - 作品名" 时，名字就是作者；"某人的作品" 这种所有格里，某人也通常是作者。
        4. 判断不了就把 author 留空。
        只输出 JSON：{"rows":[{"id":123,"author":"社团 (作者)"}]}
        """.trimIndent()

    /** Proposes a new author for every row the model answered and the title confirms. */
    fun propose(rows: List<Row>): List<Proposal> = rows.chunked(BATCH_SIZE).flatMap(::proposeBatch)

    private fun proposeBatch(batch: List<Row>): List<Proposal> {
        val input = batch.joinToString("\n") { "${it.id}\t${it.title}\t${it.tags}" }
        val answer = LlmClient.complete(SYSTEM_PROMPT, input, maxTokens = MAX_ANSWER_TOKENS) ?: return emptyList()

        val parsed =
            runCatching { json.decodeFromString(Answer.serializer(), extractJsonObject(answer)) }
                .onFailure { logger.warn(it) { "could not decode the author answer: ${answer.take(200)}" } }
                .getOrNull() ?: return emptyList()

        val rowsById = batch.associateBy { it.id }
        return parsed.rows.mapNotNull { row ->
            val original = rowsById[row.id] ?: return@mapNotNull null
            val author =
                row.author
                    ?.trim()
                    ?.replace(WHITESPACE_REGEX, " ")
                    ?.takeIf { it.isNotEmpty() && it.length <= MAX_NAME_LENGTH }
                    ?: return@mapNotNull null
            if (!appearsIn(author, original)) return@mapNotNull null
            if (isMetadata(author)) return@mapNotNull null

            Proposal(row.id, author, artistOf(author))
        }
    }

    /** `circle (person)` -> person; a bare name stays. */
    fun artistOf(author: String): String =
        CIRCLE_AND_PERSON_REGEX.matchEntire(author)?.groupValues?.get(2)?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: author

    private fun appearsIn(
        author: String,
        row: Row,
    ): Boolean {
        val haystack = (row.title + " " + row.tags).lowercase()
        return haystack.contains(author.lowercase()) || haystack.contains(artistOf(author).lowercase())
    }

    /**
     * The model still proposes scanlation groups or release labels when the title only contains those
     * ("武田弘光的DQ11（流木個人漢化）"), so the same words the source filters are rejected here too.
     */
    private fun isMetadata(author: String): Boolean =
        METADATA_KEYWORDS.any { author.contains(it, ignoreCase = true) } ||
            COUNT_REGEX.matches(author)

    /** Models sometimes wrap the JSON in a fenced block or add a sentence around it. */
    private fun extractJsonObject(answer: String): String {
        val start = answer.indexOf('{')
        val end = answer.lastIndexOf('}')
        if (start < 0 || end <= start) return answer

        return answer.substring(start, end + 1)
    }

    private val WHITESPACE_REGEX = Regex("""\s+""")
    private val CIRCLE_AND_PERSON_REGEX = Regex("""^(.*?)[（(]([^（）()]{1,40})[)）]$""")
    private val COUNT_REGEX = Regex("""^\d+\s*[pP页頁]?$""")

    /** Release metadata, scanlation groups and platforms that must never be stored as an author. */
    private val METADATA_KEYWORDS = listOf(
        "汉化", "漢化", "翻訳", "翻译", "翻譯", "机翻", "機翻", "重嵌", "嵌字", "修图", "修圖", "自翻",
        "上色", "全彩", "カラー", "中文", "中譯", "中译", "掃圖", "扫图", "魔改", "無修正", "无修正",
        "DL版", "電子版", "电子版", "同人誌", "AI Generated", "AI生成", "Patreon", "Fanbox", "Fantia",
        "BOOTH", "DLsite", "Pixiv", "Twitter", "R-18", "雜誌", "杂志", "短編集", "総集編", "合集",
    )
}
