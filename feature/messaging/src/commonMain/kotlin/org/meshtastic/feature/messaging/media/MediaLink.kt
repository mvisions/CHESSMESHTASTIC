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

/** A photo, video or voice-message link found in a message, previewed inline in the bubble. */
data class MediaLink(val url: String, val kind: Kind) {
    enum class Kind {
        IMAGE,
        VIDEO,
        AUDIO,
    }

    /** The URL to fetch: without the `#d=` duration hint, which only the app reads. */
    val fetchUrl: String
        get() = url.substringBefore('#')

    /** Recording length carried in the link's `#d=<seconds>` fragment, so it shows before anything is downloaded. */
    val durationMillis: Long?
        get() = DURATION_FRAGMENT.find(url)?.groupValues?.get(1)?.toLongOrNull()?.times(MILLIS_PER_SECOND)
}

private val DURATION_FRAGMENT = Regex("""#d=(\d{1,6})$""")
private const val MILLIS_PER_SECOND = 1000L
private const val MILLIS_ROUNDING = 500L

private val URL_REGEX = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")
private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "webm", "mov", "3gp", "mkv")
private val AUDIO_EXTENSIONS = setOf("m4a", "aac", "mp3", "ogg", "oga", "opus", "wav", "flac", "amr")

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
        "audio/mp4" to "m4a",
        "audio/x-m4a" to "m4a",
        "audio/aac" to "aac",
        "audio/mpeg" to "mp3",
        "audio/ogg" to "ogg",
        "audio/opus" to "opus",
        "audio/wav" to "wav",
        "audio/x-wav" to "wav",
        "audio/flac" to "flac",
        "audio/amr" to "amr",
    )

/** Returns the first photo/video/audio link in [text], judged by the file extension of the URL path. */
fun findMediaLink(text: String): MediaLink? = URL_REGEX.findAll(text).firstNotNullOfOrNull { match ->
    val url = match.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']')
    val extension =
        url.substringBefore('#').substringBefore('?').substringAfterLast('/').substringAfterLast('.', "")
    when (extension.lowercase()) {
        in IMAGE_EXTENSIONS -> MediaLink(url, MediaLink.Kind.IMAGE)
        in VIDEO_EXTENSIONS -> MediaLink(url, MediaLink.Kind.VIDEO)
        in AUDIO_EXTENSIONS -> MediaLink(url, MediaLink.Kind.AUDIO)
        else -> null
    }
}

/** True when [text] is nothing but [link], so the bubble can show the media alone. */
fun isOnlyMediaLink(text: String, link: MediaLink): Boolean = text.trim() == link.url

/** The file extension to upload a file of [mimeType] with, or null when it is not a supported media type. */
fun mediaExtensionFor(mimeType: String?): String? {
    val type = mimeType?.lowercase()?.substringBefore(';')?.trim() ?: return null
    return MIME_EXTENSIONS[type]
        ?: when {
            type.startsWith("image/") -> "jpg"
            type.startsWith("video/") -> "mp4"
            type.startsWith("audio/") -> "m4a"
            else -> null
        }
}

/** Guesses a MIME type from a file name, for platforms whose pickers don't report one. */
fun mimeTypeFromName(name: String): String? = when (val extension = name.substringAfterLast('.', "").lowercase()) {
    "jpeg" -> "image/jpeg"
    "m4v" -> "video/mp4"
    else -> MIME_EXTENSIONS.entries.firstOrNull { it.value == extension }?.key
}

/** Appends the `#d=<seconds>` duration hint read back by [MediaLink.durationMillis]. */
fun withDurationHint(url: String, durationMillis: Long): String =
    "$url#d=${(durationMillis + MILLIS_ROUNDING) / MILLIS_PER_SECOND}"

/** Formats a playback position as `m:ss`. */
fun formatPlaybackTime(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0) / MILLIS_PER_SECOND).toInt()
    return "${totalSeconds / SECONDS_PER_MINUTE}:${(totalSeconds % SECONDS_PER_MINUTE).toString().padStart(2, '0')}"
}

private const val SECONDS_PER_MINUTE = 60
