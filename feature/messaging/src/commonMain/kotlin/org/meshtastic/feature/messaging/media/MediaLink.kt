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
package org.meshtastic.feature.messaging.media

/** A photo or video link found in a message, previewed inline in the bubble. */
data class MediaLink(val url: String, val kind: Kind) {
    enum class Kind {
        IMAGE,
        VIDEO,
    }
}

private val URL_REGEX = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")
private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "webm", "mov", "3gp", "mkv")

private val MIME_EXTENSIONS =
    mapOf(
        "image/jpeg" to "jpg",
        "image/png" to "png",
        "image/gif" to "gif",
        "image/webp" to "webp",
        "image/bmp" to "bmp",
        "image/heic" to "heic",
        "image/heif" to "heif",
        "image/avif" to "avif",
        "video/mp4" to "mp4",
        "video/webm" to "webm",
        "video/quicktime" to "mov",
        "video/3gpp" to "3gp",
        "video/x-matroska" to "mkv",
    )

/** Returns the first photo/video link in [text], judged by the file extension of the URL path. */
fun findMediaLink(text: String): MediaLink? = URL_REGEX.findAll(text).firstNotNullOfOrNull { match ->
    val url = match.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']')
    val extension =
        url.substringBefore('#').substringBefore('?').substringAfterLast('/').substringAfterLast('.', "")
    when (extension.lowercase()) {
        in IMAGE_EXTENSIONS -> MediaLink(url, MediaLink.Kind.IMAGE)
        in VIDEO_EXTENSIONS -> MediaLink(url, MediaLink.Kind.VIDEO)
        else -> null
    }
}

/** True when [text] is nothing but [link], so the bubble can show the media alone. */
fun isOnlyMediaLink(text: String, link: MediaLink): Boolean = text.trim() == link.url

/** The file extension to upload a file of [mimeType] with, or null when it is not a supported photo or video. */
fun mediaExtensionFor(mimeType: String?): String? {
    val type = mimeType?.lowercase()?.substringBefore(';')?.trim() ?: return null
    return MIME_EXTENSIONS[type]
        ?: when {
            type.startsWith("image/") -> "jpg"
            type.startsWith("video/") -> "mp4"
            else -> null
        }
}

/** Guesses a MIME type from a file name, for platforms whose pickers don't report one. */
fun mimeTypeFromName(name: String): String? = when (val extension = name.substringAfterLast('.', "").lowercase()) {
    "jpeg" -> "image/jpeg"
    "m4v" -> "video/mp4"
    else -> MIME_EXTENSIONS.entries.firstOrNull { it.value == extension }?.key
}
