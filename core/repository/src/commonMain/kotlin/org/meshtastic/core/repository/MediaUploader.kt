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
package org.meshtastic.core.repository

import okio.BufferedSource

/** Uploads media to a temporary file host so that only a short link has to travel over the mesh. */
interface MediaUploader {
    /**
     * Streams [source] to the host and returns the public download URL.
     *
     * @throws Exception when the host is unreachable or rejects the upload.
     */
    suspend fun upload(
        source: BufferedSource,
        fileName: String,
        contentType: String,
        host: MediaUploadHost = MediaUploadHost.LITTERBOX,
    ): String
}

/** A backup host must be explicitly authorized before it receives a file. */
enum class MediaUploadHost {
    LITTERBOX,
    UGUU,
}
