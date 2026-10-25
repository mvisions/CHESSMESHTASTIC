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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import org.meshtastic.core.ui.util.rememberOpenMediaUrl

/**
 * Routes taps on photo/video links in [content] to an app that can show them (player, gallery), falling back to the
 * browser; every other link keeps the default handling.
 */
@Composable
fun ProvideMediaUriHandler(content: @Composable () -> Unit) {
    val defaultHandler = LocalUriHandler.current
    val openMediaUrl = rememberOpenMediaUrl()
    val handler =
        remember(defaultHandler, openMediaUrl) {
            object : UriHandler {
                override fun openUri(uri: String) {
                    val link = findMediaLink(uri)
                    if (link?.url == uri) openMediaUrl(uri, link.mimeTypeWildcard) else defaultHandler.openUri(uri)
                }
            }
        }
    CompositionLocalProvider(LocalUriHandler provides handler, content = content)
}

private val MediaLink.mimeTypeWildcard: String
    get() =
        when (kind) {
            MediaLink.Kind.IMAGE -> "image/*"
            MediaLink.Kind.VIDEO -> "video/*"
        }
