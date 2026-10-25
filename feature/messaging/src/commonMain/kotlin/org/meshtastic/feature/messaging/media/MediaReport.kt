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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler

private const val CATBOX_DOMAIN = "catbox.moe"
private const val CATBOX_ABUSE_EMAIL = "admin@catbox.moe"
private const val CATBOX_CONTACT_URL = "https://catbox.moe/contact.php"
private const val UGUU_DOMAIN = "uguu.se"
private const val UGUU_ABUSE_EMAIL = "abuse@pomf.se"
private const val UGUU_CONTACT_URL = "https://uguu.se/faq.html"
private const val HEX_DIGITS = "0123456789ABCDEF"
private const val NIBBLE_BITS = 4
private const val NIBBLE_MASK = 0x0F
private const val BYTE_MASK = 0xFF
private const val ASCII_LIMIT = 0x80

/**
 * Where to report a shared photo/video. [primaryUri] is tried first (a prefilled abuse e-mail when the host has one)
 * and [fallbackUri] (the host's contact page) when no app can handle it.
 */
data class MediaReportTarget(val primaryUri: String, val fallbackUri: String?)

/** Builds the abuse-report destination for the site hosting [mediaUrl], or `null` when the URL has no usable host. */
fun mediaReportTargetFor(mediaUrl: String): MediaReportTarget? {
    val host = hostOf(mediaUrl) ?: return null
    val catbox = host == CATBOX_DOMAIN || host.endsWith(".$CATBOX_DOMAIN")
    val uguu = host == UGUU_DOMAIN || host.endsWith(".$UGUU_DOMAIN")
    return if (catbox || uguu) {
        val subject = "Abuse report: $mediaUrl"
        val body =
            "Hello, I would like to report this file because it breaks the hosting service's rules " +
                "(e.g. it involves minors or illegal content). Please remove it:\n\n$mediaUrl\n"
        MediaReportTarget(
            primaryUri =
            "mailto:${if (catbox) CATBOX_ABUSE_EMAIL else UGUU_ABUSE_EMAIL}" +
                "?subject=${percentEncode(subject)}&body=${percentEncode(body)}",
            fallbackUri = if (catbox) CATBOX_CONTACT_URL else UGUU_CONTACT_URL,
        )
    } else {
        MediaReportTarget(primaryUri = "https://$host/", fallbackUri = null)
    }
}

/** Returns a callback that opens the report destination for [mediaUrl], or `null` when it cannot be reported. */
@Composable
fun rememberReportMedia(mediaUrl: String): (() -> Unit)? {
    val uriHandler = LocalUriHandler.current
    return remember(mediaUrl, uriHandler) {
        val target = mediaReportTargetFor(mediaUrl) ?: return@remember null
        {
            runCatching { uriHandler.openUri(target.primaryUri) }
                .onFailure { target.fallbackUri?.let { fallback -> runCatching { uriHandler.openUri(fallback) } } }
        }
    }
}

private fun hostOf(url: String): String? {
    val afterScheme = url.substringAfter("://", missingDelimiterValue = "")
    val host = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#').substringAfter('@')
    return host.substringBefore(':').lowercase().takeIf { it.isNotBlank() }
}

private fun percentEncode(value: String): String = buildString {
    value.encodeToByteArray().forEach { byte ->
        val c = byte.toInt() and BYTE_MASK
        val ch = c.toChar()
        if ((c < ASCII_LIMIT && ch.isLetterOrDigit()) || ch in "-._~") {
            append(ch)
        } else {
            append('%')
            append(HEX_DIGITS[c shr NIBBLE_BITS])
            append(HEX_DIGITS[c and NIBBLE_MASK])
        }
    }
}
