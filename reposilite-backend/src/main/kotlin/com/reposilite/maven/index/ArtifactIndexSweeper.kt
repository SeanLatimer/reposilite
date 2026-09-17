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
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService

internal class ArtifactIndexSweeper(
    private val journalist: Journalist,
    private val ioService: ExecutorService,
    private val clock: Clock,
    private val indexes: () -> Collection<IndexedStorageProvider>,
) {

    private val running = ConcurrentHashMap.newKeySet<String>()

    fun tick() {
        val now = clock.instant()

        indexes().forEach { index ->
            val lastReconciled = index.lastReconciledAt()
            val due = lastReconciled == null || Duration.between(lastReconciled, now).seconds >= index.reconciliationIntervalSeconds()

            when {
                !due -> Unit
                !running.add(index.repository) -> Unit
                else -> ioService.execute {
                    try {
                        index.reconcile()
                            .peek { journalist.logger.info("INDEX | Scheduled reconciliation of '${index.repository}' completed ($it objects)") }
                            .onError { journalist.logger.warn("INDEX | Scheduled reconciliation of '${index.repository}' failed due to ${it.message}") }
                    } finally {
                        running.remove(index.repository)
                    }
                }
            }
        }
    }

}
