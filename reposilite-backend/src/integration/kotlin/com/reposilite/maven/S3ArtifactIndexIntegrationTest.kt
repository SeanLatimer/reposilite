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
import com.reposilite.console.ConsoleFacade
import com.reposilite.maven.application.MavenSettings
import com.reposilite.storage.s3.S3IndexSettings
import com.reposilite.storage.s3.S3Signer
import com.reposilite.storage.s3.S3StorageProviderSettings
import kong.unirest.core.Unirest.delete
import kong.unirest.core.Unirest.get
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import panda.std.ResultAssertions.assertOk
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import java.net.URI

@ExtendWith(RecommendedLocalSpecificationJunitExtension::class)
@Testcontainers
internal class LocalS3ArtifactIndexIntegrationTest : S3ArtifactIndexIntegrationTest() {

    @Container
    private val floci: GenericContainer<*> = GenericContainer(DockerImageName.parse("floci/floci:latest"))
        .withExposedPorts(4566)
        .waitingFor(Wait.forListeningPort())

    override fun floci(): GenericContainer<*> =
        floci

}

@Testcontainers
internal abstract class S3ArtifactIndexIntegrationTest : ReposiliteSpecification() {

    private val consoleFacade by lazy { useFacade<ConsoleFacade>() }

    private val rawS3Client by lazy {
        S3Client.builder()
            .endpointOverride(URI.create("http://${floci().host}:${floci().getMappedPort(4566)}"))
            .region(Region.of("us-east-1"))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build()
    }

    protected abstract fun floci(): GenericContainer<*>

    override fun overrideSharedConfiguration(sharedConfigurationFacade: SharedConfigurationFacade) {
        val endpoint = "http://${floci().host}:${floci().getMappedPort(4566)}"

        sharedConfigurationFacade.getDomainSettings<MavenSettings>().update { old ->
            old.copy(
                repositories = old.repositories.associateBy { it.id }.toMutableMap()
                    .also { repositories ->
                        repositories["releases"] = repositories.getValue("releases").copy(
                            storageProvider = S3StorageProviderSettings(
                                bucketName = "index-bucket",
                                endpoint = endpoint,
                                accessKey = "test",
                                secretKey = "test",
                                region = "us-east-1",
                                signer = S3Signer.LEGACY_V4,
                                sharedBucket = true,
                                downloadRedirect = com.reposilite.storage.DownloadRedirectMode.ALWAYS,
                                indexSettings = S3IndexSettings(
                                    enabled = true,
                                    serveMetadata = true,
                                    reconciliationIntervalSeconds = 60 * 60 * 24,
                                )
                            )
                        )
                    }
                    .values.toList()
            )
        }
    }

    @Test
    fun `should serve deployed artifacts through the index`() {
        // given: an artifact deployed to an indexed repository
        val (_, gav, file, _) = useDocument("releases", "com/example", "lib.jar", "content", true)

        // when: the directory is browsed
        val response = get("$base/api/maven/details/releases/$gav").asString()

        // then: the deployed artifact is visible
        assertThat(response.isSuccess).isTrue
        assertThat(response.body).contains(file)
    }

    @Test
    fun `should hide out-of-band changes until reconciliation`() {
        // given: a seeded repository and an object written directly to the bucket
        useDocument("releases", "com/example", "lib.jar", "content", true)
        get("$base/api/maven/details/releases/com/example").asString()

        rawS3Client.putObject(
            { it.bucket("index-bucket"); it.key("releases/com/example/out-of-band.jar") },
            RequestBody.fromBytes(ByteArray(0))
        )

        // when: the directory is browsed before reconciliation
        val before = get("$base/api/maven/details/releases/com/example").asString()

        // then: the out-of-band object is not visible yet
        assertThat(before.body).doesNotContain("out-of-band.jar")

        // when: the index is reconciled through the console command
        val execution = consoleFacade.executeCommand("s3index releases")
        assertOk(execution)

        // then: the out-of-band object becomes visible
        val after = get("$base/api/maven/details/releases/com/example").asString()
        assertThat(after.body).contains("out-of-band.jar")
    }

    @Test
    fun `should redirect downloads of indexed repositories to presigned urls`() {
        // given: an artifact in a repository with both the listing index and download redirects enabled
        val (_, gav, file, content) = useDocument("releases", "com/example", "lib.jar", "content", true)

        // when: a redirect-capable client requests the artifact
        val client = java.net.http.HttpClient.newBuilder()
            .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
            .build()

        val response = client.send(
            java.net.http.HttpRequest.newBuilder(URI.create("$base/releases/$gav/$file"))
                .header("User-Agent", "curl/8.4.0")
                .GET()
                .build(),
            java.net.http.HttpResponse.BodyHandlers.ofString()
        )

        // then: the download is redirected to the presigned URL
        assertThat(response.statusCode()).isEqualTo(302)
        assertThat(response.headers().firstValue("Location").orElseThrow()).contains("X-Amz-Signature")

        // and: browsing still serves the directory through the index
        val details = get("$base/api/maven/details/releases/$gav").asString()
        assertThat(details.body).contains(file)
    }

    @Test
    fun `should prune deleted artifacts from the index immediately`() {
        // given: two deployed artifacts and a seeded directory
        useDocument("releases", "com/example", "lib.jar", "content", true)
        useDocument("releases", "com/example", "other.jar", "content", true)
        get("$base/api/maven/details/releases/com/example").asString()

        // when: one artifact is deleted through Reposilite
        val (name, secret) = useDefaultManagementToken()
        val deletion = delete("$base/releases/com/example/other.jar")
            .basicAuth(name, secret)
            .asEmpty()

        assertThat(deletion.isSuccess).isTrue

        // then: it disappears from the listing without reconciliation
        val response = get("$base/api/maven/details/releases/com/example").asString()
        assertThat(response.body).contains("lib.jar")
        assertThat(response.body).doesNotContain("other.jar")
    }

}
