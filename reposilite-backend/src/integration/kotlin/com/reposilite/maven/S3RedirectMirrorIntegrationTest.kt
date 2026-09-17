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
import com.reposilite.storage.DownloadRedirectMode
import com.reposilite.storage.api.toLocation
import com.reposilite.storage.s3.S3Signer
import com.reposilite.storage.s3.S3StorageProviderSettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@ExtendWith(RecommendedLocalSpecificationJunitExtension::class)
@Testcontainers
internal class LocalS3RedirectMirrorIntegrationTest : S3RedirectMirrorIntegrationTest() {

    @Container
    private val floci: GenericContainer<*> = GenericContainer(DockerImageName.parse("floci/floci:latest"))
        .withExposedPorts(4566)
        .waitingFor(Wait.forListeningPort())

    override fun redirectFloci(): GenericContainer<*> =
        floci

}

@Testcontainers
internal abstract class S3RedirectMirrorIntegrationTest : ReposiliteSpecification() {

    private val client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    protected abstract fun redirectFloci(): GenericContainer<*>

    override fun overrideSharedConfiguration(sharedConfigurationFacade: SharedConfigurationFacade) {
        val endpoint = "http://${redirectFloci().host}:${redirectFloci().getMappedPort(4566)}"

        fun redirectSettings() = S3StorageProviderSettings(
            bucketName = "redirect-bucket",
            endpoint = endpoint,
            accessKey = "test",
            secretKey = "test",
            region = "us-east-1",
            signer = S3Signer.LEGACY_V4,
            sharedBucket = true,
            downloadRedirect = DownloadRedirectMode.ALWAYS,
        )

        sharedConfigurationFacade.getDomainSettings<MavenSettings>().update { old ->
            old.copy(
                repositories = old.repositories.associateBy { it.id }.toMutableMap()
                    .also { repositories ->
                        repositories["releases"] = repositories.getValue("releases").copy(storageProvider = redirectSettings())
                        repositories["proxied"] = RepositorySettings(
                            id = "proxied",
                            redeployment = true,
                            storageProvider = redirectSettings(),
                            proxied = listOf(MirroredRepositorySettings(reference = "releases", store = true)),
                        )
                    }
                    .values.toList()
            )
        }
    }

    @Test
    fun `should stream mirror-resolved artifact instead of redirecting`() {
        // given: an artifact deployed to the upstream repository only
        val (_, gav, file, content) = useDocument("releases", "com/example", "lib.jar", "mirror-content", true)

        // when: the artifact is requested through a proxied repository backed by S3 with redirects enabled
        val response = client.send(
            HttpRequest.newBuilder(URI.create("$base/proxied/$gav/$file")).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        )

        // then: the mirror-resolved artifact is streamed, not redirected
        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).isEqualTo(content)
    }

    @Test
    fun `should redirect mirror-cached artifacts on subsequent requests`() {
        // given: an artifact already cached in the proxied repository's S3 storage by a previous mirror fetch
        val (_, gav, file, content) = useDocument("releases", "com/example", "cached.jar", "cached-content", true)
        client.send(
            HttpRequest.newBuilder(URI.create("$base/proxied/$gav/$file")).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        )

        // when: the cached artifact is requested again
        val response = client.send(
            HttpRequest.newBuilder(URI.create("$base/proxied/$gav/$file"))
                .header("User-Agent", "curl/8.4.0")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )

        // then: the locally stored copy is redirected to a presigned URL under the proxied repository's namespace
        assertThat(response.statusCode()).isEqualTo(302)
        val location = response.headers().firstValue("Location").orElseThrow()
        assertThat(location).contains("/redirect-bucket/proxied/$gav/$file")

        // and: the presigned URL serves the original content
        val followed = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(location)).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        )
        assertThat(followed.statusCode()).isEqualTo(200)
        assertThat(followed.body()).isEqualTo(content)
    }

    @Test
    fun `should refresh mirrored snapshot metadata instead of redirecting stale data`() {
        // given: a snapshot metadata file cached through the proxied repository
        val gav = "com/example/lib/1.0-SNAPSHOT"
        val initialMetadata = """
            <metadata>
              <groupId>com.example</groupId>
              <artifactId>lib</artifactId>
              <version>1.0-SNAPSHOT</version>
              <versioning>
                <snapshot>
                  <timestamp>20260917.120000</timestamp>
                  <buildNumber>1</buildNumber>
                </snapshot>
              </versioning>
            </metadata>
        """.trimIndent()
        val updatedMetadata = initialMetadata.replace("120000", "120001")
        useDocument("releases", gav, "maven-metadata.xml", initialMetadata, true)
        val location = "$gav/maven-metadata.xml".toLocation()
        val proxiedStorage = mavenFacade.getRepository("proxied")!!.storageProvider

        val initial = client.send(
            HttpRequest.newBuilder(URI.create("$base/proxied/$gav/maven-metadata.xml")).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        )
        assertThat(initial.body()).isEqualTo(initialMetadata)
        assertThat(proxiedStorage.exists(location)).isTrue

        // when: upstream receives newer snapshot metadata
        useDocument("releases", gav, "maven-metadata.xml", updatedMetadata, true)
        val refreshed = client.send(
            HttpRequest.newBuilder(URI.create("$base/proxied/$gav/maven-metadata.xml")).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        )

        // then: the metadata freshness policy streams and caches the updated copy instead of redirecting stale data
        assertThat(refreshed.statusCode()).isEqualTo(200)
        assertThat(refreshed.headers().firstValue("Location")).isEmpty
        assertThat(refreshed.body()).isEqualTo(updatedMetadata)
    }

    @Test
    fun `should not fetch mirror artifacts for head probes`() {
        // given: an artifact available only from the upstream repository
        val (_, gav, file, content) = useDocument("releases", "com/example", "probe.jar", "probe-content", true)
        val location = "$gav/$file".toLocation()
        val proxiedStorage = mavenFacade.getRepository("proxied")!!.storageProvider

        // when: a client probes the uncached artifact through the proxy
        val head = client.send(
            HttpRequest.newBuilder(URI.create("$base/proxied/$gav/$file"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.discarding()
        )

        // then: metadata is returned without fetching and storing the artifact locally
        assertThat(head.statusCode()).isEqualTo(200)
        assertThat(head.headers().firstValue("Content-Length")).hasValue(content.length.toString())
        assertThat(proxiedStorage.exists(location)).isFalse

        // and: the subsequent GET performs the regular mirror fetch
        val get = client.send(
            HttpRequest.newBuilder(URI.create("$base/proxied/$gav/$file")).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        )
        assertThat(get.statusCode()).isEqualTo(200)
        assertThat(get.body()).isEqualTo(content)
        assertThat(proxiedStorage.exists(location)).isTrue
    }

}
