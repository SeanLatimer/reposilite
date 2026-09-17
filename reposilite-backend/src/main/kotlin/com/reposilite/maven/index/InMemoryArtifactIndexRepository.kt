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
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class InMemoryArtifactIndexRepository : ArtifactIndexRepository {

    private val objects = ConcurrentHashMap<String, ConcurrentHashMap<String, ListedObject>>()
    private val reconciledAt = ConcurrentHashMap<String, Instant>()

    override fun findObject(repository: String, key: String): ListedObject? =
        objects[repository]?.get(key)

    override fun findUnder(repository: String, prefix: String): List<ListedObject> =
        objects[repository]?.keys
            ?.filter { it.startsWith(prefix) }
            ?.mapNotNull { objects[repository]?.get(it) }
            ?: emptyList()

    override fun upsert(repository: String, obj: ListedObject) {
        objects.getOrPut(repository) { ConcurrentHashMap() }[obj.key] = obj
    }

    override fun removeTree(repository: String, key: String) {
        val prefix = "$key/"
        objects[repository]?.keys?.removeAll { it == key || it.startsWith(prefix) }
    }

    override fun replaceAll(repository: String, objs: List<ListedObject>) {
        val fresh = ConcurrentHashMap<String, ListedObject>()
        objs.forEach { fresh[it.key] = it }
        objects[repository] = fresh
    }

    override fun lastReconciledAt(repository: String): Instant? =
        reconciledAt[repository]

    override fun markReconciled(repository: String, at: Instant) {
        reconciledAt[repository] = at
    }

}
