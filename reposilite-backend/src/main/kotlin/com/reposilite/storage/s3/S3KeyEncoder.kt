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

/**
 * Encodes S3 object keys into a client-safe form.
 *
 * Presigned download URLs sign the exact key, percent-encoded. HTTP clients like Gradle
 * decode characters such as `+` when following redirect Location paths, which invalidates
 * the signature (some providers canonicalize a raw `+` in the path as an encoded space).
 * To keep presigned redirects followable by every client, physical keys only contain
 * characters that survive URL round-trips verbatim.
 *
 * Every byte outside of [SAFE_CHARACTERS] is stored as `~xx`, where `xx` is the lowercase
 * hex representation of the UTF-8 byte (e.g. `+` becomes `~2b`, `~` itself `~7e`).
 * The encoding is injective and prefix-stable: `encode(prefix + key)` starts with `encode(prefix)`.
 */
internal object S3KeyEncoder {

    private const val ESCAPE = '~'
    private const val HEX = "0123456789abcdef"
    private val SAFE_CHARACTERS = ('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('.', '/', '_', '-')

    fun encode(key: String): String =
        buildString {
            for (byte in key.toByteArray(Charsets.UTF_8)) {
                val char = byte.toInt().toChar()

                if (char in SAFE_CHARACTERS) {
                    append(char)
                } else {
                    val value = byte.toInt() and 0xFF
                    append(ESCAPE)
                    append(HEX[value ushr 4])
                    append(HEX[value and 0xF])
                }
            }
        }

    /**
     * Reverses [encode]. Malformed input (raw keys never produced by this encoder)
     * is returned unchanged, so unencoded keys keep resolving until migrated.
     */
    fun decode(key: String): String {
        if (ESCAPE !in key) {
            return key
        }

        return runCatching { key.toByteArray(Charsets.UTF_8).let(::decodeBytes).toString(Charsets.UTF_8) }
            .getOrElse { key }
    }

    private fun decodeBytes(bytes: ByteArray): ByteArray {
        val decoded = ByteArray(bytes.size)
        var length = 0
        var index = 0

        while (index < bytes.size) {
            val byte = bytes[index]

            if (byte == ESCAPE.code.toByte()) {
                require(index + 2 < bytes.size) { "Truncated escape sequence" }
                decoded[length++] = ((hexValue(bytes[index + 1]) shl 4) or hexValue(bytes[index + 2])).toByte()
                index += 3
            } else {
                require(byte.toInt() in 0x21..0x7E) { "Unescaped non-ASCII byte" }
                decoded[length++] = byte
                index += 1
            }
        }

        return decoded.copyOf(length)
    }

    private fun hexValue(byte: Byte): Int {
        val value = Character.digit(byte.toInt(), 16)
        require(value >= 0) { "Invalid hex digit" }
        return value
    }

}
