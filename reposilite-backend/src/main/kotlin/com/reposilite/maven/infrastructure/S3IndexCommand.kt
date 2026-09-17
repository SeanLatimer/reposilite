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

package com.reposilite.maven.infrastructure

import com.reposilite.console.CommandContext
import com.reposilite.console.CommandStatus.FAILED
import com.reposilite.console.api.ReposiliteCommand
import com.reposilite.maven.MavenFacade
import com.reposilite.maven.index.IndexedStorageProvider
import picocli.CommandLine.Command
import picocli.CommandLine.Parameters

@Command(name = "s3index", description = ["Reconcile the S3 listing index of a repository with its bucket. Usage: s3index <repository|all>."])
internal class S3IndexCommand(private val mavenFacade: MavenFacade) : ReposiliteCommand {

    @Parameters(index = "0", paramLabel = "<repository>", description = ["Repository to reconcile, or 'all' for every indexed repository"])
    private lateinit var repository: String

    override fun execute(context: CommandContext) {
        val indexes = mavenFacade.getRepositories()
            .filter { repository == "all" || it.name == repository }
            .mapNotNull { it.storageProvider as? IndexedStorageProvider }

        when {
            indexes.isEmpty() -> context.fail("No indexed repository named '$repository' found")
            else -> indexes.forEach { index ->
                index.reconcile()
                    .peek { context.append("Repository '${index.repository}' reconciled ($it objects indexed).") }
                    .onError { context.fail("Cannot reconcile '${index.repository}' due to ${it.message}") }
            }
        }
    }

    private fun CommandContext.fail(message: String) {
        append(message)
        status = FAILED
    }

}
