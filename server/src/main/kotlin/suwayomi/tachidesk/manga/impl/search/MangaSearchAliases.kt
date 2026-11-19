package suwayomi.tachidesk.manga.impl.search

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

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
 * All entries are stored in the normalized form ([MangaSearchText.normalize]: simplified, lowercase,
 * katakana folded to hiragana), because lookups happen on normalized tokens.
 */
object MangaSearchAliases {
    /**
     * One concept per inner list. Keep the groups small and unambiguous: an over-eager group turns a
     * precise query into "half the library". Frequencies in the comments are from the 47k book library.
     */
    private val GROUPS: List<List<String>> =
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

    /**
     * Normalized token to the other members of its group. A token may belong to more than one group in
     * principle, so the value is the union; the token itself is never included.
     */
    private val byToken: Map<String, List<String>> = buildMap {
        GROUPS.forEach { group ->
            val normalized = group.map { MangaSearchText.normalize(it) }.filter { it.isNotEmpty() }.distinct()
            normalized.forEach { token ->
                val others = normalized.filter { it != token }
                put(token, (get(token).orEmpty() + others).distinct())
            }
        }
    }

    /** Other members of the concept the [token] belongs to. Empty when the token is not an alias. */
    fun aliasesOf(token: String): List<String> = byToken[token].orEmpty()

    /** True when [token] is part of any synonym group. Used by the tests and the query planner. */
    fun isAlias(token: String): Boolean = byToken.containsKey(token)
}
