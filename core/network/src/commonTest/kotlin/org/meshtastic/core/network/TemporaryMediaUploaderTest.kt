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
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okio.Buffer
import org.meshtastic.core.di.CoroutineDispatchers
import org.meshtastic.core.repository.MediaUploadHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TemporaryMediaUploaderTest {
    private val dispatchers =
        CoroutineDispatchers(io = Dispatchers.Default, main = Dispatchers.Default, default = Dispatchers.Default)

    @Test
    fun uploadsTheFileAndReturnsTheLink() = runTest {
        var sentBody = ""
        val client = client { request ->
            assertEquals(TemporaryMediaUploader.UPLOAD_URL, request.url.toString())
            sentBody = request.body.toByteArray().decodeToString()
            respond("https://litter.catbox.moe/abc123.jpg\n", HttpStatusCode.OK)
        }
        val url =
            TemporaryMediaUploader(client, dispatchers)
                .upload(Buffer().writeUtf8("pixels"), "my photo.jpg", "image/jpeg")

        assertEquals("https://litter.catbox.moe/abc123.jpg", url)
        assertTrue("name=\"reqtype\"" in sentBody && "fileupload" in sentBody)
        assertTrue("name=\"time\"" in sentBody && TemporaryMediaUploader.EXPIRY in sentBody)
        assertTrue("filename=\"my_photo.jpg\"" in sentBody)
        assertTrue("pixels" in sentBody)
    }

    @Test
    fun aRejectedUploadThrows() = runTest {
        val client = client { respond("File too large", HttpStatusCode.PayloadTooLarge) }
        assertFailsWith<IllegalStateException> {
            TemporaryMediaUploader(client, dispatchers).upload(Buffer().writeUtf8("x"), "a.mp4", "video/mp4")
        }
    }

    @Test
    fun uguuIsOnlyUsedWhenExplicitlySelected() = runTest {
        val client = client { request ->
            assertEquals(TemporaryMediaUploader.UGUU_UPLOAD_URL, request.url.toString())
            val body = request.body.toByteArray().decodeToString()
            assertTrue("name=\"files[]\"" in body)
            assertTrue("filename=\"voice.m4a\"" in body)
            assertTrue("name=\"reqtype\"" !in body)
            respond(
                """{"success":true,"files":[{"url":"https://d.uguu.se/test.m4a"}]}""",
                HttpStatusCode.OK,
            )
        }
        assertEquals(
            "https://d.uguu.se/test.m4a",
            TemporaryMediaUploader(client, dispatchers)
                .upload(Buffer().writeUtf8("audio"), "voice.m4a", "audio/mp4", MediaUploadHost.UGUU),
        )
    }

    @Test
    fun invalidBackupResponsesNeverBecomeLinks() = runTest {
        val responses =
            listOf(
                """{"success":false,"description":"Rejected"}""",
                """{"success":true,"files":[]}""",
                """{"success":true,"files":[{"url":"http://d.uguu.se/test.png"}]}""",
                """{"success":true,"files":[{"url":"https://uguu.se.attacker.test/a.png"}]}""",
                """{"success":true,"files":[{"url":"https://attacker.test/a.png"}]}""",
            )
        for (body in responses) {
            val client = client { respond(body, HttpStatusCode.OK) }
            assertFailsWith<IllegalStateException> {
                TemporaryMediaUploader(client, dispatchers)
                    .upload(Buffer().writeUtf8("pixels"), "a.png", "image/png", MediaUploadHost.UGUU)
            }
        }
    }

    @Test
    fun htmlErrorIsNotAcceptedAsALitterboxLink() = runTest {
        val client = client { respond("<html>Invalid uploader</html>", HttpStatusCode.OK) }
        assertFailsWith<IllegalStateException> {
            TemporaryMediaUploader(client, dispatchers).upload(Buffer().writeUtf8("x"), "a.png", "image/png")
        }
    }

    private fun client(
        handler:
        suspend io.ktor.client.engine.mock.MockRequestHandleScope.(
            io.ktor.client.request.HttpRequestData,
        ) -> io.ktor.client.request.HttpResponseData,
    ) = HttpClient(MockEngine(handler)) {
        install(HttpTimeout)
        install(HttpRequestRetry)
    }
}
