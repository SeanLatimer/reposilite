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
import com.reposilite.maven.application.RepositorySettings
import com.reposilite.storage.DownloadRedirectMode
import com.reposilite.storage.DownloadRedirectMode.ALWAYS
import com.reposilite.storage.DownloadRedirectMode.AUTO
import com.reposilite.storage.s3.S3Signer
import com.reposilite.storage.s3.S3StorageProviderSettings
import com.reposilite.token.RoutePermission
import io.javalin.http.HttpStatus.UNAUTHORIZED
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
import java.util.Base64

@ExtendWith(RecommendedLocalSpecificationJunitExtension::class)
@Testcontainers
internal class LocalS3DownloadRedirectIntegrationTest : S3DownloadRedirectIntegrationTest() {

    @Container
    private val floci: GenericContainer<*> = GenericContainer(DockerImageName.parse("floci/floci:latest"))
        .withExposedPorts(4566)
        .waitingFor(Wait.forListeningPort())

    override fun redirectFloci(): GenericContainer<*> =
        floci

}

@Testcontainers
internal abstract class S3DownloadRedirectIntegrationTest : ReposiliteSpecification() {

    private val client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    protected abstract fun redirectFloci(): GenericContainer<*>

    override fun overrideSharedConfiguration(sharedConfigurationFacade: SharedConfigurationFacade) {
        val endpoint = "http://${redirectFloci().host}:${redirectFloci().getMappedPort(4566)}"

        fun redirectSettings(mode: DownloadRedirectMode) = S3StorageProviderSettings(
            bucketName = "redirect-bucket",
            endpoint = endpoint,
            accessKey = "test",
            secretKey = "test",
            region = "us-east-1",
            signer = S3Signer.LEGACY_V4,
            sharedBucket = true,
            downloadRedirect = mode,
        )

        sharedConfigurationFacade.getDomainSettings<MavenSettings>().update { old ->
            old.copy(
                repositories = old.repositories.associateBy { it.id }.toMutableMap()
                    .also { repositories ->
                        repositories["releases"] = repositories.getValue("releases").copy(storageProvider = redirectSettings(ALWAYS))
                        repositories["private"] = repositories.getValue("private").copy(storageProvider = redirectSettings(ALWAYS))
                        repositories["redirect-auto"] = RepositorySettings(
                            id = "redirect-auto",
                            redeployment = true,
                            storageProvider = redirectSettings(AUTO)
                        )
                    }
                    .values.toList()
            )
        }
    }

    @Test
    fun `should redirect downloads to presigned url when redirects are always enabled`() {
        // given: a document deployed to a repository with ALWAYS redirect mode
        val (repository, gav, file, content) = useDocument("releases", "com/example", "lib.jar", "presigned-content", true)

        // when: a redirect-capable client requests the document
        val response = get("$base/$repository/$gav/$file", userAgent = "curl/8.4.0")

        // then: the response is a redirect to the presigned bucket key
        assertThat(response.statusCode()).isEqualTo(302)
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store")

        val location = response.headers().firstValue("Location").orElseThrow()
        assertThat(location).contains("/redirect-bucket/$repository/$gav/$file")
        assertThat(location).contains("X-Amz-Signature")
        assertThat(location).contains("X-Amz-Expires=300")

        // and: the presigned URL serves the original content without further authentication
        assertThat(follow(location).body()).isEqualTo(content)
    }

    @Test
    fun `should redirect only capable clients in auto mode`() {
        // given: a document deployed to a repository with AUTO redirect mode
        val (repository, gav, file, content) = useDocument("redirect-auto", "com/example", "auto.jar", "auto-content", true)

        // when: a browser requests the document
        val browser = get("$base/$repository/$gav/$file", userAgent = "Mozilla/5.0 (X11; Linux x86_64; rv:127.0) Gecko/20100101 Firefox/127.0")

        // then: the browser is redirected
        assertThat(browser.statusCode()).isEqualTo(302)

        // when: a known build tool or an unknown client requests the document
        val maven = get("$base/$repository/$gav/$file", userAgent = "Apache-Maven/3.9.9 (Java 17.0.7; Windows 11 10.0)")
        val ivy = get("$base/$repository/$gav/$file", userAgent = "Apache Ivy/2.5.2")
        val unknown = get("$base/$repository/$gav/$file", userAgent = null)

        // then: known clients redirect while unknown and unverified clients stream as usual
        assertThat(maven.statusCode()).isEqualTo(302)
        assertThat(ivy.statusCode()).isEqualTo(200)
        assertThat(ivy.body()).isEqualTo(content)
        assertThat(unknown.statusCode()).isEqualTo(200)
        assertThat(unknown.body()).isEqualTo(content)
    }

    @Test
    fun `should use the global auto redirect user agent allowlist`() {
        // given: AUTO mode configured globally for a custom client only
        reposilite.extensions.facade<SharedConfigurationFacade>().getDomainSettings<MavenSettings>().update {
            it.copy(downloadRedirectUserAgents = listOf("myprobe/"))
        }
        val (repository, gav, file, content) = useDocument("redirect-auto", "com/example", "custom.jar", "custom-content", true)

        // when: the configured client and a default client request the document
        val customClient = get("$base/$repository/$gav/$file", userAgent = "MyProbe/1.0")
        val curl = get("$base/$repository/$gav/$file", userAgent = "curl/8.4.0")

        // then: the configured list replaces the default allowlist
        assertThat(customClient.statusCode()).isEqualTo(302)
        assertThat(curl.statusCode()).isEqualTo(200)
        assertThat(curl.body()).isEqualTo(content)
    }

    @Test
    fun `should stream head requests when redirects are enabled`() {
        // given: a document deployed to a repository with ALWAYS redirect mode
        val (repository, gav, file) = useDocument("releases", "com/example", "head.jar", "head-content", true)

        // when: a client probes the document with HEAD
        val response = client.send(
            HttpRequest.newBuilder(URI.create("$base/$repository/$gav/$file"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .header("User-Agent", "Gradle/8.10")
                .build(),
            HttpResponse.BodyHandlers.discarding(),
        )

        // then: the probe is served by Reposilite rather than redirected to a GET-presigned URL
        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.headers().firstValue("Location")).isEmpty
    }

    @Test
    fun `should stream when redirects are disabled`() {
        // given: a document deployed to a repository without S3 redirects
        val (repository, gav, file, content) = useDocument("snapshots", "com/example", "plain.jar", "plain-content", true)

        // when: any client requests the document
        val response = get("$base/$repository/$gav/$file", userAgent = "curl/8.4.0")

        // then: content is streamed as usual
        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).isEqualTo(content)
    }

    @Test
    fun `should not leak presigned urls for unauthorized private repositories`() {
        // given: a document in a private repository with ALWAYS redirect mode
        val (repository, gav, file) = useDocument("private", "com/example", "secret.jar", "secret-content", true)

        // when: an anonymous client requests the document
        val response = get("$base/$repository/$gav/$file", userAgent = "curl/8.4.0")

        // then: the request is rejected without a redirect
        assertThat(response.statusCode()).isEqualTo(UNAUTHORIZED.code)
        assertThat(response.headers().firstValue("Location")).isEmpty
    }

    @Test
    fun `should redirect authorized clients for private repositories`() {
        // given: a document in a private repository and a token authorized to read it
        val (repository, gav, file, content) = useDocument("private", "com/example", "secret.jar", "secret-content", true)
        val (name, secret) = useAuth("redirect-user", "redirect-secret", routes = mapOf("/private" to RoutePermission.READ))

        // when: an authorized redirect-capable client requests the document
        val response = get("$base/$repository/$gav/$file", userAgent = "curl/8.4.0", basicAuth = name to secret)

        // then: the response is a redirect that serves the original content
        assertThat(response.statusCode()).isEqualTo(302)
        assertThat(follow(response.headers().firstValue("Location").orElseThrow()).body()).isEqualTo(content)
    }

    private fun get(uri: String, userAgent: String?, basicAuth: Pair<String, String>? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create(uri)).GET()

        userAgent?.let { builder.header("User-Agent", it) }
        basicAuth?.let { (name, secret) ->
            val credentials = Base64.getEncoder().encodeToString("$name:$secret".toByteArray())
            builder.header("Authorization", "Basic $credentials")
        }

        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun follow(location: String): HttpResponse<String> =
        client.send(HttpRequest.newBuilder(URI.create(location)).GET().build(), HttpResponse.BodyHandlers.ofString())

}
