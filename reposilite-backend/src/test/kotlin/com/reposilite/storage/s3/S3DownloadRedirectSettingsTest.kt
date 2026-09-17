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

package com.reposilite.storage.s3

import com.reposilite.ReposiliteObjectMapper.DEFAULT_OBJECT_MAPPER
import com.reposilite.storage.DownloadRedirectMode.ALWAYS
import com.reposilite.storage.DownloadRedirectMode.OFF
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class S3DownloadRedirectSettingsTest {

    @Test
    fun `should default to disabled redirects`() {
        val settings = S3StorageProviderSettings()

        assertThat(settings.downloadRedirect).isEqualTo(OFF)
        assertThat(settings.downloadRedirectValiditySeconds).isEqualTo(300)
    }

    @Test
    fun `should deserialize redirects from json`() {
        val settings = DEFAULT_OBJECT_MAPPER.readValue(
            """{ "type": "s3", "bucketName": "bucket", "downloadRedirect": "ALWAYS", "downloadRedirectValiditySeconds": 60 }""",
            S3StorageProviderSettings::class.java
        )

        assertThat(settings.downloadRedirect).isEqualTo(ALWAYS)
        assertThat(settings.downloadRedirectValiditySeconds).isEqualTo(60)
    }

    @Test
    fun `should keep disabled redirects when json omits redirect settings`() {
        val settings = DEFAULT_OBJECT_MAPPER.readValue(
            """{ "type": "s3", "bucketName": "bucket" }""",
            S3StorageProviderSettings::class.java
        )

        assertThat(settings.downloadRedirect).isEqualTo(OFF)
    }

}
