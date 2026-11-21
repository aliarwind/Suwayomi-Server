package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

/**
 * Keys of the per-manga metadata the server itself reads back.
 *
 * Sources derive the author from the title, so every refresh rewrites it. Once the value was corrected
 * (manually or by [suwayomi.tachidesk.graphql.mutations.LlmMutation.llmFixAuthors]) it has to survive the
 * refreshes: `authorLocked=true` makes [Manga.updateMangaDatabase] keep the stored author and artist.
 */
object MangaMetaKeys {
    const val AUTHOR_LOCKED = "authorLocked"
}
