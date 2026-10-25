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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import org.meshtastic.core.ui.util.rememberOpenUrl

/** iOS playback is not wired up yet, so the link is handed to the system browser and kept on screen as a fallback. */
@Composable
actual fun VideoPlayer(url: String, modifier: Modifier) {
    val openUrl = rememberOpenUrl()
    DisposableEffect(url) {
        openUrl(url)
        onDispose {}
    }
    VideoUnavailable(url = url, modifier = modifier)
}
