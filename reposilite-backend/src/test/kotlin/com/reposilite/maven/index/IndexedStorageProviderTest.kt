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

import com.reposilite.journalist.backend.InMemoryLogger
import com.reposilite.storage.api.DocumentInfo
import com.reposilite.storage.api.DirectoryInfo
import com.reposilite.storage.api.Location
import com.reposilite.storage.api.toLocation
import com.reposilite.storage.s3.S3IndexSettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import panda.std.ResultAssertions.assertError
import panda.std.ResultAssertions.assertOk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

internal class IndexedStorageProviderTest {

    private val fixedClock = Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneOffset.UTC)

    private fun provider(settings: S3IndexSettings = S3IndexSettings(enabled = true), storage: FakeStorageProvider = FakeStorageProvider()): IndexedStorageProvider =
        IndexedStorageProvider(
            journalist = InMemoryLogger(),
            delegate = storage,
            index = InMemoryArtifactIndexRepository(),
            repository = "releases",
            settings = settings,
            clock = fixedClock,
        )

    @Test
    fun `should seed directory on first lookup and serve subsequent lookups from index`() {
        val storage = FakeStorageProvider()
        storage.put("com/example/lib.jar", size = 42, lastModifiedTime = fixedClock.instant())
        val provider = provider(storage = storage)

        val first = assertOk(provider.getFileDetails("com".toLocation())) as DirectoryInfo
        assertThat(first.files.map { it.name }).containsExactly("example")

        val second = assertOk(provider.getFileDetails("com".toLocation())) as DirectoryInfo
        assertThat(second.files.map { it.name }).containsExactly("example")

        assertThat(storage.listCalls).isEqualTo(1)
    }

    @Test
    fun `should serve nested directory with metadata after seeding`() {
        val storage = FakeStorageProvider()
        storage.put("com/example/lib.jar", size = 42, lastModifiedTime = fixedClock.instant())
        val provider = provider(storage = storage)

        assertOk(provider.getFileDetails("com/example".toLocation()))
        val cached = assertOk(provider.getFileDetails("com/example".toLocation())) as DirectoryInfo

        assertThat(cached.files).hasSize(1)
        val file = cached.files.first() as DocumentInfo
        assertThat(file.name).isEqualTo("lib.jar")
        assertThat(file.contentLength).isEqualTo(42)
        assertThat(file.lastModifiedTime).isEqualTo(fixedClock.instant())
        assertThat(storage.listCalls).isEqualTo(1)
    }

    @Test
    fun `should reflect deployed file in listing without additional s3 list`() {
        val storage = FakeStorageProvider()
        storage.put("com/example/lib.jar")
        val provider = provider(storage = storage)

        assertOk(provider.getFileDetails("com/example".toLocation()))
        val listCallsAfterSeed = storage.listCalls

        assertOk(provider.putFile("com/example/new.jar".toLocation(), "content".toByteArray().inputStream()))
        val listing = assertOk(provider.getFileDetails("com/example".toLocation())) as DirectoryInfo

        assertThat(listing.files.map { it.name }).containsExactly("lib.jar", "new.jar")
        assertThat(storage.listCalls).isEqualTo(listCallsAfterSeed)
    }

    @Test
    fun `should prune deleted subtree from index`() {
        val storage = FakeStorageProvider()
        storage.put("com/example/lib.jar")
        storage.put("com/example/other/other.jar")
        val provider = provider(storage = storage)

        assertOk(provider.getFileDetails("com".toLocation()))
        assertOk(provider.getFileDetails("com/example".toLocation()))
        assertOk(provider.removeFile("com/example/other".toLocation()))

        val listing = assertOk(provider.getFileDetails("com/example".toLocation())) as DirectoryInfo
        assertThat(listing.files.map { it.name }).containsExactly("lib.jar")
    }

    @Test
    fun `should reconcile index against storage`() {
        val storage = FakeStorageProvider()
        storage.put("com/example/lib.jar")
        val provider = provider(storage = storage)

        assertOk(provider.getFileDetails("com".toLocation()))

        storage.put("out-of-band/new.jar", size = 7)
        storage.objects.remove("com/example/lib.jar")

        val reconciled = assertOk(provider.reconcile())
        assertThat(reconciled).isEqualTo(1)

        val outOfBand = assertOk(provider.getFileDetails("out-of-band".toLocation())) as DirectoryInfo
        assertThat(outOfBand.files.map { it.name }).containsExactly("new.jar")

        assertError(provider.getFileDetails("com/example".toLocation()))
        assertThat(storage.listCalls).isEqualTo(1)
    }

    @Test
    fun `should serve file metadata from index only when configured`() {
        val storage = FakeStorageProvider()
        storage.put("com/lib.jar", size = 42)
        val provider = provider(settings = S3IndexSettings(enabled = true, serveMetadata = true), storage = storage)

        assertOk(provider.reconcile())
        val headCallsAfterReconcile = storage.headCalls

        val file = assertOk(provider.getFileDetails("com/lib.jar".toLocation())) as DocumentInfo
        assertThat(file.contentLength).isEqualTo(42)
        assertThat(storage.headCalls).isEqualTo(headCallsAfterReconcile)
    }

    @Test
    fun `should pass through file metadata lookups when serving metadata is disabled`() {
        val storage = FakeStorageProvider()
        storage.put("com/lib.jar", size = 42)
        val provider = provider(settings = S3IndexSettings(enabled = true, serveMetadata = false), storage = storage)

        assertOk(provider.reconcile())
        val headCallsAfterReconcile = storage.headCalls

        assertOk(provider.getFileDetails("com/lib.jar".toLocation()))
        assertThat(storage.headCalls).isGreaterThan(headCallsAfterReconcile)
    }

    @Test
    fun `should answer existence checks from reconciled index`() {
        val storage = FakeStorageProvider()
        storage.put("com/lib.jar")
        val provider = provider(storage = storage)

        assertOk(provider.reconcile())

        assertThat(provider.exists("com/lib.jar".toLocation())).isTrue
        assertThat(provider.exists("com".toLocation())).isTrue
        assertThat(provider.exists("missing.jar".toLocation())).isFalse
    }

    @Test
    fun `should treat directory marker as existing but empty directory`() {
        val storage = FakeStorageProvider()
        storage.put("marker/")
        val provider = provider(storage = storage)

        assertOk(provider.reconcile())

        val listing = assertOk(provider.getFileDetails("marker".toLocation())) as DirectoryInfo
        assertThat(listing.files).isEmpty()
    }

    @Test
    fun `should expose reconciliation state`() {
        val provider = provider()

        assertThat(provider.lastReconciledAt()).isNull()

        assertOk(provider.reconcile())

        assertThat(provider.lastReconciledAt()).isEqualTo(fixedClock.instant())
    }

}
