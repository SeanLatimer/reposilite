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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class S3KeyEncoderTest {

    @Test
    fun `should keep safe keys unchanged`() {
        val key = "releases/com/example/lib/1.0.0/lib-1.0.0-sources.jar"

        assertThat(S3KeyEncoder.encode(key)).isEqualTo(key)
        assertThat(S3KeyEncoder.decode(key)).isEqualTo(key)
    }

    @Test
    fun `should encode plus characters`() {
        val key = "releases/com/example/lib/1.0.1+1.21.1/lib-1.0.1+1.21.1.pom"

        assertThat(S3KeyEncoder.encode(key)).isEqualTo("releases/com/example/lib/1.0.1~2b1.21.1/lib-1.0.1~2b1.21.1.pom")
        assertThat(S3KeyEncoder.decode(S3KeyEncoder.encode(key))).isEqualTo(key)
    }

    @Test
    fun `should encode the escape character itself`() {
        val key = "a~b"

        assertThat(S3KeyEncoder.encode(key)).isEqualTo("a~7eb")
        assertThat(S3KeyEncoder.decode("a~7eb")).isEqualTo(key)
    }

    @Test
    fun `should encode spaces and non-ascii characters via utf-8 bytes`() {
        val key = "prefix/информация/file name.txt"

        val encoded = S3KeyEncoder.encode(key)

        assertThat(encoded).doesNotContain(" ").doesNotContain("информация")
        assertThat(encoded).isEqualTo("prefix/~d0~b8~d0~bd~d1~84~d0~be~d1~80~d0~bc~d0~b0~d1~86~d0~b8~d1~8f/file~20name.txt")
        assertThat(S3KeyEncoder.decode(encoded)).isEqualTo(key)
    }

    @Test
    fun `should keep encoded prefixes stable for partial list requests`() {
        val prefix = "releases/com/example/lib/1.0.1+1.21.1"
        val key = "$prefix/lib-1.0.1+1.21.1.pom"

        assertThat(S3KeyEncoder.encode(key)).startsWith(S3KeyEncoder.encode(prefix))
    }

    @Test
    fun `should return malformed keys unchanged`() {
        assertThat(S3KeyEncoder.decode("a~zz")).isEqualTo("a~zz")
        assertThat(S3KeyEncoder.decode("a~2")).isEqualTo("a~2")
        assertThat(S3KeyEncoder.decode("a~d0~b")).isEqualTo("a~d0~b")
    }

}
