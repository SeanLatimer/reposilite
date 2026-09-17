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

package com.reposilite.storage

import com.reposilite.journalist.backend.InMemoryLogger
import com.reposilite.status.FailureFacade
import com.reposilite.storage.api.toLocation
import com.reposilite.storage.s3.S3Signer
import com.reposilite.storage.s3.S3StorageProviderSettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import panda.std.ResultAssertions.assertOk
import java.io.File
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@Testcontainers
internal class S3PresignedDownloadUrlIntegrationTest {

    private val client = HttpClient.newHttpClient()

    @TempDir
    lateinit var rootDirectory: File

    @Container
    val floci: GenericContainer<*> = GenericContainer(DockerImageName.parse("floci/floci:latest"))
        .withExposedPorts(4566)
        .waitingFor(Wait.forListeningPort())

    private lateinit var provider: StorageProvider

    @BeforeEach
    fun setup() {
        val logger = InMemoryLogger()
        provider = createProvider(
            repository = "test-repository",
            settings = S3StorageProviderSettings(
                bucketName = "test-repository",
                endpoint = flociEndpoint(),
                accessKey = "test",
                secretKey = "test",
                region = "us-east-1",
                signer = S3Signer.LEGACY_V4,
                downloadRedirect = DownloadRedirectMode.ALWAYS,
                downloadRedirectValiditySeconds = 120,
            )
        )
    }

    @Test
    fun `should generate presigned url that serves the stored object`() {
        // given: an object stored in the bucket
        val location = "/com/example/lib.jar".toLocation()
        assertOk(provider.putFile(location, "presigned-content".toByteArray().inputStream()))

        // when: a download URL is requested for the stored object
        val url = assertOk(provider.toRedirectProvider().getDownloadUrl(location))

        // then: the URL points to the bucket key and carries a signature with the configured validity
        assertThat(url.toString()).contains("/test-repository/com/example/lib.jar")
        assertThat(url.toString()).contains("X-Amz-Signature")
        assertThat(url.toString()).contains("X-Amz-Expires=120")

        // and: the URL serves the stored content without any further authentication
        val response = client.send(
            HttpRequest.newBuilder(url).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        )

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).isEqualTo("presigned-content")
    }

    @Test
    fun `should scope presigned urls by key prefix and repository namespace`() {
        // given: a repository sharing a bucket under a prefixed namespace
        val provider = createProvider(
            repository = "alpha",
            settings = S3StorageProviderSettings(
                bucketName = "shared-bucket",
                endpoint = flociEndpoint(),
                accessKey = "test",
                secretKey = "test",
                region = "us-east-1",
                prefix = "reposilite",
                sharedBucket = true,
                downloadRedirect = DownloadRedirectMode.ALWAYS,
            )
        )
        val location = "/com/example/lib.jar".toLocation()
        assertOk(provider.putFile(location, "namespaced-content".toByteArray().inputStream()))

        // when: a download URL is requested
        val url = assertOk(provider.toRedirectProvider().getDownloadUrl(location))

        // then: the URL targets the namespaced key
        assertThat(url.toString()).contains("/shared-bucket/reposilite/alpha/com/example/lib.jar")

        val response = client.send(
            HttpRequest.newBuilder(url).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        )

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).isEqualTo("namespaced-content")
    }

    private fun flociEndpoint(): String =
        "http://${floci.host}:${floci.getMappedPort(4566)}"

    private fun StorageProvider.toRedirectProvider(): DownloadRedirectProvider =
        this as DownloadRedirectProvider

    private fun createProvider(repository: String, settings: S3StorageProviderSettings): StorageProvider {
        val logger = InMemoryLogger()
        return StorageFacade().createStorageProvider(
            journalist = logger,
            failureFacade = FailureFacade(logger),
            workingDirectory = rootDirectory.toPath(),
            repository = repository,
            storageSettings = settings,
        )!!
    }

}
