package suwayomi.tachidesk.manga.impl.search

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

/**
 * Rule based cleaning of a manga title for the search index.
 *
 * Titles from the usual sources pack the whole book into one string:
 *
 * ```
 * [聖性屋 (聖シロー)] 聖女と魔女の乱交パーティ (Fate／Grand Order) [黎欧出资汉化] [DL版]
 * ```
 *
 * Only `聖女と魔女の乱交パーティ` is the work. `[社团 (作者)]` is a name, `[汉化]`/`[DL版]`/`[中国翻訳]`
 * are release metadata that ~70% of the library carries, and searching for those words matches the whole
 * library instead of one book. This class splits such a title into [Cleaned]:
 *
 * - `title`: the work name (release metadata and names removed)
 * - `names`: circle / artist / series found in brackets, still searchable
 * - `flags`: content attributes worth keeping as search terms (`无修正`, `无码`, ...)
 *
 * Everything ends up in `MangaTable.searchText` in the end; the split only exists so that metadata words
 * can be dropped while names stay findable, and so that the pieces are available to later features
 * (per column ranking, series/character extraction).
 *
 * The input is expected to be [MangaSearchText.normalize]d already, but [clean] normalizes again so it is
 * safe to call with a raw title.
 */
object MangaTitleCleaner {
    /** Result of cleaning one title. All fields are normalized and trimmed. */
    data class Cleaned(
        val title: String,
        val names: String,
        val flags: String,
    ) {
        /** True when nothing at all could be recovered; callers should fall back to the raw title. */
        val isEmpty: Boolean get() = title.isEmpty() && names.isEmpty()
    }

    /** `[..]` and `【..】`: circle, artist, series, scanlation group or release flags. */
    private val BRACKETED = Regex("""\[[^]]*]|【[^】]*】""")

    /** `(..)`: series, characters, event editions, magazine and volume numbers. */
    private val PAREN = Regex("""\(([^)]*)\)""")

    /**
     * Words that mark a bracket as release metadata rather than a name. A bracket containing one of them is
     * dropped from the index entirely: the words are common enough to match most of the library.
     * Written in the normalized form (traditionals folded, lowercase).
     */
    private val NOISE_MARKERS =
        listOf(
            "汉化",
            "翻译",
            "翻訳",
            "机翻",
            "dl版",
            "上色",
            "彩漫社",
            "嵌字",
            "润色",
            "修图",
            "生肉",
            "整合",
            "中文",
            "中国语",
            "chinese",
            "english",
            "英文",
            "日文",
            "ai生成",
            "ai翻译",
            "ai翻訳",
            "ai generated",
            "ai作画",
        )

    /** Content attributes that are kept as search terms instead of being dropped. */
    private val FLAGS = Regex("""^(?:无修正|无码|有码|修正)$""")

    /**
     * Parenthesised release metadata: comic market editions, original work markers, magazine issues.
     * Written in the normalized form: [MangaSearchText.normalize] folds katakana to hiragana, so
     * `オリジナル` is `おりじなる` and `コミック` is `こみっく` by the time these run.
     */
    private val PAREN_NOISE =
        Regex("""^(?:c\d{2,3}|おりじなる|同人誌|同人志|vol\.?\s*\d+|.*こみっく.*|.*comic.*|.*年\d{1,2}月号.*)$""")

    /** Leftovers between two removed segments: release titles glue them together. */
    private val SEPARATORS = Regex("""[_~～・]+""")

    private val WHITESPACE = Regex("""\s+""")

    fun clean(rawTitle: String?): Cleaned {
        if (rawTitle.isNullOrBlank()) return Cleaned("", "", "")

        val normalized = MangaSearchText.normalize(rawTitle)
        val names = ArrayList<String>()
        val flags = ArrayList<String>()

        val withoutBrackets =
            BRACKETED.replace(normalized) { match ->
                val body = match.value.trim('[', ']', '【', '】').trim()
                when {
                    body.isEmpty() -> " "
                    isNoise(body) -> " "
                    FLAGS.matches(body) -> {
                        flags += body
                        " "
                    }
                    else -> {
                        names += body
                        " "
                    }
                }
            }

        val title =
            PAREN
                .replace(withoutBrackets) { match ->
                    val body = match.groupValues[1].trim()
                    if (body.isEmpty() || isNoise(body) || PAREN_NOISE.matches(body)) " " else match.value
                }.replace(SEPARATORS, " ")
                .replace(WHITESPACE, " ")
                .trim()
                .trim(' ', '-', '~', '～', '_', '.', ',', '、', ':', '/', '｜', '|')

        val cleaned = Cleaned(title, names.joinToString(" ").trim(), flags.joinToString(" ").trim())

        // Never leave a book unsearchable: if every rule ate the title, keep the raw text.
        return if (cleaned.isEmpty) Cleaned(normalized.trim(), "", "") else cleaned
    }

    private fun isNoise(body: String): Boolean = NOISE_MARKERS.any { body.contains(it) }

    /**
     * True when a whole column value (author, artist) is release metadata instead of a name.
     *
     * Sources that fill `author` from the first bracketed part of the title end up with values like
     * `中国翻訳` or `全彩乱涂机上色` on thousands of books, which would reintroduce exactly the words
     * [clean] removed from the title.
     */
    fun isReleaseMetadata(value: String?): Boolean = !value.isNullOrBlank() && isNoise(MangaSearchText.normalize(value))
}
