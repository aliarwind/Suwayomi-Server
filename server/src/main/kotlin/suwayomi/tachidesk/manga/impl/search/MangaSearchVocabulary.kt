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
 *
 * Built once after the search index backfill; while empty, [plan] degrades to the previous behavior.
 */
object MangaSearchVocabulary {
    private val logger = KotlinLogging.logger {}

    @Volatile
    private var indexedTexts: List<String> = emptyList()

    @Volatile
    private var tags: Set<String> = emptySet()

    /** A tag has to be used by at least this many library entries to count as a genre tag. */
    private const val MIN_TAG_USES = 2

    /** Tags longer than this are source specific noise rather than reusable search terms. */
    private const val MAX_TAG_LENGTH = 20

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
        val (texts, tagSet) =
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

                textList to tagSet
            }

        indexedTexts = texts
        tags = tagSet
        logger.info {
            "Manga search vocabulary ready: ${texts.size} indexed texts, ${tagSet.size} tags in " +
                "${System.currentTimeMillis() - start}ms"
        }
    }

    /** Splits a query into the groups described on the object. [texts] and [tagSet] are injectable for tests. */
    fun plan(
        query: String?,
        texts: List<String> = indexedTexts,
        tagSet: Set<String> = tags,
    ): SearchPlan {
        val rawTokens = MangaSearchText.queryTokens(query)
        if (rawTokens.isEmpty()) return SearchPlan(emptyList(), emptyList(), emptyList())

        val tokenGroups = rawTokens.map { token -> termsFor(token, texts) }

        // Nothing known yet (vocabulary not built) or nothing matched: keep the raw tokens as before.
        val fallbackToRaw = tokenGroups.all { it.isEmpty() }
        val groups = if (fallbackToRaw) rawTokens.map { listOf(it) } else tokenGroups.filter { it.isNotEmpty() }

        // A group that resolves to a genre tag is matched as tags (OR among themselves), because that is
        // how users list tags ("妓女、娼妇、婊子" means any of them). All other groups are AND-ed.
        val tagGroups = groups.filter { group -> group.any { it in tagSet } }
        val textGroups = groups.filter { group -> group.none { it in tagSet } }

        val dropped =
            if (fallbackToRaw) {
                emptyList()
            } else {
                rawTokens.filterIndexed { index, _ -> tokenGroups[index].isEmpty() }
            }

        return SearchPlan(textGroups, tagGroups.flatten().distinct(), dropped)
    }

    /**
     * All terms a query token can be matched with: the token itself when the library contains it, plus the
     * members of its synonym group that also exist. Never returns a term the library cannot match.
     */
    private fun termsFor(
        token: String,
        texts: List<String>,
    ): List<String> {
        val direct = resolve(token, texts)
        val aliases = MangaSearchAliases.aliasesOf(direct ?: token).filter { exists(it, texts) }
        if (direct == null && aliases.isEmpty()) return emptyList()

        return (listOfNotNull(direct) + aliases).distinct()
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

    private fun exists(token: String, texts: List<String>): Boolean {
        if (texts.isEmpty()) return false
        return texts.any { it.contains(token) }
    }
}
