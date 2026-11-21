package suwayomi.tachidesk.llm

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import suwayomi.tachidesk.manga.impl.search.MangaSearchText
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Turns a title into structured fields with the model.
 *
 * [suwayomi.tachidesk.manga.impl.search.MangaTitleCleaner] only knows the bracket conventions of the
 * sources, so it can drop `[汉化]` but not `... 机翻 汉化`, and it cannot tell a series from a character.
 * This asks the model for `cleanTitle / series / characters / circle / artist / lang / aliases / flags`
 * and **validates every answer against the source text** before it is worth storing:
 *
 * - `cleanTitle` has to be a subsequence of the title (only deletions are allowed, no rewrites/translations),
 * - names have to appear in the title, the tags or the author, and must not be release metadata,
 * - `lang` and `flags` have to be in their enums.
 *
 * Prototype on 500 books: ~1% of the name proposals are hallucinated and get rejected here; the
 * measured cost is ~100 input + ~65 output tokens per book, see `notes/llm-search-design.md` §24.
 */
object LlmTitleStructurer {
    /** Version of the prompt plus validation rules; stored per row in [MangaLlmIndexTable.version]. */
    const val VERSION = 1

    /** Books per model call: keeps the prompt small while amortizing the system prompt. */
    const val BATCH_SIZE = 20

    /**
     * Batches sent at the same time. One call takes ~5.4 s and the whole library is ~2,400 calls, so a
     * serial backfill would take 3.6 h; four in flight brings it under an hour (measured 1.8 s per batch at
     * 4, no 429s; see `notes/llm-search-design.md` §24).
     */
    const val PARALLELISM = 4

    private const val MAX_ANSWER_TOKENS = 2600
    private const val MAX_NAME_LENGTH = 40
    private const val MIN_CLEAN_TITLE_LENGTH = 2

    private val json = Json { ignoreUnknownKeys = true }

    /** One book as it goes to the model. [tags] is the stored genre list, joined with commas. */
    data class Row(
        val id: Int,
        val title: String,
        val tags: String,
        val author: String?,
    )

    /**
     * Validated fields of one book. [rejects] is not persisted: it exists for the dry run preview, so a
     * reviewer can see which fields the model proposed and the source contradicted.
     */
    data class Structure(
        val id: Int,
        val cleanTitle: String?,
        val series: String?,
        val characters: List<String>,
        val circle: String?,
        val artist: String?,
        val lang: String,
        val aliases: List<String>,
        val flags: List<String>,
        val rejects: List<String>,
    )

    @Serializable
    private data class Answer(
        val rows: List<AnswerRow> = emptyList(),
    )

    @Serializable
    private data class AnswerRow(
        val id: Int = 0,
        val cleanTitle: String? = null,
        val series: String? = null,
        val characters: List<String> = emptyList(),
        val circle: String? = null,
        val artist: String? = null,
        val lang: String? = null,
        val aliases: List<String> = emptyList(),
        val flags: List<String> = emptyList(),
    )

    private val SYSTEM_PROMPT =
        """
        你在给一个漫画库做标题结构化。每行输入是 "id<TAB>标题<TAB>标签<TAB>现有作者"，标签用逗号分隔，现有作者可能为空或是错的。
        对每一行输出一个对象，字段：
        - cleanTitle: 作品名。去掉社团名、作者名、汉化组、版本/属性标记、场次号、页数、章节范围，以及表示出处/系列的括号（如 （Fate／Grand Order）、（COMIC ペンギンクラブ 2020年2月号））之后剩下的部分。**只能删字，不能改写、不能翻译、不能补字、不能调整顺序**；删完不足两个字就把原标题原样放回来。
        - series: 标题里写明的作品/系列名，例如 （Fate／Grand Order）、（ブルーアーカイブ） 里的那一段；没有就空字符串。可以来自标签。
        - characters: 标题里出现的角色名，数组；没有就空数组。可以来自标签。
        - circle: 同人社团/画师团体名；只在标题里能看到时给，否则空字符串。
        - artist: 作者/画师人名；只在标题里能看到时给，否则空字符串。
        - lang: 标题本身用什么语言写的（不是汉化后的语言）：zh / ja / en / other / unknown 之一。有假名就是 ja，纯汉字是 zh，纯拉丁字母是 en，"中国翻訳"、"汉化" 这些标记不影响 lang。
        - flags: 版本/属性标记，只能从这个列表里选：汉化、中国翻訳、無修正、無碼、有碼、DL版、AI生成、全彩、上色、中文、英文、日文、电子版、同人誌。没有就空数组。
        - aliases: 这个 series 或 characters 在其它语言里的常见写法（中文译名、日文原名、英文名都算），最多 3 个；拿不准就给空数组。

        规则：
        1. series、characters、circle、artist 必须**原样出现在标题或标签里**，不要翻译、不要改写、不要臆造、不要补全。找不到就留空。
        2. 标题里 "[社团 (作者)]" 拆成 circle="社团"、artist="作者"；只有 "[作者]" 时是 artist，不是 circle。
        3. 下面这些都不是 circle、也不是 artist：汉化组、翻译组、上色组、搬运号、AI Generated、Patreon、Fanbox、DL版、无修正、中国翻訳、场次（C106、COMIC1☆17、例大祭）、页数（88P）、平台名。
        4. cleanTitle 里不要留下第 3 条那些噪声段，也不要留下 circle/artist 的方括号段，也不要留下出处/系列的括号。
        5. cleanTitle 里也不要留下这些**正文里的**元数据：汉化、汉化组、翻譯、全集、合集、新刊、第1-4话、1-8話、P1-2、【111p】、C101、MJK-26-T3143、"作者 - " 这个前缀（作者已经在 artist 里）。
        6. "某绅士" 不是人名；现有作者只是参考，不要照抄它。
        7. 只输出 JSON：{"rows":[{"id":1,"cleanTitle":"...","series":"","characters":[],"circle":"","artist":"","lang":"ja","flags":[],"aliases":[]}]}
        行数必须和输入一样，id 原样带回。
        """.trimIndent()

    /** Extract the fields of [rows] in batches of [BATCH_SIZE]; rows of a failed batch are missing. */
    fun structure(rows: List<Row>): List<Structure> {
        val batches = rows.chunked(BATCH_SIZE)
        if (batches.size <= 1) return batches.flatMap(::structureBatch)

        val futures = batches.map { batch -> executor.submit<List<Structure>> { structureBatch(batch) } }
        return futures.flatMap { future -> runCatching { future.get() }.getOrDefault(emptyList()) }
    }

    private val executor =
        Executors.newFixedThreadPool(PARALLELISM) { runnable ->
            Thread(runnable, "llm-title-structurer").apply { isDaemon = true }
        }

    /** Hash of the fields the prompt sees, so an unchanged title is not sent to the model again. */
    fun inputHash(row: Row): String {
        val payload = listOf(MangaSearchText.normalize(row.title), MangaSearchText.normalize(row.tags), MangaSearchText.normalize(row.author)).joinToString("\u0000")
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun structureBatch(batch: List<Row>): List<Structure> {
        val input =
            batch.joinToString("\n") { row ->
                listOf(row.id.toString(), oneLine(row.title), oneLine(row.tags), oneLine(row.author.orEmpty())).joinToString("\t")
            }
        val answer = LlmClient.complete(SYSTEM_PROMPT, input, maxTokens = MAX_ANSWER_TOKENS) ?: return emptyList()
        // an unparsable answer means the batch is lost, but the rows stay pending and are retried later
        return parseAnswer(answer, batch) ?: emptyList()
    }

    /**
     * Validates one answer. Returns null when the JSON itself is unusable; a row the model skipped is
     * returned empty so the caller still marks it processed instead of asking about it forever.
     */
    internal fun parseAnswer(
        answer: String,
        rows: List<Row>,
    ): List<Structure>? {
        val parsed = runCatching { json.decodeFromString(Answer.serializer(), extractJsonObject(answer)) }.getOrNull() ?: return null
        val byId = parsed.rows.associateBy { it.id }
        return rows.map { row -> byId[row.id]?.let { validate(row, it) } ?: empty(row.id, "missing") }
    }

    private fun validate(
        row: Row,
        answer: AnswerRow,
    ): Structure {
        val rejects = mutableListOf<String>()

        val series = name(answer.series, row, "series", rejects)
        val circle = name(answer.circle, row, "circle", rejects)
        val artist = name(answer.artist, row, "artist", rejects)
        val characters =
            answer.characters
                .take(MAX_CHARACTERS)
                .mapNotNull { name(it, row, "characters", rejects) }
                .distinctBy(MangaSearchText::normalize)

        val cleanTitle = cleanTitle(answer.cleanTitle, row, artist, rejects)

        val lang = MangaSearchText.normalize(answer.lang).takeIf { it in LANGS }
        if (lang == null && !answer.lang.isNullOrBlank()) rejects += "lang:${answer.lang}"

        val flags =
            answer.flags
                .mapNotNull { flag ->
                    val canonical = FLAG_NAMES[MangaSearchText.normalize(flag)]
                    if (canonical == null) {
                        rejects += "flags:$flag"
                        null
                    } else {
                        canonical
                    }
                }.distinct()

        val known = listOfNotNull(series, circle, artist).map(MangaSearchText::normalize) + characters.map(MangaSearchText::normalize)
        val sourceText = MangaSearchText.normalize(row.title + " " + row.tags)
        val aliases =
            answer.aliases
                .take(MAX_ALIASES)
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.length <= MAX_NAME_LENGTH }
                .filterNot { MangaSearchText.normalize(it) in known }
                .filter { MangaSearchText.normalize(it) !in sourceText }
                .distinctBy(MangaSearchText::normalize)

        return Structure(
            id = row.id,
            cleanTitle = cleanTitle,
            series = series,
            characters = characters,
            circle = circle,
            artist = artist,
            lang = lang ?: "unknown",
            aliases = aliases,
            flags = flags,
            rejects = rejects,
        )
    }

    /** A name the source really contains: same writing as the source, not release metadata. */
    private fun name(
        raw: String?,
        row: Row,
        field: String,
        rejects: MutableList<String>,
    ): String? {
        val value = raw?.trim()?.replace(WHITESPACE, " ") ?: return null
        if (value.isEmpty()) return null
        if (value.length > MAX_NAME_LENGTH) {
            rejects += "$field:$value"
            return null
        }
        val normalized = MangaSearchText.normalize(value)
        if (isReleaseMetadata(value)) {
            rejects += "$field:$value"
            return null
        }
        val haystack = MangaSearchText.normalize(row.title + " " + row.tags + " " + row.author.orEmpty())
        if (normalized.isEmpty() || !haystack.contains(normalized)) {
            rejects += "$field:$value"
            return null
        }
        return value
    }

    /**
     * `cleanTitle` may only delete characters from the title. Afterwards the deterministic clean-up
     * removes what the model keeps missing (event code prefix, `artist - ` prefix, trailing metadata,
     * page count) — those are rules, not prompt work (500 books prototype, see §24).
     */
    private fun cleanTitle(
        raw: String?,
        row: Row,
        artist: String?,
        rejects: MutableList<String>,
    ): String? {
        val candidate = raw?.trim() ?: return null
        if (candidate.isEmpty()) return null
        if (!isSubsequence(letters(MangaSearchText.normalize(candidate)), letters(MangaSearchText.normalize(row.title)))) {
            rejects += "cleanTitle:$candidate"
            return null
        }
        val tidied = tidy(candidate, artist)
        if (letters(MangaSearchText.normalize(tidied)).length < MIN_CLEAN_TITLE_LENGTH) {
            rejects += "cleanTitle:$candidate"
            return null
        }
        return tidied
    }

    /** Deterministic post-processing of an accepted `cleanTitle`; only deletes, never rewrites. */
    internal fun tidy(
        cleanTitle: String,
        artist: String?,
    ): String {
        var text = cleanTitle.trim()

        if (!artist.isNullOrBlank()) {
            // "Hana Bunny - Samsung Sam" / "Peachuu ｜ Bulma": the name already is the artist field
            val prefix = Regex("^\\s*${Regex.escape(artist.trim())}\\s*[-–—─:|｜]\\s*", RegexOption.IGNORE_CASE)
            text = text.replaceFirst(prefix, "")
        }

        text = EVENT_PREFIX.replace(text, "")
        text = PAGE_MARK.replace(text, " ")

        // "… 6本汉化全集", "… 短篇数条汉化", "观者 合集"
        var previous = ""
        while (previous != text) {
            previous = text
            text = TRAILING_METADATA.replace(text, "")
        }

        return text
            .replace(WHITESPACE, " ")
            .trim()
            .trim(' ', '-', '–', '—', '─', '|', '｜', ':', '：', '/', '_', '~', '～')
            .trim()
    }

    private fun empty(
        id: Int,
        reason: String,
    ): Structure =
        Structure(
            id = id,
            cleanTitle = null,
            series = null,
            characters = emptyList(),
            circle = null,
            artist = null,
            lang = "unknown",
            aliases = emptyList(),
            flags = emptyList(),
            rejects = listOf(reason),
        )

    private fun oneLine(value: String): String = value.replace(TAB_OR_NEWLINE, " ")

    /** Everything that is not a letter or a digit; used for the delete-only check. */
    private fun letters(value: String): String = value.filter { it.isLetterOrDigit() }

    private fun isSubsequence(
        needle: String,
        haystack: String,
    ): Boolean {
        var index = 0
        for (ch in haystack) {
            if (index < needle.length && ch == needle[index]) index++
        }
        return index == needle.length
    }

    /** Same words [LlmAuthorFixer] refuses as author, plus the coloring marker that is also a real name. */
    private fun isReleaseMetadata(value: String): Boolean {
        val text = MangaSearchText.normalize(value)
        if (COUNT_REGEX.matches(text)) return true
        if (COLORIZING_SUFFIX.containsMatchIn(text)) return true
        return METADATA_KEYWORDS.any { text.contains(it) }
    }

    /** Models sometimes wrap the JSON in a fenced block or add a sentence around it. */
    private fun extractJsonObject(answer: String): String {
        val start = answer.indexOf('{')
        val end = answer.lastIndexOf('}')
        if (start < 0 || end <= start) return answer
        return answer.substring(start, end + 1)
    }

    private const val MAX_CHARACTERS = 8
    private const val MAX_ALIASES = 3

    private val LANGS = setOf("zh", "ja", "en", "other", "unknown")

    /** Canonical flags keyed by their normalized form; normalization folds 漢化/無碼/電子版 etc. */
    private val FLAG_NAMES: Map<String, String> =
        listOf(
            "汉化",
            "中国翻訳",
            "無修正",
            "無碼",
            "有碼",
            "DL版",
            "AI生成",
            "全彩",
            "上色",
            "中文",
            "英文",
            "日文",
            "电子版",
            "同人誌",
        ).associateBy(MangaSearchText::normalize)

    private val METADATA_KEYWORDS =
        listOf(
            "汉化", "翻訳", "机翻", "重嵌", "嵌字", "修图", "自翻", "上色", "全彩", "中文", "扫图", "魔改",
            "无修正", "dl版", "电子版", "同人志", "ai generated", "ai生成", "patreon", "fanbox", "fantia",
            "booth", "dlsite", "pixiv", "twitter", "r-18", "杂志", "短篇集", "总集编", "合集",
        )

    private val COUNT_REGEX = Regex("""^\d+\s*[pP页頁]?$""")
    private val COLORIZING_SUFFIX = Regex("""(カラー化|colori[sz]ed)$""")
    private val WHITESPACE = Regex("""\s+""")
    private val TAB_OR_NEWLINE = Regex("""[\t\r\n]+""")

    /** `C97`, `C101`, `COMIC1☆27`, `MJK-26-T3143` glued to the work name. */
    private val EVENT_PREFIX = Regex("""^\s*(?:[Cc]\d{2,3}(?=[^\d]|$)|COMIC\s*1\S*|例大祭\S*|MJK-[\w-]+)\s*""")

    /** `【111p】`, `[88P]`, `(12頁)`. */
    private val PAGE_MARK = Regex("""[【\[（(]\s*\d+\s*[pP頁页]\s*[】\]）)]""")

    /** `... 汉化`, `... 汉化全集`, `... 合集` at the end of the remaining title. */
    private val TRAILING_METADATA = Regex("""(?:\s*[·・]?\s*(?:汉化组|汉化|翻譯|翻译|机翻|機翻|合集|全集|新刊))+\s*$""")
}
