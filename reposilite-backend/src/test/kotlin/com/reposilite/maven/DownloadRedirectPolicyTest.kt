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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class DownloadRedirectPolicyTest {

    @Test
    fun `should accept curl`() {
        assertThat(DownloadRedirectPolicy.accepts("curl/8.4.0")).isTrue
    }

    @Test
    fun `should accept wget`() {
        assertThat(DownloadRedirectPolicy.accepts("Wget/1.21.3 (linux-gnu)")).isTrue
    }

    @Test
    fun `should accept browsers`() {
        assertThat(DownloadRedirectPolicy.accepts("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")).isTrue
        assertThat(DownloadRedirectPolicy.accepts("Mozilla/5.0 (X11; Linux x86_64; rv:127.0) Gecko/20100101 Firefox/127.0")).isTrue
    }

    @Test
    fun `should accept case-insensitive user agents`() {
        assertThat(DownloadRedirectPolicy.accepts("CURL/8.4.0")).isTrue
        assertThat(DownloadRedirectPolicy.accepts("WGET/1.21.3")).isTrue
    }

    @Test
    fun `should reject build tools with unverified redirect support`() {
        assertThat(DownloadRedirectPolicy.accepts("Apache-Maven/3.9.9 (Java 17.0.7; Windows 11 10.0)")).isFalse
        assertThat(DownloadRedirectPolicy.accepts("Gradle/8.9 (Linux 6.5.0; amd64; 17.0.7)")).isFalse
        assertThat(DownloadRedirectPolicy.accepts("Apache Ivy/2.5.2")).isFalse
    }

    @Test
    fun `should reject unknown and missing user agents`() {
        assertThat(DownloadRedirectPolicy.accepts("JGit/6.9.0")).isFalse
        assertThat(DownloadRedirectPolicy.accepts("SomeRandomClient/1.0")).isFalse
        assertThat(DownloadRedirectPolicy.accepts("")).isFalse
        assertThat(DownloadRedirectPolicy.accepts(null)).isFalse
    }

}
