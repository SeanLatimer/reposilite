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

import com.reposilite.journalist.Journalist
import com.reposilite.journalist.Logger
import com.reposilite.shared.ErrorResponse
import com.reposilite.shared.internalServerError
import com.reposilite.shared.notFoundError
import com.reposilite.storage.FullListingProvider
import com.reposilite.storage.ListedObject
import com.reposilite.storage.StorageProvider
import com.reposilite.storage.api.DirectoryInfo
import com.reposilite.storage.api.DocumentInfo
import com.reposilite.storage.api.FileDetails
import com.reposilite.storage.api.Location
import com.reposilite.storage.api.SimpleDirectoryInfo
import com.reposilite.storage.api.UNKNOWN_LENGTH
import com.reposilite.storage.api.toLocation
import com.reposilite.storage.s3.S3IndexSettings
import io.javalin.http.ContentType
import io.javalin.http.ContentType.APPLICATION_OCTET_STREAM
import panda.std.Result
import panda.std.asSuccess
import java.io.InputStream
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Decorator that serves repository listings from a local index instead of
 * querying S3 on every browse. The index is updated on deploys and deletes
 * and periodically reconciled against S3, which remains the source of truth.
 */
internal class IndexedStorageProvider(
    private val journalist: Journalist,
    private val delegate: StorageProvider,
    private val index: ArtifactIndexRepository,
    val repository: String,
    private val settings: S3IndexSettings,
    private val clock: Clock,
) : StorageProvider, Journalist {

    private val fullListing: FullListingProvider? = delegate as? FullListingProvider

    @Volatile
    private var fullyIndexed: Boolean = index.lastReconciledAt(repository) != null

    private val seededDirectories = ConcurrentHashMap.newKeySet<String>()

    fun lastReconciledAt(): Instant? =
        index.lastReconciledAt(repository)

    fun reconciliationIntervalSeconds(): Long =
        settings.reconciliationIntervalSeconds

    fun reconcile(): Result<Int, ErrorResponse> {
        val listing = fullListing
            ?: return internalServerError("Storage provider '${delegate.javaClass.simpleName}' does not support full listings")

        return listing.listAllObjects()
            .peek { objects ->
                index.replaceAll(repository, objects)
                index.markReconciled(repository, clock.instant())
                fullyIndexed = true
                journalist.logger.info("INDEX | Repository $repository reconciled, ${objects.size} objects indexed")
            }
            .map { objects -> objects.size }
    }

    override fun getFileDetails(location: Location): Result<out FileDetails, ErrorResponse> {
        val key = location.toString()
        val prefix = directoryPrefix(location)

        val indexed = location == Location.empty() || fullyIndexed || seededDirectories.contains(prefix)

        return when {
            indexed && isKnownDirectory(prefix) -> findDirectory(location, prefix)
            indexed && !settings.serveMetadata && location != Location.empty() -> delegate.getFileDetails(location)
            indexed -> findFile(location, key)
            settings.serveMetadata -> when (val cached = index.findObject(repository, key)) {
                null -> delegate.getFileDetails(location).map { recordLookup(key, prefix, it) }
                else -> toDocumentInfo(location, key, cached).asSuccess()
            }
            else -> delegate.getFileDetails(location).map { recordLookup(key, prefix, it) }
        }
    }

    override fun getFiles(location: Location): Result<List<Location>, ErrorResponse> {
        val prefix = directoryPrefix(location)

        val indexed = location == Location.empty() || fullyIndexed || seededDirectories.contains(prefix)

        return when {
            !indexed -> delegate.getFiles(location)
            else -> findDirectory(location, prefix).flatMap { details ->
                when (details) {
                    is DirectoryInfo -> (details.files.map { child -> childKey(prefix, child.name).toLocation() }).asSuccess()
                    else -> notFoundError("Directory not found")
                }
            }
        }
    }

    override fun exists(location: Location): Boolean =
        when {
            location == Location.empty() -> true
            fullyIndexed -> index.findObject(repository, location.toString()) != null || index.findUnder(repository, directoryPrefix(location)).isNotEmpty()
            index.findObject(repository, location.toString()) != null || seededDirectories.contains(directoryPrefix(location)) -> true
            else -> delegate.exists(location)
        }

    override fun putFile(location: Location, inputStream: InputStream): Result<Unit, ErrorResponse> =
        delegate.putFile(location, inputStream)
            .peek { recordPut(location) }

    override fun removeFile(location: Location): Result<Unit, ErrorResponse> =
        delegate.removeFile(location)
            .peek { index.removeTree(repository, location.toString()) }

    override fun getFile(location: Location): Result<InputStream, ErrorResponse> =
        delegate.getFile(location)

    override fun getLastModifiedTime(location: Location): Result<FileTime, ErrorResponse> =
        delegate.getLastModifiedTime(location)

    override fun getFileSize(location: Location): Result<Long, ErrorResponse> =
        delegate.getFileSize(location)

    override fun usage(): Result<Long, ErrorResponse> =
        delegate.usage()

    override fun canHold(contentLength: Long): Result<Long, ErrorResponse> =
        delegate.canHold(contentLength)

    override fun shutdown() =
        delegate.shutdown()

    private fun isKnownDirectory(prefix: String): Boolean =
        when {
            prefix.isEmpty() -> true
            fullyIndexed -> index.findUnder(repository, prefix).isNotEmpty()
            else -> true // a seeded prefix implies the directory has been listed before
        }

    private fun findDirectory(location: Location, prefix: String): Result<out FileDetails, ErrorResponse> {
        if (!fullyIndexed && !seededDirectories.contains(prefix)) {
            return delegate.getFileDetails(location)
                .map { seed(prefix, it) }
        }

        val directories = LinkedHashMap<String, SimpleDirectoryInfo>()
        val files = LinkedHashMap<String, DocumentInfo>()
        var hasMarker = false

        index.findUnder(repository, prefix).forEach { entry ->
            val relative = entry.key.removePrefix(prefix)
            val segment = relative.substringBefore('/')

            when {
                relative.isEmpty() -> hasMarker = true
                "/" in relative -> directories.putIfAbsent(segment, SimpleDirectoryInfo(segment))
                else -> files[segment] = DocumentInfo(
                    name = segment,
                    contentType = contentTypeOf(segment),
                    contentLength = entry.size,
                    lastModifiedTime = entry.lastModifiedTime,
                )
            }
        }

        val children = (directories.values + files.values).sorted()

        return when {
            children.isEmpty() && !hasMarker && location != Location.empty() -> notFoundError("Directory not found")
            else -> DirectoryInfo(name = location.getSimpleName(), files = children).asSuccess()
        }
    }

    private fun findFile(location: Location, key: String): Result<out FileDetails, ErrorResponse> {
        val cached = index.findObject(repository, key)
            ?: return notFoundError("File not found: $location")

        return toDocumentInfo(location, key, cached).asSuccess()
    }

    private fun toDocumentInfo(location: Location, key: String, obj: ListedObject): DocumentInfo =
        DocumentInfo(
            name = location.getSimpleName(),
            contentType = contentTypeOf(key),
            contentLength = obj.size,
            lastModifiedTime = obj.lastModifiedTime,
        )

    private fun recordLookup(key: String, prefix: String, details: FileDetails): FileDetails =
        details.also {
            when (it) {
                is DocumentInfo -> index.upsert(repository, ListedObject(key, it.contentLength, it.lastModifiedTime))
                is DirectoryInfo -> seed(prefix, it)
                else -> Unit
            }
        }

    private fun seed(prefix: String, details: FileDetails): FileDetails =
        details.also {
            when (it) {
                is DirectoryInfo -> {
                    it.files.forEach { child ->
                        when (child) {
                            is DocumentInfo -> index.upsert(repository, ListedObject(childKey(prefix, child.name), child.contentLength, child.lastModifiedTime))
                            is SimpleDirectoryInfo -> index.upsert(repository, ListedObject(childKey(prefix, child.name) + "/", 0, null))
                            else -> Unit
                        }
                    }
                    seededDirectories.add(prefix)
                }
                else -> Unit
            }
        }

    private fun recordPut(location: Location) {
        val details = delegate.getFileDetails(location).orNull() as? DocumentInfo
        val lastModified = delegate.getLastModifiedTime(location).orNull()?.toInstant() ?: clock.instant()

        index.upsert(
            repository = repository,
            obj = ListedObject(
                key = location.toString(),
                size = details?.contentLength ?: UNKNOWN_LENGTH,
                lastModifiedTime = details?.lastModifiedTime ?: lastModified,
            )
        )
    }

    private fun childKey(prefix: String, name: String): String =
        "$prefix$name"

    private fun directoryPrefix(location: Location): String =
        when {
            location == Location.empty() -> ""
            else -> "${location}/"
        }

    private fun contentTypeOf(key: String): ContentType =
        key.substringAfterLast('/')
            .substringAfterLast('.', "")
            .let { extension -> ContentType.contentTypeByExtension(extension) ?: APPLICATION_OCTET_STREAM }

    override fun getLogger(): Logger =
        journalist.logger

}
