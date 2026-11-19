package suwayomi.tachidesk.llm

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import suwayomi.tachidesk.server.serverConfig
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Minimal OpenAI compatible chat client (`POST {baseUrl}/chat/completions`), enough for DeepSeek, OpenAI and
 * local servers such as Ollama (`http://127.0.0.1:11434/v1`).
 *
 * The whole feature is optional and every failure has to degrade to the rule based search, so this client
 * never throws: it returns `null` and logs.
 */
object LlmClient {
    private val logger = KotlinLogging.logger {}
    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

    /** Configured? The key can be empty for local servers, which is why only the base url is required. */
    fun isConfigured(): Boolean = serverConfig.llmQueryEnabled.value && serverConfig.llmBaseUrl.value.isNotBlank()

    /**
     * Sends one chat completion request. [jsonMode] asks the server for a JSON object response, which both
     * DeepSeek and OpenAI support; servers that ignore it still answer with JSON because the prompt says so.
     */
    fun complete(
        system: String,
        user: String,
        jsonMode: Boolean = true,
    ): String? {
        val baseUrl = serverConfig.llmBaseUrl.value.trim().trimEnd('/')
        val model = serverConfig.llmChatModel.value.trim()
        val apiKey = serverConfig.llmApiKey.value.trim()
        val timeoutMs = serverConfig.llmQueryTimeoutMs.value.toLong()

        val body =
            buildJsonObject {
                put("model", model)
                put("temperature", 0)
                put("max_tokens", 300)
                if (jsonMode) put("response_format", buildJsonObject { put("type", "json_object") })
                put(
                    "messages",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("role", "system")
                                put("content", system)
                            },
                        )
                        add(
                            buildJsonObject {
                                put("role", "user")
                                put("content", user)
                            },
                        )
                    },
                )
            }.toString()

        val request =
            HttpRequest
                .newBuilder()
                .uri(URI.create("$baseUrl/chat/completions"))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("content-type", "application/json")
                .apply { if (apiKey.isNotEmpty()) header("authorization", "Bearer $apiKey") }
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()

        repeat(2) { attempt ->
            try {
                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() == 200) {
                    return contentOf(response.body())
                }

                logger.warn { "chat completion failed: HTTP ${response.statusCode()} on attempt ${attempt + 1}" }
                // Retry only server side errors: a timeout already cost the caller the full budget.
                if (response.statusCode() !in 500..599) return null
            } catch (e: Exception) {
                logger.warn(e) { "chat completion failed on attempt ${attempt + 1}" }
                return null
            }
        }

        return null
    }

    private fun contentOf(body: String): String? =
        runCatching {
            json
                .parseToJsonElement(body)
                .jsonObject["choices"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("message")
                ?.jsonObject
                ?.get("content")
                ?.jsonPrimitive
                ?.content
        }.getOrNull()?.takeIf { it.isNotBlank() }
}
