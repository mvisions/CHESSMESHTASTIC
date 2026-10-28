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
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.meshtastic.core.di.CoroutineDispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AppReleaseRemoteDataSourceTest {
    private val dispatchers = CoroutineDispatchers(Dispatchers.Default, Dispatchers.Default, Dispatchers.Default)

    private fun release(version: String): AppRelease {
        val tag = "media-links-test-$version"
        return AppRelease(
            tag,
            false,
            listOf(
                AppReleaseAsset(
                    "Meshtastic-media-arm64.apk",
                    "https://github.com/mvisions/Meshtastic-Android/releases/download/$tag/Meshtastic-media-arm64.apk",
                    "uploaded",
                    100,
                    "sha256:" + "a".repeat(64),
                ),
            ),
        )
    }

    @Test
    fun sameReleaseIsCurrent() {
        assertNull(selectAppUpdate("5", listOf(release("5"))))
    }

    @Test
    fun partFiveOpensExactPartSixArm64Download() {
        assertEquals(
            "https://github.com/mvisions/Meshtastic-Android/releases/download/media-links-test-6/Meshtastic-media-arm64.apk",
            selectAppUpdate("5", listOf(release("6"), release("5")))?.url,
        )
    }

    @Test
    fun otherArchitecturesUseUniversalApk() {
        val next = release("6")
        val universal =
            next.assets
                .single()
                .copy(
                    name = "Meshtastic-media-universal.apk",
                    url = next.assets.single().url.replace("-arm64.apk", "-universal.apk"),
                )
        assertEquals(
            universal.url,
            selectAppUpdate("5", listOf(next.copy(assets = listOf(universal))), arm64 = false)?.url,
        )
    }

    @Test
    fun unpublishedLocalHotfixIsNotADowngrade() {
        assertNull(selectAppUpdate("4.1", listOf(release("4"))))
    }

    @Test
    fun latestIsChosenNumericallyNotByApiOrder() {
        val latest = release("10")
        assertEquals(
            latest.assets.single().url,
            selectAppUpdate("4", listOf(release("9"), latest, release("4.1")))?.url,
        )
    }

    @Test
    fun hotfixIsAnUpdate() {
        val next = release("4.1")
        assertEquals(next.assets.single().url, selectAppUpdate("4", listOf(next))?.url)
    }

    @Test
    fun missingVersionComponentsAreZero() {
        assertEquals(0, compareAppVersions("4", "4.0"))
        assertEquals(1, compareAppVersions("4.10", "4.2"))
    }

    @Test
    fun draftAndIncompleteReleaseAreExcluded() {
        assertNull(
            selectAppUpdate(
                "4",
                listOf(
                    release("4"),
                    release("5").copy(draft = true),
                    release("6").copy(assets = emptyList()),
                ),
            ),
        )
    }

    @Test
    fun foreignDownloadAndUnknownTagAreExcluded() {
        val next = release("5")
        val foreign = next.copy(assets = listOf(next.assets.single().copy(url = "https://example.org/app.apk")))
        assertNull(selectAppUpdate("4", listOf(release("4"), foreign, next.copy(tag = "unrelated-5"))))
    }

    @Test
    fun renamedRepositoryDownloadIsAccepted() {
        val next = release("6")
        val renamed =
            next.copy(
                assets =
                listOf(
                    next.assets
                        .single()
                        .copy(url = next.assets.single().url.replace("/Meshtastic-Android/", "/CHESSMESHTASTIC/")),
                ),
            )
        assertEquals(renamed.assets.single().url, selectAppUpdate("5", listOf(renamed))?.url)
    }

    @Test
    fun emptyReleaseListIsAnErrorNotCurrent() {
        assertFailsWith<IllegalStateException> { selectAppUpdate("4", emptyList()) }
    }

    @Test
    fun prereleasesAreIncludedAndActualInstalledNameIsParsed() = runTest {
        val next = release("6")
        val client =
            HttpClient(
                MockEngine { request ->
                    assertEquals("api.github.com", request.url.host)
                    assertEquals("100", request.url.parameters["per_page"])
                    respond(
                        """[{"tag_name":"${next.tag}","draft":false,"prerelease":true,"assets":[{
                "name":"Meshtastic-media-arm64.apk","state":"uploaded","size":100,
                "digest":"sha256:${"a".repeat(64)}",
                "browser_download_url":"${next.assets.single().url}"}]}]""",
                    )
                },
            )
        try {
            assertEquals(
                next.assets.single().url,
                AppReleaseRemoteDataSource(client, dispatchers).findUpdate("2.8.4-media-part5 (29322814) fdroid")?.url,
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun rateLimitIsAnErrorNotCurrent() = runTest {
        val client = HttpClient(MockEngine { respond("rate limited", HttpStatusCode.Forbidden) })
        try {
            assertFailsWith<IllegalStateException> {
                AppReleaseRemoteDataSource(client, dispatchers).findUpdate("2.8.4-media-part4.1")
            }
        } finally {
            client.close()
        }

        @Test
        fun selectedReleaseCarriesVersionedNameSizeAndIntegrity() {
            val selected = requireNotNull(selectAppUpdate("6.2", listOf(release("6.3"))))
            assertEquals("Meshtastic-media-part6.3-arm64.apk", selected.fileName)
            assertEquals(100L, selected.size)
            assertEquals("a".repeat(64), selected.sha256)
            assertEquals(true, selected.isInstalled("2.8.4-media-part6.3 (29322824) fdroid"))
            assertEquals(false, selected.isInstalled("2.8.4-media-part6.2"))
        }

        @Test
        fun missingIntegrityIsAnError() {
            val next = release("6.3")
            assertFailsWith<IllegalStateException> {
                selectAppUpdate("6.2", listOf(next.copy(assets = next.assets.map { it.copy(digest = null) })))
            }
        }
    }
}
