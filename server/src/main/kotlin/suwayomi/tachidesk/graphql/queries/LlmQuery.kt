package suwayomi.tachidesk.graphql.queries

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.serialization.json.Json
import suwayomi.tachidesk.llm.LlmQueryPlan
import suwayomi.tachidesk.llm.LlmSearchService
import suwayomi.tachidesk.server.serverConfig

/**
 * Debug entry point for the optional LLM query understanding: shows what the model made of a query without
 * running the library search itself. Useful while tuning the prompt and during the evaluation runs.
 */
class LlmQuery {
    fun llmQueryPlan(query: String): String {
        if (!serverConfig.llmQueryEnabled.value) {
            return "{\"enabled\":false}"
        }

        val plan = LlmSearchService.plan(query)

        return Json.encodeToString(
            LlmQueryPlan.serializer(),
            plan ?: LlmQueryPlan(),
        ).let { if (plan == null) "{\"parsed\":false,\"plan\":$it}" else "{\"parsed\":true,\"plan\":$it}" }
    }
}
