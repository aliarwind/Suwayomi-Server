package suwayomi.tachidesk.graphql.queries

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.types.SearchTagStat
import suwayomi.tachidesk.manga.impl.search.MangaSearchAliases
import suwayomi.tachidesk.manga.impl.search.MangaSearchVocabulary

/**
 * Read only views on the search index, used to tune the search without re-reading the code: which tags
 * dominate the library, which of them already have synonyms.
 */
class SearchQuery {
    /**
     * The most used tags of the library. The offline alias mining script turns these into candidate
     * synonym groups; [SearchTagStat.aliasTargets] shows what is already covered.
     */
    @RequireAuth
    fun searchTagStats(limit: Int = 200): List<SearchTagStat> {
        if (!MangaSearchVocabulary.isReady()) {
            return emptyList()
        }

        return MangaSearchVocabulary.topTags(limit.coerceIn(1, MAX_LIMIT)).map { (tag, uses) ->
            SearchTagStat(
                tag = tag,
                uses = uses,
                inAliasGroup = MangaSearchAliases.isAlias(tag),
                aliasTargets = MangaSearchAliases.aliasesOf(tag),
            )
        }
    }

    private companion object {
        const val MAX_LIMIT = 2000
    }
}
