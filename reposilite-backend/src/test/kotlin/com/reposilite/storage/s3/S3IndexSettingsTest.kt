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
import com.reposilite.maven.application.MavenSettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

internal class S3IndexSettingsTest {

    @ParameterizedTest
    @ValueSource(strings = [
        """{ "type": "s3", "bucketName": "bucket" }""",
        """{ "type": "s3", "bucketName": "bucket", "indexSettings": null }""",
        """{ "type": "s3", "bucketName": "bucket", "indexSettings": {} }""",
        """{ "type": "s3", "bucketName": "bucket", "indexSettings": { "enabled": false } }""",
    ])
    fun `should load configs without an enabled index as disabled`(json: String) {
        val settings = DEFAULT_OBJECT_MAPPER.readValue(json, S3StorageProviderSettings::class.java)

        assertThat(settings.indexSettings).isEqualTo(S3IndexSettings())
        assertThat(settings.indexSettings.enabled).isFalse
    }

    @Test
    fun `should load enabled index settings`() {
        val settings = DEFAULT_OBJECT_MAPPER.readValue(
            """{ "type": "s3", "indexSettings": { "enabled": true, "serveMetadata": true, "reconciliationIntervalSeconds": 60 } }""",
            S3StorageProviderSettings::class.java
        )

        assertThat(settings.indexSettings).isEqualTo(S3IndexSettings(enabled = true, serveMetadata = true, reconciliationIntervalSeconds = 60))
    }

    @Test
    fun `should describe index settings as a plain object in the settings schema`() {
        // the schema served to the frontend, generated at compile time from @JsonSchema
        val schema = DEFAULT_OBJECT_MAPPER.readTree(javaClass.getResource("/json-schemes/${MavenSettings::class.java.name}"))
        val indexSchema = schema
            .at("/properties/repositories/items/properties/storageProvider/oneOf")
            .first { it.at("/properties/type/const").asText() == "s3" }
            .at("/properties/indexSettings")

        assertThat(indexSchema.get("type").asText()).isEqualTo("object")
        assertThat(indexSchema.get("title").asText()).isEqualTo("Listing Index")
        assertThat(indexSchema.at("/properties/enabled/type").asText()).isEqualTo("boolean")
    }

}
