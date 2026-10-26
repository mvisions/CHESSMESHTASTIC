/*
 * Copyright (c) 2026 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.meshtastic.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Single
import org.meshtastic.core.di.CoroutineDispatchers

private const val RELEASES_API = "https://api.github.com/repos/mvisions/Meshtastic-Android/releases"
private const val DOWNLOAD_PREFIX = "https://github.com/mvisions/Meshtastic-Android/releases/download/"
private const val UNIVERSAL_APK = "Meshtastic-media-universal.apk"
private const val ARM64_APK = "Meshtastic-media-arm64.apk"
private const val PAGE_SIZE = 100
private val releaseJson = Json { ignoreUnknownKeys = true }

@Serializable
internal data class AppRelease(
    @SerialName("tag_name") val tag: String,
    val draft: Boolean,
    val assets: List<AppReleaseAsset>,
)

@Serializable
internal data class AppReleaseAsset(
    val name: String,
    @SerialName("browser_download_url") val url: String,
    val state: String,
    val size: Long,
)

@Single
class AppReleaseRemoteDataSource(private val client: HttpClient, private val dispatchers: CoroutineDispatchers) {
    suspend fun findUpdate(installedVersion: String, arm64: Boolean = true): String? = withContext(dispatchers.io) {
        val installed = Regex("""media-part(\d+(?:\.\d+)*)""").find(installedVersion)?.groupValues?.get(1)
        requireNotNull(installed) { "Installed version does not identify a media project release" }
        val releases = mutableListOf<AppRelease>()
        var page = 1
        do {
            val response =
                client.get(RELEASES_API) {
                    parameter("per_page", PAGE_SIZE)
                    parameter("page", page++)
                }
            check(response.status.isSuccess()) { "Release check failed: HTTP ${response.status.value}" }
            val batch = releaseJson.decodeFromString<List<AppRelease>>(response.bodyAsText())
            releases.addAll(batch)
        } while (batch.size == PAGE_SIZE)
        selectAppUpdate(installed, releases, arm64)
    }
}

internal fun selectAppUpdate(installed: String, releases: List<AppRelease>, arm64: Boolean = true): String? {
    val apkName = if (arm64) ARM64_APK else UNIVERSAL_APK
    val candidates =
        releases
            .filter { !it.draft }
            .mapNotNull { release ->
                val version =
                    Regex("""media-links-test-(\d+(?:\.\d+)*)""").matchEntire(release.tag)?.groupValues?.get(1)
                val asset =
                    release.assets.firstOrNull {
                        it.name == apkName &&
                            it.state == "uploaded" &&
                            it.size > 0 &&
                            it.url == "$DOWNLOAD_PREFIX${release.tag}/$apkName"
                    }
                if (version != null && asset != null) version to asset.url else null
            }
    val latest = candidates.maxWithOrNull { a, b -> compareAppVersions(a.first, b.first) }
    checkNotNull(latest) { "No downloadable project release found" }
    return latest.second.takeIf { compareAppVersions(latest.first, installed) > 0 }
}

internal fun compareAppVersions(left: String, right: String): Int {
    val a = left.split('.').map(String::toInt)
    val b = right.split('.').map(String::toInt)
    return (0 until maxOf(a.size, b.size)).firstNotNullOfOrNull { index ->
        (a.getOrElse(index) { 0 }).compareTo(b.getOrElse(index) { 0 }).takeIf { it != 0 }
    } ?: 0
}
