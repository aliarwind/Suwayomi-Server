package suwayomi.tachidesk.manga.impl.search

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Synonym groups for library search.
 *
 * The library vocabulary is dominated by source tags (`巨乳`, `無修正`, `NTR`, ...), while people search with
 * their own words (`大奶`, `无码`, `寝取`). Membership is checked against the indexed text, so a group only
 * ever adds terms that actually exist in the library.
 *
 * Every group is one concept: the members are OR-ed together, the groups themselves are AND-ed with the
 * other query tokens ("傲慢的上位女" -> (傲慢) AND (上位女 OR 御姐 ...)).
 *
 * The built-in groups below are always active. A `search-aliases.json` file in the data directory adds
 * (and extends) groups without a rebuild - the file is read once at startup, so updating aliases on a
 * deployed instance is "replace the file and restart", not "ship a new jar". Both shapes are accepted:
 *
 * ```json
 * { "version": 1, "groups": [ { "terms": ["上位女", "御姐"], "source": "llm" } ] }
 * { "groups": [ ["上位女", "御姐"] ] }
 * ```
 *
 * All entries are stored in the normalized form ([MangaSearchText.normalize]: simplified, lowercase,
 * katakana folded to hiragana), because lookups happen on normalized tokens.
 */
object MangaSearchAliases {
    private val logger = KotlinLogging.logger {}
    private val json = Json { ignoreUnknownKeys = true }

    /** File name inside the server data directory. */
    const val FILE_NAME = "search-aliases.json"

    /** Longer "terms" are titles or garbage, not search words. */
    private const val MAX_TERM_LENGTH = 40

    /** A concept with more members than this stops being a concept ("丝袜" groups are the usual offender). */
    private const val MAX_GROUP_SIZE = 12

    /**
     * One concept per inner list. Keep the groups small and unambiguous: an over-eager group turns a
     * precise query into "half the library". Frequencies in the comments are from the 47k book library.
     */
    private val DEFAULT_GROUPS: List<List<String>> =
        listOf(
            // 上位女 barely appears in titles, 御姐 is the tag people mean
            listOf("上位女", "御姐", "强势女", "女上司"),
            listOf("大奶", "大胸", "豪乳", "巨乳", "爆乳", "超乳"),
            listOf("无码", "无修正", "无修", "步兵"),
            listOf("有码", "有修正", "骑兵"),
            // colour stays in the group: 长筒袜/连裤袜 are colour neutral, joining them would make
            // "白丝" match every stocking in the library (measured: 12 -> 5,940)
            listOf("白丝", "白丝袜", "白裤袜"),
            listOf("黑丝", "黑丝袜", "黑裤袜"),
            listOf("长筒袜", "长筒丝袜"),
            listOf("连裤袜", "连裤丝袜"),
            listOf("丝袜", "长筒丝袜", "连裤丝袜"),
            listOf("菊花", "肛交", "爆肛", "肛"),
            listOf("撸", "手冲", "打手枪", "自慰", "撸管"),
            listOf("妈妈", "母亲", "母上", "妈"),
            listOf("阿黑颜", "阿嘿颜", "啊嘿颜"),
            listOf("寝取", "ntr", "牛头人", "出轨"),
            listOf("萝莉", "loli", "萝莉塔"),
            listOf("全彩", "fullcolor", "彩漫"),
            listOf("熟女", "人妻", "少妇"),
            listOf("触手", "触手play", "触手奸"),
            listOf("百合", "女同", "百合向"),
            listOf("巨根", "大屌", "大雕", "肉棒"),
            listOf("口交", "吹箫", "咬"),
            listOf("群交", "乱交", "3p", "多p"),
            listOf("强暴", "强奸", "强干"),
            listOf("婴儿肥", "微胖", "胖"),
            listOf("胶衣", "紧身衣", "皮衣"),
        )

    @Volatile
    private var groups: List<List<String>> = defaultGroups()

    /** Normalized token to the other members of its group (union over all groups it appears in). */
    @Volatile
    private var byToken: Map<String, List<String>> = index(groups)

    private fun defaultGroups(): List<List<String>> = DEFAULT_GROUPS.mapNotNull(::normalizeGroup)

    /**
     * Loads the extra groups from [file]. Missing file, unreadable JSON or a file without a single usable
     * group leave the built-in groups untouched - a broken alias file must never empty the table.
     */
    fun load(file: File) {
        if (!file.isFile) {
            logger.info { "No alias file at ${file.path}, using the ${groups.size} built-in groups" }
            return
        }

        val extra =
            runCatching { parseGroups(file.readText()) }
                .onFailure { logger.warn(it) { "Could not read the alias file ${file.path}" } }
                .getOrNull()

        if (extra.isNullOrEmpty()) {
            logger.warn { "Alias file ${file.path} contains no usable group, keeping the built-in groups" }
            return
        }

        // Always rebuild from the defaults, so calling load twice (e.g. after a database switch) does not
        // duplicate groups.
        val merged = defaultGroups() + extra
        groups = merged
        byToken = index(merged)
        logger.info {
            "Loaded ${extra.size} alias groups from ${file.path} (${byToken.size} terms, " +
                "${merged.size} groups in total)"
        }
    }

    /** Other members of the concept the [token] belongs to. Empty when the token is not an alias. */
    fun aliasesOf(token: String): List<String> = byToken[token].orEmpty()

    /** True when [token] is part of any synonym group. Used by the tests and the query planner. */
    fun isAlias(token: String): Boolean = byToken.containsKey(token)

    /** Number of terms that can be expanded; used by the tuning query and the tests. */
    fun termCount(): Int = byToken.size

    /**
     * Parses the file format. Accepts `{"groups":[{"terms":[...]}]}` and `{"groups":[["a","b"]]}`; groups
     * that end up with fewer than two usable terms are dropped.
     */
    internal fun parseGroups(text: String): List<List<String>> {
        val root = json.parseToJsonElement(text).jsonObject
        val groups = root["groups"]?.jsonArray ?: return emptyList()

        return groups.mapNotNull { element ->
            val terms =
                when (element) {
                    is JsonArray -> element.mapNotNull(::asText)
                    is JsonObject -> element["terms"]?.jsonArray?.mapNotNull(::asText)
                    else -> null
                }
            terms?.let(::normalizeGroup)
        }
    }

    /** Text of a JSON string; numbers, booleans, null, objects and arrays are ignored instead of failing. */
    private fun asText(element: kotlinx.serialization.json.JsonElement): String? {
        val primitive = runCatching { element.jsonPrimitive }.getOrNull() ?: return null
        return if (primitive.isString) primitive.contentOrNull?.takeIf { it.isNotBlank() } else null
    }

    /** Normalized, deduplicated, capped; `null` when the group cannot express a concept. */
    private fun normalizeGroup(terms: List<String>): List<String>? {
        val normalized =
            terms
                .map { MangaSearchText.normalize(it).trim() }
                .filter { it.isNotEmpty() && it.length <= MAX_TERM_LENGTH }
                .distinct()

        return normalized.takeIf { it.size >= 2 }?.take(MAX_GROUP_SIZE)
    }

    private fun index(groups: List<List<String>>): Map<String, List<String>> =
        buildMap {
            groups.forEach { group ->
                group.forEach { token ->
                    put(token, (get(token).orEmpty() + group.filter { it != token }).distinct())
                }
            }
        }
}
