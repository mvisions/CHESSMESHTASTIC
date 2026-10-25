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
import io.ktor.http.isSuccess
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okio.BufferedSource
import org.koin.core.annotation.Single
import org.meshtastic.core.di.CoroutineDispatchers
import org.meshtastic.core.repository.MediaUploader

/**
 * Uploads to Litterbox (litterbox.catbox.moe): anonymous, no account, files up to 1 GB that expire after [EXPIRY].
 * Unlike one-shot hosts, the link survives repeated downloads, so everyone on a channel can open it.
 */
@Single(binds = [MediaUploader::class])
class LitterboxMediaUploader(private val client: HttpClient, private val dispatchers: CoroutineDispatchers) :
    MediaUploader {

    override suspend fun upload(source: BufferedSource, fileName: String, contentType: String): String =
        withContext(dispatchers.io) {
            coroutineScope {
                val body =
                    writer(dispatchers.io) {
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val read = source.read(buffer)
                            if (read == -1) break
                            channel.writeFully(buffer, 0, read)
                        }
                    }
                        .channel
                val response =
                    client.submitFormWithBinaryData(
                        url = UPLOAD_URL,
                        formData =
                        formData {
                            append("reqtype", "fileupload")
                            append("time", EXPIRY)
                            append(
                                "fileToUpload",
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
                check(response.status.isSuccess() && text.startsWith("https://")) {
                    "Upload rejected: HTTP ${response.status.value}"
                }
                text
            }
        }

    private fun sanitize(fileName: String): String = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")

    companion object {
        const val UPLOAD_URL = "https://litterbox.catbox.moe/resources/internals/api.php"

        /** Litterbox accepts 1h, 12h, 24h or 72h. */
        const val EXPIRY = "24h"

        private const val BUFFER_SIZE = 64 * 1024
        private const val UPLOAD_TIMEOUT_MS = 30 * 60_000L
        private const val SOCKET_TIMEOUT_MS = 120_000L
    }
}
