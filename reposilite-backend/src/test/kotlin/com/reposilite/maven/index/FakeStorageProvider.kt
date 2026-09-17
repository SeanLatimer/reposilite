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

import com.reposilite.shared.ErrorResponse
import com.reposilite.shared.notFoundError
import com.reposilite.storage.FullListingProvider
import com.reposilite.storage.ListedObject
import com.reposilite.storage.StorageProvider
import com.reposilite.storage.api.DirectoryInfo
import com.reposilite.storage.api.DocumentInfo
import com.reposilite.storage.api.FileDetails
import com.reposilite.storage.api.Location
import com.reposilite.storage.api.SimpleDirectoryInfo
import com.reposilite.storage.api.toLocation
import panda.std.Result
import panda.std.asSuccess
import java.io.InputStream
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

internal class FakeStorageProvider : StorageProvider, FullListingProvider {

    val objects = ConcurrentHashMap<String, ListedObject>()
    var listCalls = 0
        private set
    var headCalls = 0
        private set

    fun put(key: String, size: Long = 1, lastModifiedTime: Instant? = null) {
        objects[key] = ListedObject(key, size, lastModifiedTime)
    }

    override fun putFile(location: Location, inputStream: InputStream): Result<Unit, ErrorResponse> {
        objects[location.toString()] = ListedObject(location.toString(), inputStream.available().toLong(), null)
        return Unit.asSuccess()
    }

    override fun getFile(location: Location): Result<InputStream, ErrorResponse> =
        notFoundError("Not supported")

    override fun getFileDetails(location: Location): Result<out FileDetails, ErrorResponse> {
        val key = location.toString()

        objects[key]?.let {
            headCalls++
            return toDocumentInfo(it).asSuccess()
        }

        val prefix = "$key/"
        if (objects.keys.none { it.startsWith(prefix) }) {
            return notFoundError("File not found: $location")
        }

        listCalls++
        return listDirectory(location, prefix)
    }

    private fun listDirectory(location: Location, prefix: String): Result<out FileDetails, ErrorResponse> {
        val directories = LinkedHashMap<String, SimpleDirectoryInfo>()
        val files = LinkedHashMap<String, DocumentInfo>()
        var hasMarker = false

        objects.values.forEach { entry ->
            val relative = entry.key.removePrefix(prefix)
            val segment = relative.substringBefore('/')

            when {
                relative.isEmpty() -> hasMarker = true
                "/" in relative -> directories.putIfAbsent(segment, SimpleDirectoryInfo(segment))
                else -> files[segment] = toDocumentInfo(entry)
            }
        }

        val children = (directories.values + files.values).sorted()

        return when {
            children.isEmpty() && !hasMarker && location != Location.empty() -> notFoundError("Directory not found")
            else -> DirectoryInfo(name = location.getSimpleName(), files = children).asSuccess()
        }
    }

    private fun toDocumentInfo(obj: ListedObject): DocumentInfo =
        DocumentInfo(
            name = obj.key.substringAfterLast('/'),
            contentType = io.javalin.http.ContentType.APPLICATION_OCTET_STREAM,
            contentLength = obj.size,
            lastModifiedTime = obj.lastModifiedTime,
        )

    override fun removeFile(location: Location): Result<Unit, ErrorResponse> {
        val key = location.toString()
        objects.keys.removeAll { it == key || it.startsWith("$key/") }
        return Unit.asSuccess()
    }

    override fun getFiles(location: Location): Result<List<Location>, ErrorResponse> {
        val prefix = "${location}/"

        if (objects.keys.none { it.startsWith(prefix) }) {
            return notFoundError("Directory not found")
        }

        listCalls++
        return objects.keys
            .filter { it.startsWith(prefix) }
            .map { it.substringBefore('/').toLocation() }
            .distinct()
            .asSuccess()
    }

    override fun getLastModifiedTime(location: Location): Result<FileTime, ErrorResponse> =
        objects[location.toString()]?.lastModifiedTime
            ?.let { FileTime.from(it).asSuccess() }
            ?: notFoundError("File not found")

    override fun getFileSize(location: Location): Result<Long, ErrorResponse> =
        objects[location.toString()]?.size?.asSuccess() ?: notFoundError("File not found")

    override fun exists(location: Location): Boolean =
        objects.containsKey(location.toString()) || objects.keys.any { it.startsWith("${location}/") }

    override fun usage(): Result<Long, ErrorResponse> =
        objects.size.toLong().asSuccess()

    override fun canHold(contentLength: Long): Result<Long, ErrorResponse> =
        Long.MAX_VALUE.asSuccess()

    override fun listAllObjects(): Result<List<ListedObject>, ErrorResponse> =
        objects.values.toList().asSuccess()

}
