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

internal object DownloadRedirectPolicy {

    // Clients known to follow HTTP redirects on artifact downloads. Build tools (Maven, Gradle, Ivy)
    // are deliberately absent: redirect support depends on the HTTP transport each tool bundles and
    // its configuration (including credential handling across origins), which has not been verified
    // by an actual compatibility matrix yet. AUTO fails safe - a wrong guess streams the artifact
    // instead of breaking the client.
    private val redirectCapableAgents = listOf("curl/", "wget/", "mozilla/")

    fun accepts(userAgent: String?): Boolean =
        userAgent != null && redirectCapableAgents.any { userAgent.lowercase().contains(it) }

}
