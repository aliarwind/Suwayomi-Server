package suwayomi.tachidesk.manga.impl.search

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.MangaUserTable

/**
 * Knows what can actually be found in the library, so a free text query can be split into:
 *
 * - tokens the library contains and that are not tags: AND (narrowing)
 * - tokens that are genre tags: OR among themselves (users list tags meaning "any of these")
 * - tokens the library does not contain at all ("画风", "上位女"): dropped instead of emptying the result
 *
 * Membership is checked with substring matching against the indexed text, because Chinese has no word
 * boundaries: the stored tokens are whole phrases, so an exact token lookup would never match "怀孕".
 * Multi word terms (typically an alias like "blue archive") need all their words, see [MangaSearchMatch].
 *
 * Built once after the search index backfill; while empty, [plan] degrades to the previous behavior.
 */
object MangaSearchVocabulary {
    private val logger = KotlinLogging.logger {}

    @Volatile
    private var indexedTexts: List<String> = emptyList()

    @Volatile
    private var tags: Set<String> = emptySet()

    /** How many library entries use each tag, kept for the alias mining query ([topTags]). */
    @Volatile
    private var tagCounts: Map<String, Int> = emptyMap()

    /** A tag has to be used by at least this many library entries to count as a genre tag. */
    private const val MIN_TAG_USES = 2

    /** Tags longer than this are source specific noise rather than reusable search terms. */
    private const val MAX_TAG_LENGTH = 20

    /** Longest word the compound splitter tries to match when a query token is not in the library. */
    private const val MAX_SEGMENT_LENGTH = 4

    /** Query fillers that often get glued to a name ("像武田弘光"); stripped when the token is unknown. */
    private val LEADING_FILLERS = setOf('像', '如', '似', '是', '有', '找', '求', '看')
    private val TRAILING_FILLERS = setOf('的', '了', '吗', '呢', '吧')

    data class SearchPlan(
        /** Groups of equivalent terms that must all match; one group is "any of these". */
        val textGroups: List<List<String>>,
        /** Genre tags of which at least one has to match; empty if the query has no tags. */
        val anyTerms: List<String>,
        /** Tokens dropped because the library does not contain them (nor any of their aliases). */
        val droppedTerms: List<String>,
    ) {
        /** All text terms regardless of grouping, e.g. for the last resort OR query. */
        val allTerms: List<String> get() = textGroups.flatten().distinct()

        val isEmpty: Boolean get() = textGroups.isEmpty() && anyTerms.isEmpty()
    }

    fun isReady(): Boolean = indexedTexts.isNotEmpty()

    /** (Re)builds the vocabulary from the library. Safe to call more than once. */
    fun build() {
        val start = System.currentTimeMillis()
        val built =
            transaction {
                val textList = ArrayList<String>(1 shl 16)
                val tagUses = HashMap<String, Int>()

                MangaTable
                    .innerJoin(MangaUserTable)
                    .select(MangaTable.searchText, MangaTable.genre)
                    .where { MangaUserTable.inLibrary eq true }
                    .forEach { row ->
                        row[MangaTable.searchText]?.takeIf { it.isNotEmpty() }?.let(textList::add)

                        row[MangaTable.genre]
                            ?.split(',')
                            ?.forEach { rawTag ->
                                MangaSearchText.queryTokens(rawTag).forEach { tagUses.merge(it, 1, Int::plus) }
                            }
                    }

                val tagSet =
                    tagUses
                        .asSequence()
                        .filter { (tag, uses) -> uses >= MIN_TAG_USES && tag.length <= MAX_TAG_LENGTH }
                        .map { it.key }
                        .toHashSet()

                Triple(textList, tagSet, tagUses.toMap())
            }

        val (texts, tagSet, uses) = built
        indexedTexts = texts
        tags = tagSet
        tagCounts = uses.filterKeys { it in tagSet }
        logger.info {
            "Manga search vocabulary ready: ${texts.size} indexed texts, ${tagSet.size} tags in " +
                "${System.currentTimeMillis() - start}ms"
        }
    }

    /**
     * The most used tags of the library, with their usage counts. Feeds the offline alias mining script,
     * which asks the model for other ways to search these terms - so new aliases can be found without
     * reading the code.
     */
    fun topTags(limit: Int): List<Pair<String, Int>> =
        tagCounts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { it.key to it.value }

    /** Splits a query into the groups described on the object. [texts] and [tagSet] are injectable for tests. */
    fun plan(
        query: String?,
        texts: List<String> = indexedTexts,
        tagSet: Set<String> = tags,
    ): SearchPlan {
        val rawTokens = MangaSearchText.queryTokens(query)
        if (rawTokens.isEmpty()) return SearchPlan(emptyList(), emptyList(), emptyList())

        // One token can contribute several groups: an unknown CJK compound ("婊子人妻") is split into
        // the words the library knows ("婊子" + "人妻"), and those are AND-ed like separate tokens.
        val tokenTerms = rawTokens.map { token -> termsFor(token, texts) }

        // Nothing known yet (vocabulary not built) or nothing matched: keep the raw tokens as before.
        val fallbackToRaw = tokenTerms.all { it.groups.isEmpty() }

        // A group that resolves to a genre tag is matched as tags (OR among themselves), because that is
        // how users list tags ("妓女、娼妇、婊子" means any of them). All other groups are AND-ed.
        // Pieces of a glued compound are always AND-ed, even when they are tags: "婊子人妻" means both.
        val textGroups = mutableListOf<List<String>>()
        val tagGroups = mutableListOf<List<String>>()
        val dropped = mutableListOf<String>()

        if (fallbackToRaw) {
            rawTokens.forEach { token ->
                if (token in tagSet) tagGroups += listOf(token) else textGroups += listOf(token)
            }
        } else {
            tokenTerms.forEach { terms ->
                if (terms.groups.isEmpty()) {
                    dropped += terms.token
                    return@forEach
                }

                terms.groups.forEach { group ->
                    if (!terms.segmented && group.any { it in tagSet }) tagGroups += group else textGroups += group
                }
            }
        }

        return SearchPlan(textGroups, tagGroups.flatten().distinct(), dropped)
    }

    /**
     * All terms a query token can be matched with: the token itself when the library contains it, plus the
     * members of its synonym group that also exist. Never returns a term the library cannot match.
     */
    private class TokenTerms(
        val token: String,
        /** OR groups for this token; several groups means the token was split into AND-ed words. */
        val groups: List<List<String>>,
        val segmented: Boolean = false,
    )

    private fun termsFor(
        token: String,
        texts: List<String>,
    ): TokenTerms {
        val direct = resolve(token, texts)
        if (direct != null) {
            val aliases = MangaSearchAliases.aliasesOf(direct).filter { exists(it, texts) }
            return TokenTerms(token, listOf((listOf(direct) + aliases).distinct()))
        }

        val aliases = MangaSearchAliases.aliasesOf(token).filter { exists(it, texts) }
        if (aliases.isNotEmpty()) return TokenTerms(token, listOf(aliases))

        // Chinese has no word boundaries ("婊子人妻" is two tags glued together), so try to cover the
        // unknown token with words the library actually contains.
        val pieces = segmentsOf(token, texts)
        if (pieces.isEmpty()) return TokenTerms(token, emptyList())

        return TokenTerms(token, pieces.map { piece -> listOf(piece) }, segmented = true)
    }

    /**
     * Greedy longest-match segmentation of an unknown CJK token into known words. Returns the pieces when
     * the whole token can be covered by at least two known words, otherwise nothing (the token is dropped
     * or falls back to a literal match as before).
     */
    private fun segmentsOf(
        token: String,
        texts: List<String>,
    ): List<String> {
        // Latin is already split off before tokenizing; only CJK runs are worth segmenting, and two
        // characters is the shortest meaningful Chinese word.
        if (token.length < 4 || token.any { it.code < 0x2E80 }) return emptyList()

        val pieces = mutableListOf<String>()
        var index = 0
        while (index < token.length) {
            val maxLength = minOf(MAX_SEGMENT_LENGTH, token.length - index)
            var match: String? = null
            for (length in maxLength downTo 2) {
                val candidate = token.substring(index, index + length)
                if (exists(candidate, texts)) {
                    match = candidate
                    break
                }
            }
            if (match == null) return emptyList()

            pieces += match
            index += match.length
        }

        return if (pieces.size >= 2) pieces else emptyList()
    }

    private fun resolve(token: String, texts: List<String>): String? {
        if (exists(token, texts)) return token
        if (token.length <= 2) return null

        // "像武田弘光" is one CJK run; strip a glued filler before giving up on it.
        val candidates =
            buildList {
                if (token[0] in LEADING_FILLERS) add(token.substring(1))
                if (token.last() in TRAILING_FILLERS) add(token.dropLast(1))
                add(token.substring(1))
                add(token.dropLast(1))
            }
        return candidates.firstOrNull { it.length >= 2 && exists(it, texts) }
    }

    private fun exists(
        token: String,
        texts: List<String>,
    ): Boolean {
        if (texts.isEmpty()) return false
        // the words are computed once: this runs against every indexed text, so normalizing per text would
        // dominate the query
        val words = MangaSearchMatch.wordsOf(token)
        return texts.any { text -> MangaSearchMatch.matches(words, text) }
    }
}
