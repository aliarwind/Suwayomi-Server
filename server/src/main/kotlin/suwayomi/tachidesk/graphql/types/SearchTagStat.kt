/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package suwayomi.tachidesk.graphql.types

/**
 * One tag of the library vocabulary, as used by the offline alias mining script.
 *
 * [uses] is how many library entries carry the tag, [inAliasGroup] says whether some synonym group already
 * covers it, and [aliasTargets] are the other members of that group (empty when it has none).
 */
class SearchTagStat(
    val tag: String,
    val uses: Int,
    val inAliasGroup: Boolean,
    val aliasTargets: List<String>,
)
