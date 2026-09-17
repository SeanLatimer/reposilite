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

package com.reposilite.maven

import com.reposilite.shared.ErrorResponse
import com.reposilite.storage.api.Location
import panda.std.Result

internal fun MavenFacade.removeDeprecatedSnapshotBuilds(repository: Repository, gav: Location): Result<Int, ErrorResponse> {
    val artifactDirectory = gav.locationBeforeLast("/")

    return findMetadata(repository, artifactDirectory)
        .merge(repository.storageProvider.getFiles(artifactDirectory)) { metadata, files -> metadata to files }
        .map { (metadata, files) ->
            val snapshotToPreserve = metadata.versioning?.snapshot?.timestamp ?: return@map 0
            val artifactId = metadata.artifactId ?: return@map 0

            files
                .filter { it.locationAfterLast("/").toString().startsWith(artifactId) }
                .filterNot { it.toString().contains(snapshotToPreserve) }
                .map { repository.storageProvider.removeFile(it) }
                .count()
        }
}
