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

/**
 * Persistent index of objects stored by a repository.
 * Keys are repository-relative, slash-separated object paths;
 * S3 directory markers are preserved with a trailing slash.
 */
interface ArtifactIndexRepository {

    fun findObject(repository: String, key: String): ListedObject?

    fun findUnder(repository: String, prefix: String): List<ListedObject>

    fun upsert(repository: String, obj: ListedObject)

    fun removeTree(repository: String, key: String)

    fun replaceAll(repository: String, objects: List<ListedObject>)

    fun lastReconciledAt(repository: String): Instant?

    fun markReconciled(repository: String, at: Instant)

}
