/*
 * Copyright (c) 2020-2026 dzikoysk
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.reposilite.maven.index

import com.reposilite.storage.ListedObject
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

internal object ArtifactIndexTable : Table("s3_artifact_index") {
    val repository = varchar("repository", 128)
    val key = varchar("key", 1024)
    val size = long("size")
    val lastModified = long("last_modified").nullable()

    override val primaryKey = PrimaryKey(repository, key)
}

internal object ArtifactIndexStateTable : Table("s3_artifact_index_state") {
    val repository = varchar("repository", 128)
    val lastReconciled = long("last_reconciled")

    override val primaryKey = PrimaryKey(repository)
}

class SqliteArtifactIndexRepository(private val database: Database) : ArtifactIndexRepository {

    init {
        transaction(database) {
            SchemaUtils.create(ArtifactIndexTable, ArtifactIndexStateTable)
        }
    }

    override fun findObject(repository: String, key: String): ListedObject? =
        transaction(database) {
            ArtifactIndexTable.selectAll()
                .where { (ArtifactIndexTable.repository eq repository) and (ArtifactIndexTable.key eq key) }
                .singleOrNull()
                ?.toListedObject()
        }

    override fun findUnder(repository: String, prefix: String): List<ListedObject> =
        transaction(database) {
            ArtifactIndexTable.selectAll()
                .where(keyRange(repository, prefix))
                .map { it.toListedObject() }
        }

    override fun upsert(repository: String, obj: ListedObject) {
        transaction(database) {
            val updated = ArtifactIndexTable.update(where = { (ArtifactIndexTable.repository eq repository) and (ArtifactIndexTable.key eq obj.key) }) { statement ->
                statement[ArtifactIndexTable.size] = obj.size
                statement[lastModified] = obj.lastModifiedTime?.toEpochMilli()
            }

            if (updated == 0) {
                ArtifactIndexTable.insert { statement ->
                    statement[ArtifactIndexTable.repository] = repository
                    statement[ArtifactIndexTable.key] = obj.key
                    statement[ArtifactIndexTable.size] = obj.size
                    statement[lastModified] = obj.lastModifiedTime?.toEpochMilli()
                }
            }
        }
    }

    override fun removeTree(repository: String, key: String) {
        transaction(database) {
            ArtifactIndexTable.deleteWhere {
                (ArtifactIndexTable.repository eq repository) and (ArtifactIndexTable.key eq key)
            }
            ArtifactIndexTable.deleteWhere { keyRange(repository, "$key/") }
        }
    }

    override fun replaceAll(repository: String, objects: List<ListedObject>) {
        transaction(database) {
            ArtifactIndexTable.deleteWhere { ArtifactIndexTable.repository eq repository }

            objects.forEach { obj ->
                ArtifactIndexTable.insert { statement ->
                    statement[ArtifactIndexTable.repository] = repository
                    statement[ArtifactIndexTable.key] = obj.key
                    statement[ArtifactIndexTable.size] = obj.size
                    statement[lastModified] = obj.lastModifiedTime?.toEpochMilli()
                }
            }
        }
    }

    override fun lastReconciledAt(repository: String): Instant? =
        transaction(database) {
            ArtifactIndexStateTable.selectAll()
                .where { ArtifactIndexStateTable.repository eq repository }
                .singleOrNull()
                ?.get(ArtifactIndexStateTable.lastReconciled)
                ?.let { Instant.ofEpochMilli(it) }
        }

    override fun markReconciled(repository: String, at: Instant) {
        transaction(database) {
            val updated = ArtifactIndexStateTable.update(where = { ArtifactIndexStateTable.repository eq repository }) { statement ->
                statement[lastReconciled] = at.toEpochMilli()
            }

            if (updated == 0) {
                ArtifactIndexStateTable.insert { statement ->
                    statement[ArtifactIndexStateTable.repository] = repository
                    statement[lastReconciled] = at.toEpochMilli()
                }
            }
        }
    }

    private fun keyRange(repository: String, prefix: String): Op<Boolean> =
        when {
            prefix.isEmpty() -> ArtifactIndexTable.repository eq repository
            else ->
                (ArtifactIndexTable.repository eq repository)
                    .and(ArtifactIndexTable.key.greaterEq(prefix))
                    .and(ArtifactIndexTable.key.less(upperBound(prefix)))
        }

    private fun upperBound(prefix: String): String =
        prefix.dropLast(1) + (prefix.last() + 1)

    private fun ResultRow.toListedObject(): ListedObject =
        ListedObject(
            key = this[ArtifactIndexTable.key],
            size = this[ArtifactIndexTable.size],
            lastModifiedTime = this[ArtifactIndexTable.lastModified]?.let { Instant.ofEpochMilli(it) },
        )

}

/**
 * Lazily opens the dedicated SQLite file that backs the artifact index.
 * The index deliberately lives outside of the main Reposilite database, so
 * instances that never enable the feature create no database at all.
 */
class ArtifactIndexDatabase(private val directory: Path) {

    @Volatile
    private var instance: SqliteArtifactIndexRepository? = null

    fun open(): ArtifactIndexRepository =
        instance ?: synchronized(this) {
            instance ?: create().also { instance = it }
        }

    private fun create(): SqliteArtifactIndexRepository {
        Files.createDirectories(directory)

        val database = Database.connect("jdbc:sqlite:${directory.resolve("s3-index.db").toAbsolutePath()}")

        return SqliteArtifactIndexRepository(database)
    }

}
