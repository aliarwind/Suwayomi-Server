package suwayomi.tachidesk.server.database

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.ExperimentalKeywordApi
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import suwayomi.tachidesk.graphql.types.DatabaseType
import suwayomi.tachidesk.server.database.migration.M0072_AddMangaLlmIndex
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.test.ApplicationTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * M0072 only creates a table, so the things that can go wrong are the DDL itself (column types, the
 * primary key on `manga_ref` instead of a surrogate id), the foreign key, and a second run against a
 * database where the table already exists.
 */
class M0072AddMangaLlmIndexTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            ApplicationTest.testingSetup()
        }
    }

    @Test
    fun `creates the table, keeps one row per manga and cascades deletes`() {
        Assumptions.assumeTrue(serverConfig.databaseType.value == DatabaseType.H2)

        val previousPrimaryDatabase = TransactionManager.primaryDatabase
        val database =
            Database.connect(
                "jdbc:h2:mem:m0072-${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
                "org.h2.Driver",
                databaseConfig =
                    DatabaseConfig {
                        useNestedTransactions = true
                        @OptIn(ExperimentalKeywordApi::class)
                        preserveKeywordCasing = false
                    },
            )

        try {
            val migration = M0072_AddMangaLlmIndex()

            // the state M0072 finds in a real database: MANGA exists, everything else is new
            transaction(database) {
                exec("CREATE TABLE IF NOT EXISTS MANGA (ID INT PRIMARY KEY, TITLE VARCHAR)")
            }

            transaction(database) { migration.run() }

            // a second run must not fail on the existing table
            transaction(database) { migration.run() }

            transaction(database) {
                val columns =
                    exec(
                        "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'MANGALLMINDEX'",
                    ) { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                assertEquals(13, columns)

                exec("INSERT INTO MANGA (ID, TITLE) VALUES (1, 'title')")
                exec(
                    "INSERT INTO MANGALLMINDEX (MANGA_REF, CLEAN_TITLE, FLAGS, VERSION) VALUES (1, 'clean', '汉化', 1)",
                )

                // the primary key is the manga reference, not a surrogate id
                assertFailsWith<Exception> {
                    exec("INSERT INTO MANGALLMINDEX (MANGA_REF, CLEAN_TITLE, VERSION) VALUES (1, 'again', 1)")
                }

                exec("DELETE FROM MANGA WHERE ID = 1")
                val rows =
                    exec("SELECT COUNT(*) FROM MANGALLMINDEX") { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                assertEquals(0, rows)
            }
        } finally {
            if (previousPrimaryDatabase != null) {
                TransactionManager.defaultDatabase = previousPrimaryDatabase
            } else {
                TransactionManager.closeAndUnregister(database)
            }
        }
    }
}
