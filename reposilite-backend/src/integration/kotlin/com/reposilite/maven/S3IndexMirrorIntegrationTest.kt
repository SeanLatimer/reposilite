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

@file:Suppress("FunctionName")

package com.reposilite.maven

import com.reposilite.RecommendedLocalSpecificationJunitExtension
import com.reposilite.ReposiliteSpecification
import com.reposilite.configuration.shared.SharedConfigurationFacade
import com.reposilite.maven.application.MavenSettings
import com.reposilite.maven.application.MirroredRepositorySettings
import com.reposilite.maven.application.RepositorySettings
import com.reposilite.maven.index.IndexedStorageProvider
import com.reposilite.storage.s3.S3IndexSettings
import com.reposilite.storage.s3.S3Signer
import com.reposilite.storage.s3.S3StorageProviderSettings
import kong.unirest.core.Unirest.get
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

@ExtendWith(RecommendedLocalSpecificationJunitExtension::class)
@Testcontainers
internal class LocalS3IndexMirrorIntegrationTest : S3IndexMirrorIntegrationTest() {

    @Container
    private val floci: GenericContainer<*> = GenericContainer(DockerImageName.parse("floci/floci:latest"))
        .withExposedPorts(4566)
        .waitingFor(Wait.forListeningPort())

    override fun indexFloci(): GenericContainer<*> =
        floci

}

@Testcontainers
internal abstract class S3IndexMirrorIntegrationTest : ReposiliteSpecification() {

    protected abstract fun indexFloci(): GenericContainer<*>

    override fun overrideSharedConfiguration(sharedConfigurationFacade: SharedConfigurationFacade) {
        val endpoint = "http://${indexFloci().host}:${indexFloci().getMappedPort(4566)}"

        sharedConfigurationFacade.getDomainSettings<MavenSettings>().update { old ->
            old.copy(
                repositories = old.repositories.associateBy { it.id }.toMutableMap()
                    .also { repositories ->
                        repositories["proxied"] = RepositorySettings(
                            id = "proxied",
                            redeployment = true,
                            storageProvider = S3StorageProviderSettings(
                                bucketName = "index-bucket",
                                endpoint = endpoint,
                                accessKey = "test",
                                secretKey = "test",
                                region = "us-east-1",
                                signer = S3Signer.LEGACY_V4,
                                sharedBucket = true,
                                indexSettings = S3IndexSettings(enabled = true, serveMetadata = true),
                            ),
                            proxied = listOf(MirroredRepositorySettings(reference = "releases", store = true, allowedExtensions = listOf("<none>", ".jar"))),
                        )
                    }
                    .values.toList()
            )
        }
    }

    @Test
    fun `should fetch mirror-resolved artifact through the indexed provider`() {
        // given: an artifact deployed to the upstream (local filesystem) repository only
        val (_, gav, file, content) = useDocument("releases", "com/example", "lib.jar", "mirror-content", true)

        // when: the artifact is requested through a proxied repository whose S3 storage is indexed
        val response = get("$base/proxied/$gav/$file").asString()

        // then: the mirror-resolved artifact is streamed through the indexed provider
        assertThat(response.isSuccess).isTrue
        assertThat(response.body).isEqualTo(content)
    }

    @Test
    fun `should index mirror-stored artifacts without reconciliation`() {
        // given: an artifact fetched through the proxied repository (mirror fetch + store)
        val (_, gav, file, content) = useDocument("releases", "com/example", "cached.jar", "cached-content", true)
        assertThat(get("$base/proxied/$gav/$file").asString().isSuccess).isTrue

        // when: the proxied repository's listing is browsed
        val provider = mavenFacade.getRepository("proxied")!!.storageProvider as IndexedStorageProvider
        val listing = get("$base/api/maven/details/proxied/$gav").asString()

        // then: the mirror-stored artifact is listed through the index, without any reconciliation
        assertThat(listing.body).contains(file)
        assertThat(provider.lastReconciledAt()).isNull()
    }

    @Test
    fun `should fall through to upstream listing for locally missing directories`() {
        // given: an upstream repository with content that the proxied repository has never stored
        useDocument("releases", "com/example", "upstream.jar", "upstream-content", true)

        // when: a locally missing directory of the proxied repository is browsed
        val response = get("$base/api/maven/details/proxied/com/example").asString()

        // then: the upstream listing is served (no negative caching in the index)
        assertThat(response.isSuccess)
            .withFailMessage("status=${response.status} body=${response.body.take(300)}")
            .isTrue
        assertThat(response.body).contains("upstream.jar")
    }

}
