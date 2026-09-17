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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

internal abstract class ArtifactIndexRepositorySpecification {

    protected val instant: Instant = Instant.parse("2026-01-01T12:00:00Z")

    protected abstract fun repository(): ArtifactIndexRepository

    @Test
    fun `should store and find objects`() {
        val repository = repository()

        repository.upsert("releases", ListedObject("com/example/lib.jar", 42, instant))

        val found = repository.findObject("releases", "com/example/lib.jar")
        assertThat(found).isEqualTo(ListedObject("com/example/lib.jar", 42, instant))
    }

    @Test
    fun `should not find objects of other repositories`() {
        val repository = repository()

        repository.upsert("releases", ListedObject("lib.jar", 1, instant))

        assertThat(repository.findObject("snapshots", "lib.jar")).isNull()
        assertThat(repository.findUnder("snapshots", "")).isEmpty()
        assertThat(repository.lastReconciledAt("snapshots")).isNull()
    }

    @Test
    fun `should find objects under a prefix`() {
        val repository = repository()
        listOf(
            ListedObject("a", 1, instant),
            ListedObject("a/", 0, null),
            ListedObject("a/b.jar", 2, instant),
            ListedObject("a/sub/c.jar", 3, instant),
            ListedObject("ab.jar", 4, instant),
        ).forEach { repository.upsert("releases", it) }

        val under = repository.findUnder("releases", "a/")

        assertThat(under.map { it.key }).containsExactlyInAnyOrder("a/", "a/b.jar", "a/sub/c.jar")
    }

    @Test
    fun `should remove exact object and subtree`() {
        val repository = repository()
        listOf(
            ListedObject("a/b.jar", 1, instant),
            ListedObject("a/sub/c.jar", 2, instant),
            ListedObject("a.jar", 3, instant),
        ).forEach { repository.upsert("releases", it) }

        repository.removeTree("releases", "a")

        assertThat(repository.findObject("releases", "a/b.jar")).isNull()
        assertThat(repository.findObject("releases", "a/sub/c.jar")).isNull()
        assertThat(repository.findObject("releases", "a.jar")).isNotNull
    }

    @Test
    fun `should upsert over existing objects`() {
        val repository = repository()

        repository.upsert("releases", ListedObject("lib.jar", 1, instant))
        repository.upsert("releases", ListedObject("lib.jar", 2, instant))

        assertThat(repository.findObject("releases", "lib.jar")?.size).isEqualTo(2)
    }

    @Test
    fun `should replace all objects of a repository`() {
        val repository = repository()
        repository.upsert("releases", ListedObject("old.jar", 1, instant))

        repository.replaceAll("releases", listOf(ListedObject("new.jar", 2, instant)))

        assertThat(repository.findObject("releases", "old.jar")).isNull()
        assertThat(repository.findObject("releases", "new.jar")).isNotNull
        assertThat(repository.findUnder("releases", "")).hasSize(1)
    }

    @Test
    fun `should store reconciliation state per repository`() {
        val repository = repository()

        assertThat(repository.lastReconciledAt("releases")).isNull()

        repository.markReconciled("releases", instant)

        assertThat(repository.lastReconciledAt("releases")).isEqualTo(instant)
        assertThat(repository.lastReconciledAt("snapshots")).isNull()
    }

}
