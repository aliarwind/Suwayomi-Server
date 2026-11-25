package suwayomi.tachidesk.manga.impl.duplicates

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.manga.impl.search.MangaSearchText
import suwayomi.tachidesk.manga.model.dataclass.toGenreList
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.MangaUserTable
import suwayomi.tachidesk.manga.model.table.SourceTable
import suwayomi.tachidesk.manga.model.table.getWithUserData
import java.util.concurrent.ConcurrentHashMap

/**
 * Library wide duplicate detection.
 *
 * The sources glue the work name together with the uploader, the release edition and the series, e.g.
 *
 * ```
 * [いちごクレープ大盛組 (横十輔)] 友ママを呼び捨てにしながらハメたいッ【デリヘル嬢編】 [中国翻訳]
 * ```
 *
 * Dropping the `[...]`/`(...)` groups and the release metadata leaves the work name, which is what the
 * dedupe key is built from: `友ママを呼び捨てにしながらはめたいっ【でりへる嬢編】`. `【...】` is *kept* because
 * the sources use it for part of the work name (`【デリヘル嬢編】`, `【退魔士ゆら6】`); dropping it merged
 * every book of an artist into one group.
 *
 * The entries of a group are scored so the caller can keep the best copy: chapters, downloads, author,
 * description, genres and how much of the title is the work name.
 */
object MangaDuplicates {
    private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

    /** A single book of a duplicate group, with the reason why the score is what it is. */
    data class Entry(
        val mangaId: Int,
        val title: String,
        /** The work name extracted from [title]. */
        val cleanTitle: String,
        val thumbnailUrl: String,
        val author: String?,
        val artist: String?,
        val sourceId: Long,
        val sourceName: String?,
        val chapterCount: Int,
        val downloadedChapterCount: Int,
        val score: Int,
        val reasons: List<String>,
    )

    data class Group(
        val key: String,
        /** The work name shared by the group, used as the group title. */
        val title: String,
        val entries: List<Entry>,
        val recommendedKeepId: Int,
        /** The entries name different authors, so they are probably not the same work. */
        val conflictingAuthors: Boolean,
    )

    /** Groups with fewer entries are not reported. */
    const val DEFAULT_MIN_GROUP_SIZE = 2

    private const val CACHE_TTL_MS = 60_000L

    /** `[..]` holds the uploader, the circle, the artist or a release flag. */
    private val SQUARE_BRACKETS = Regex("""\[[^]]*]""")

    /** `(..)` holds the series, a character, a market edition or a magazine issue. */
    private val PARENS = Regex("""\([^)]*\)""")

    /** `（..）` is only dropped when it is release metadata, it is also used for the work name itself. */
    private val FULL_WIDTH_PARENS = Regex("""（([^）]*)）""")

    /**
     * Release metadata written in the normalized form (traditionals folded, lowercase). Removed wherever it
     * appears, so `作品名 汉化组` and `作品名` end up with the same key.
     */
    private val NOISE_WORDS =
        Regex(
            """(汉化|漢化|汉畫|翻译|翻譯|翻訳|机翻|中文|中国语|中國語|dl版|无修正|無修正|无码|有码|上色|嵌字|润色|修图|生肉|整合|ai生成|ai翻译|ai翻訳|ai generated|ai作画|patreon|fanbox|fantia|dlsite|fanza|chinese|english)""",
        )

    private val SPACES = Regex("""\s+""")

    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")

    /** Titles that are not a work name at all and would group unrelated books. */
    private val IGNORED_TITLES =
        setOf(
            "无题",
            "無題",
            "unknown",
            "manga",
            "comic",
            "漫画",
            "短篇",
            "单篇",
            "同人志",
            "同人誌",
        )

    private data class CacheEntry(
        val builtAt: Long,
        val groups: List<Group>,
    )

    private val cache = ConcurrentHashMap<Int, CacheEntry>()

    /** Invalidate the cached groups, e.g. after the library changed. */
    fun invalidate(userId: Int? = null) {
        if (userId == null) cache.clear() else cache.remove(userId)
    }

    suspend fun groups(
        userId: Int,
        refresh: Boolean = false,
    ): List<Group> {
        val cached = cache[userId]
        val now = System.currentTimeMillis()
        if (!refresh && cached != null && now - cached.builtAt < CACHE_TTL_MS) {
            return cached.groups
        }

        val groups = build(userId)
        cache[userId] = CacheEntry(now, groups)
        return groups
    }

    private fun build(userId: Int): List<Group> {
        val startedAt = System.currentTimeMillis()
        val rows =
            transaction {
                MangaTable
                    .getWithUserData(userId)
                    .select(
                        MangaTable.id,
                        MangaTable.title,
                        MangaTable.author,
                        MangaTable.artist,
                        MangaTable.description,
                        MangaTable.genre,
                        MangaTable.sourceReference,
                    ).where { MangaUserTable.inLibrary eq true }
                    .toList()
            }

        val chapterCounts =
            transaction {
                ChapterTable
                    .select(ChapterTable.manga, ChapterTable.id.count())
                    .groupBy(ChapterTable.manga)
                    .associate { it[ChapterTable.manga].value to it[ChapterTable.id.count()].toInt() }
            }

        val downloadCounts =
            transaction {
                ChapterTable
                    .select(ChapterTable.manga, ChapterTable.id.count())
                    .where { ChapterTable.isDownloaded eq true }
                    .groupBy(ChapterTable.manga)
                    .associate { it[ChapterTable.manga].value to it[ChapterTable.id.count()].toInt() }
            }

        val sourceNames =
            transaction {
                SourceTable
                    .select(SourceTable.id, SourceTable.name)
                    .associate { it[SourceTable.id].value to it[SourceTable.name] }
            }

        // the key only needs to be stable, so the cheap normalization (no ICU transliteration) is used: the
        // full one costs ~0.2 ms per title and this runs over the whole library
        val normalizeStartedAt = System.currentTimeMillis()
        val rawByKey = HashMap<String, MutableList<Raw>>()

        rows.forEach { row ->
            val id = row[MangaTable.id].value
            val rawTitle = row[MangaTable.title]
            val cleanTitle = workNameOf(MangaSearchText.normalizeFast(rawTitle))
            val key = NON_WORD.replace(cleanTitle, "")

            if (!isUsableKey(cleanTitle, key)) {
                return@forEach
            }

            rawByKey.getOrPut(key) { ArrayList() } +=
                Raw(
                    mangaId = id,
                    title = rawTitle,
                    cleanTitle = cleanTitle,
                    author = row[MangaTable.author],
                    normalizedAuthor = MangaSearchText.normalizeFast(row[MangaTable.author]),
                    artist = row[MangaTable.artist],
                    sourceId = row[MangaTable.sourceReference],
                    sourceName = sourceNames[row[MangaTable.sourceReference]],
                    chapterCount = chapterCounts[id] ?: 0,
                    downloadedChapterCount = downloadCounts[id] ?: 0,
                    descriptionLength = row[MangaTable.description]?.length ?: 0,
                    genreCount = row[MangaTable.genre].toGenreList().size,
                )
        }
        logger.info { "duplicates: normalized the titles in ${System.currentTimeMillis() - normalizeStartedAt} ms" }

        val groups =
            rawByKey
                .filter { it.value.size >= DEFAULT_MIN_GROUP_SIZE }
                .map { (key, rawEntries) -> toGroup(key, rawEntries) }
                // small groups first: two entries with the same work name are almost always the same upload
                // twice, while a group of thirty is usually an artist whose whole catalogue shares a title
                .sortedWith(compareBy<Group> { it.entries.size }.thenBy { it.title })
        logger.info {
            "duplicates: ${rows.size} library entries -> ${groups.size} groups in ${System.currentTimeMillis() - startedAt} ms"
        }
        return groups
    }

    /** Removes the uploader/circle/series brackets and the release metadata from a normalized title. */
    private fun workNameOf(normalizedTitle: String): String =
        SQUARE_BRACKETS
            .replace(normalizedTitle, " ")
            .let { PARENS.replace(it, " ") }
            .let { value ->
                FULL_WIDTH_PARENS.replace(value) { match ->
                    val body = match.groupValues[1]
                    if (NOISE_WORDS.containsMatchIn(body)) " " else match.value
                }
            }.let { NOISE_WORDS.replace(it, " ") }
            .replace(SPACES, " ")
            // removing a leading `[...] AUTHOR` leaves the separator behind: `- bay&clay`
            .trim { it.isWhitespace() || !it.isLetterOrDigit() }

    private fun isUsableKey(
        cleanTitle: String,
        key: String,
    ): Boolean {
        if (cleanTitle.isBlank() || key.length < 2) return false
        if (key in IGNORED_TITLES) return false
        // a title that is only release metadata is not a work name, grouping those merges unrelated books
        return !NOISE_WORDS.containsMatchIn(cleanTitle)
    }

    private data class Raw(
        val mangaId: Int,
        val title: String,
        val cleanTitle: String,
        val author: String?,
        val normalizedAuthor: String,
        val artist: String?,
        val sourceId: Long,
        val sourceName: String?,
        val chapterCount: Int,
        val downloadedChapterCount: Int,
        val descriptionLength: Int,
        val genreCount: Int,
    ) {
        val usefulAuthor: String?
            get() =
                normalizedAuthor
                    .takeIf { it.isNotBlank() && !NOISE_WORDS.containsMatchIn(it) }
                    ?.let { author }
    }

    private fun toGroup(
        key: String,
        rawEntries: List<Raw>,
    ): Group {
        val maxChapters = rawEntries.maxOf { it.chapterCount }.coerceAtLeast(0)
        val entries = rawEntries.map { it.toEntry(maxChapters) }.sortedByDescending { it.score }

        val authors = entries.mapNotNull { it.author }.map { MangaSearchText.normalizeFast(it) }.toSet()
        val recommended = entries.first()

        return Group(
            key = key,
            title = recommended.cleanTitle,
            entries = entries,
            recommendedKeepId = recommended.mangaId,
            conflictingAuthors = authors.size > 1,
        )
    }

    /**
     * 0..100, higher is better. The weights are a judgement call: the number of chapters is the best proxy for
     * "this is the complete upload", the title ratio separates a clean `作品名` from a title that is mostly
     * release metadata, the rest are the usual quality signals of a library entry.
     */
    private fun Raw.toEntry(maxChapters: Int): Entry {
        val reasons = ArrayList<String>()
        var score = 0.0

        if (maxChapters > 0) {
            score += 30.0 * chapterCount / maxChapters
            if (chapterCount == maxChapters && chapterCount > 0) {
                reasons += "最全（$chapterCount 章）"
            }
        }

        val titleRatio = (cleanTitle.length.toDouble() / title.length.coerceAtLeast(1)).coerceIn(0.0, 1.0)
        score += 20.0 * titleRatio
        if (titleRatio >= 0.9) {
            reasons += "标题干净"
        } else {
            reasons += "标题含发布信息（${(titleRatio * 100).toInt()}% 是作品名）"
        }

        val author = usefulAuthor
        if (author != null) {
            score += 15.0
            reasons += "有作者（$author）"
        } else {
            reasons += "作者缺失"
        }

        if (downloadedChapterCount > 0) {
            score += 15.0
            reasons += "已下载 $downloadedChapterCount 章"
        }

        when {
            descriptionLength >= 80 -> {
                score += 10.0
                reasons += "有简介"
            }
            descriptionLength > 0 -> score += 5.0
        }

        if (genreCount >= 3) {
            score += 5.0
            reasons += "标签 $genreCount 个"
        }

        if (cleanTitle.length in 4..120) {
            score += 5.0
        }

        return Entry(
            mangaId = mangaId,
            title = title,
            cleanTitle = cleanTitle,
            thumbnailUrl = "/api/v1/manga/$mangaId/thumbnail",
            author = author,
            artist = artist,
            sourceId = sourceId,
            sourceName = sourceName,
            chapterCount = chapterCount,
            downloadedChapterCount = downloadedChapterCount,
            score = score.toInt().coerceIn(0, 100),
            reasons = reasons,
        )
    }
}
