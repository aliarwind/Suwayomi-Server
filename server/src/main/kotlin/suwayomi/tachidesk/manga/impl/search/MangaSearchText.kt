package suwayomi.tachidesk.manga.impl.search

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.ibm.icu.text.Transliterator
import java.text.Normalizer
import java.util.Locale

/**
 * Normalization for the library search index ([suwayomi.tachidesk.manga.model.table.MangaTable.searchText]).
 *
 * The library is dominated by Chinese translations of Japanese works, so the same entity shows up as
 * `瑪修` / `玛修` / `マシュ`. Folding traditionals to simplified plus katakana to hiragana is what makes a
 * simplified Chinese query find a traditional title (measured on the live library: `雙馬尾` 3068 hits,
 * `双马尾` 31 hits before folding).
 *
 * Bump [VERSION] whenever the rules change; the background reindex picks the bump up and rebuilds.
 */
object MangaSearchText {
    /** Version of the normalization rules. Stored per row in `MangaTable.searchTextVer`. */
    const val VERSION = 4

    /** Upper bound for tokens taken out of a single user query. */
    const val MAX_QUERY_TOKENS = 8

    private val TRANSFORMS = listOf("Traditional-Simplified", "Hant-Hans")

    private val t2s: ThreadLocal<Transliterator?> =
        ThreadLocal.withInitial {
            TRANSFORMS.firstNotNullOfOrNull { runCatching { Transliterator.getInstance(it) }.getOrNull() }
        }

    /**
     * Multi character filler phrases that carry no search signal. Removed before tokenizing a query.
     * Only simplified forms are listed: [normalize] has already folded traditionals by then.
     */
    private val STOP_PHRASES =
        listOf(
            "本子",
            "漫画",
            "作者",
            "画师",
            "社团",
            "系列",
            "作品",
            "名字",
            "名称",
            "关于",
            "有关",
            "那个",
            "这个",
            "那种",
            "一种",
            "有没有",
            "求推荐",
            "帮我",
            "一下",
            "之前",
            "最近",
            "好看的",
        )

    /** Single character particles used both as query stop words and as CJK word separators. */
    private val STOP_CHARS =
        setOf(
            '的', '了', '是', '在', '和', '与', '及', '或', '把', '被', '给', '就', '都', '又', '还', '也',
            '很', '太', '吗', '呢', '吧', '啊', '呀', '哦', '么', '些', '个', '本', '求', '找', '想', '看',
            '做', '要', '一', '部', '这', '那', '啥', '我', '你', '他', '她', '它',
        )

    /**
     * Folds text into the canonical form used by both the index and the query side:
     * NFKC (full width characters), traditional to simplified, lowercase, katakana to hiragana.
     */
    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val nfkc = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        val simplified = t2s.get()?.transliterate(nfkc) ?: nfkc
        return foldKana(simplified.lowercase(Locale.ROOT))
    }

    /**
     * Builds the indexed text for a manga: normalized title, author, artist and genre tokens joined by
     * single spaces.
     *
     * The title goes through [MangaTitleCleaner] first, so release metadata (`[汉化]`, `[DL版]`,
     * `[中国翻訳]`, `(C97)`) is not indexed: those words appear on most of the library and would match
     * thousands of books for a query that means one thing. Circle and artist names found in the title are
     * kept (see [MangaTitleCleaner.Cleaned.names]), so dropping the metadata does not lose names.
     *
     * [structured] is the model's view of the same title ([StructuredTitle.Fields]). When present it
     * replaces the rule based title with `cleanTitle` and adds the fields the rules cannot see: the series
     * (`ブルーアーカイブ` for a book whose title only has the kana), the characters, the circle and the
     * model's spelling of the artist. Only the model's *content* flags are added — `汉化`/`DL版` stay out
     * of the index for the same reason the cleaner drops them.
     */
    fun index(
        title: String?,
        author: String?,
        artist: String?,
        genre: String?,
        structured: StructuredTitle.Fields? = null,
    ): String {
        val cleaned = MangaTitleCleaner.clean(title)

        // Sources that derive author/artist from the title often store the scanlation group there
        // ("中国翻訳", "全彩乱涂机上色"); indexing those would undo the cleaning above.
        val authorText = author?.takeUnless(MangaTitleCleaner::isReleaseMetadata)
        val artistText = artist?.takeUnless(MangaTitleCleaner::isReleaseMetadata)

        // the model can leave cleanTitle empty (a title with no work name at all): keep the raw title so
        // the book does not disappear from the search
        val titleText =
            structured?.cleanTitle?.takeIf { it.isNotBlank() }
                ?: cleaned.title.ifBlank { title.orEmpty() }

        return listOfNotNull(
            titleText,
            cleaned.names,
            structured?.series,
            structured?.characters?.joinToString(" "),
            structured?.circle,
            structured?.artist,
            structured?.aliases?.joinToString(" "),
            structured?.contentFlags?.joinToString(" "),
            cleaned.flags,
            authorText,
            artistText,
            genre,
        ).asSequence()
            .filter { it.isNotBlank() }
            .map(::normalize)
            .flatMap { splitScriptRuns(it).asSequence() }
            .distinct()
            .joinToString(" ")
    }

    /**
     * Turns a free text query into tokens. Latin and CJK runs are separated, common Chinese particles
     * split CJK runs ("玛修的本" -> "玛修"), filler phrases are dropped.
     *
     * A query made only of stop words yields no tokens; callers treat that as "no search" (the
     * unfiltered listing) rather than as an error.
     */
    fun queryTokens(query: String?): List<String> {
        if (query.isNullOrBlank()) return emptyList()

        val normalized =
            STOP_PHRASES.fold(normalize(query)) { acc, phrase -> acc.replace(phrase, " ") }

        return splitScriptRuns(normalized)
            .flatMap { if (isCjkToken(it)) splitOnStopChars(it) else listOf(it) }
            .filter { it.isNotEmpty() }
            .filterNot { it.length == 1 && it[0] in STOP_CHARS }
            .distinct()
            .take(MAX_QUERY_TOKENS)
    }

    private fun foldKana(text: String): String {
        val out = StringBuilder(text.length)
        for (ch in text) {
            out.append(
                when (ch) {
                    // katakana -> hiragana, ヴ (U+30F4) -> ゔ (U+3094) included
                    in 'ァ'..'ヶ' -> ch - 0x60
                    'ヽ' -> 'ゝ'
                    'ヾ' -> 'ゞ'
                    else -> ch
                },
            )
        }
        return out.toString()
    }

    /**
     * Splits on everything that is not a letter or digit and on boundaries between latin and CJK
     * (so "fgo玛修" becomes "fgo" + "玛修"). Kana and CJK stay together because Japanese words mix both.
     */
    private fun splitScriptRuns(text: String): List<String> {
        val runs = mutableListOf<String>()
        val current = StringBuilder()
        var currentIsLatin = false

        fun flush() {
            if (current.isNotEmpty()) {
                runs += current.toString()
                current.setLength(0)
            }
        }

        for (ch in text) {
            if (!ch.isLetterOrDigit()) {
                flush()
                continue
            }
            val isLatin = isLatin(ch)
            if (current.isNotEmpty() && isLatin != currentIsLatin) flush()
            currentIsLatin = isLatin
            current.append(ch)
        }
        flush()
        return runs
    }

    private fun splitOnStopChars(token: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        for (ch in token) {
            if (ch in STOP_CHARS) {
                if (current.isNotEmpty()) {
                    parts += current.toString()
                    current.setLength(0)
                }
            } else {
                current.append(ch)
            }
        }
        if (current.isNotEmpty()) parts += current.toString()
        return parts
    }

    private fun isCjkToken(token: String): Boolean = token.isNotEmpty() && !isLatin(token[0])

    private fun isLatin(ch: Char): Boolean {
        val code = ch.code
        return code < 0x0250 || code in 0x1E00..0x1EFF
    }
}
