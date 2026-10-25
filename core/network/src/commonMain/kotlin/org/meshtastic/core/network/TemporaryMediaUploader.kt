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
import io.ktor.client.plugins.retry
import io.ktor.client.plugins.timeout
import io.ktor.client.request.forms.ChannelProvider
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.isSuccess
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.BufferedSource
import org.koin.core.annotation.Single
import org.meshtastic.core.di.CoroutineDispatchers
import org.meshtastic.core.repository.MediaUploadHost
import org.meshtastic.core.repository.MediaUploader

/**
 * Streams to Litterbox (24 hours), or Uguu (3 hours) when explicitly selected by the caller. Neither host removes the
 * file after its first download.
 */
@Single(binds = [MediaUploader::class])
class TemporaryMediaUploader(private val client: HttpClient, private val dispatchers: CoroutineDispatchers) :
    MediaUploader {

    override suspend fun upload(
        source: BufferedSource,
        fileName: String,
        contentType: String,
        host: MediaUploadHost,
    ): String = withContext(dispatchers.io) {
        coroutineScope {
            val body =
                writer(dispatchers.io) {
                    val buffer = ByteArray(BUFFER_SIZE)
                    var totalBytes = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read == -1) break
                        totalBytes += read
                        check(host != MediaUploadHost.UGUU || totalBytes <= UGUU_MAX_BYTES) {
                            "Uguu upload exceeds 128 MiB"
                        }
                        channel.writeFully(buffer, 0, read)
                    }
                }
                    .channel
            val response =
                client.submitFormWithBinaryData(
                    url = if (host == MediaUploadHost.LITTERBOX) UPLOAD_URL else UGUU_UPLOAD_URL,
                    formData =
                    formData {
                        if (host == MediaUploadHost.LITTERBOX) {
                            append("reqtype", "fileupload")
                            append("time", EXPIRY)
                        }
                        append(
                            if (host == MediaUploadHost.LITTERBOX) "fileToUpload" else "files[]",
                            ChannelProvider { body },
                            Headers.build {
                                append(HttpHeaders.ContentType, contentType)
                                append(HttpHeaders.ContentDisposition, "filename=\"${sanitize(fileName)}\"")
                            },
                        )
                    },
                ) {
                    // The body is a one-shot stream, so a retry would resend an already drained channel.
                    retry { noRetry() }
                    timeout {
                        requestTimeoutMillis = UPLOAD_TIMEOUT_MS
                        socketTimeoutMillis = SOCKET_TIMEOUT_MS
                    }
                }
            val text = response.bodyAsText().trim()
            check(response.status.isSuccess()) {
                "Upload rejected: HTTP ${response.status.value}"
            }
            val url =
                if (host == MediaUploadHost.LITTERBOX) {
                    text
                } else {
                    val result = Json.parseToJsonElement(text).jsonObject
                    check(result["success"]?.jsonPrimitive?.content == "true") { "Uguu rejected the upload" }
                    result["files"]?.jsonArray?.singleOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content
                        ?: error("Uguu did not return a download URL")
                }
            validateDownloadUrl(url, host)
        }
    }

    private fun sanitize(fileName: String): String = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun validateDownloadUrl(value: String, host: MediaUploadHost): String {
        val url = Url(value)
        val domain = if (host == MediaUploadHost.LITTERBOX) "litter.catbox.moe" else "uguu.se"
        check(
            url.protocol == URLProtocol.HTTPS &&
                (url.host == domain || (host == MediaUploadHost.UGUU && url.host.endsWith(".$domain"))) &&
                url.user.isNullOrEmpty() &&
                url.password.isNullOrEmpty() &&
                value.none { it.isWhitespace() },
        ) {
            "Upload did not return a valid host download URL"
        }
        return value
    }

    companion object {
        const val UPLOAD_URL = "https://litterbox.catbox.moe/resources/internals/api.php"
        const val UGUU_UPLOAD_URL = "https://uguu.se/upload"

        /** Litterbox accepts 1h, 12h, 24h or 72h. */
        const val EXPIRY = "24h"

        private const val BUFFER_SIZE = 64 * 1024
        private const val UPLOAD_TIMEOUT_MS = 30 * 60_000L
        private const val SOCKET_TIMEOUT_MS = 120_000L
        private const val UGUU_MAX_BYTES = 128L * 1024 * 1024
    }
}
