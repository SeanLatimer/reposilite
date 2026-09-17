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

import com.reposilite.maven.api.MirrorStoredEvent
import com.reposilite.plugin.api.EventListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService

internal class MirroredSnapshotRetentionListener(
    private val mavenFacade: MavenFacade,
    private val executor: ExecutorService,
) : EventListener<MirrorStoredEvent> {

    private val scheduled = ConcurrentHashMap.newKeySet<String>()

    override fun onCall(event: MirrorStoredEvent) {
        val repository = event.repository
            .takeUnless { it.preserveSnapshots }
            ?: return

        val gav = event.gav
            .takeIf { it.toString().endsWith("-SNAPSHOT/maven-metadata.xml") }
            ?: return

        val key = "${repository.name}/$gav"
        if (!scheduled.add(key)) {
            return
        }

        executor.execute {
            try {
                mavenFacade.removeDeprecatedSnapshotBuilds(repository, gav)
                    .peek { mavenFacade.logger.info("MIRROR | Snapshot retention removed $it deprecated file(s) from ${repository.name}") }
                    .onError { mavenFacade.logger.warn("MIRROR | Snapshot retention failed in ${repository.name} due to ${it.message}") }
            } finally {
                scheduled.remove(key)
            }
        }
    }

}
