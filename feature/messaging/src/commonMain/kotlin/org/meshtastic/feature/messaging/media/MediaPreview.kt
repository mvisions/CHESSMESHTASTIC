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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.close
import org.meshtastic.core.resources.media_image_description
import org.meshtastic.core.resources.media_play_video
import org.meshtastic.core.resources.media_report
import org.meshtastic.core.resources.media_report_description
import org.meshtastic.core.ui.component.AutoLinkText
import org.meshtastic.core.ui.icon.Close
import org.meshtastic.core.ui.icon.Flag
import org.meshtastic.core.ui.icon.MeshtasticIcons
import org.meshtastic.core.ui.icon.PlayArrow

private val PREVIEW_MAX_WIDTH = 260.dp
private val PREVIEW_MIN_HEIGHT = 120.dp
private val PREVIEW_MAX_HEIGHT = 320.dp
private val PREVIEW_SHAPE = RoundedCornerShape(12.dp)
private const val VIDEO_ASPECT_RATIO = 16f / 9f
private const val SCRIM_ALPHA = 0.55f

/**
 * Inline preview for a photo, video or voice-message link. Reports through [onLoadFailure] when the photo could not be
 * fetched (e.g. no internet), so the bubble can fall back to showing the plain link; in that case the preview hides
 * itself.
 */
@Composable
fun MediaPreview(link: MediaLink, onLoadFailure: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    var fullScreen by remember(link.url) { mutableStateOf(false) }
    when (link.kind) {
        MediaLink.Kind.IMAGE -> ImagePreview(link.url, onLoadFailure, { fullScreen = true }, modifier = modifier)
        MediaLink.Kind.VIDEO -> VideoPreview(link.url, onClick = { fullScreen = true }, modifier = modifier)
        MediaLink.Kind.AUDIO -> AudioMessage(link, onLoadFailure, modifier = modifier)
    }
    if (fullScreen) {
        FullScreenMediaDialog(link = link, onDismiss = { fullScreen = false })
    }
}

@Composable
private fun ImagePreview(
    url: String,
    onLoadFailure: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var state by remember(url) { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
    if (state is AsyncImagePainter.State.Error) return
    // Fixed width: the bubble measures intrinsics, which an unloaded image cannot answer meaningfully.
    Box(
        modifier =
        modifier
            .width(PREVIEW_MAX_WIDTH)
            .heightIn(min = PREVIEW_MIN_HEIGHT, max = PREVIEW_MAX_HEIGHT)
            .clip(PREVIEW_SHAPE)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = url,
            contentDescription = stringResource(Res.string.media_image_description),
            contentScale = ContentScale.Fit,
            onState = { newState ->
                state = newState
                onLoadFailure(newState is AsyncImagePainter.State.Error)
            },
            modifier = Modifier.width(PREVIEW_MAX_WIDTH),
        )
        if (state is AsyncImagePainter.State.Loading || state is AsyncImagePainter.State.Empty) {
            CircularProgressIndicator(modifier = Modifier.size(32.dp))
        }
        if (state is AsyncImagePainter.State.Success) {
            ReportIconButton(mediaUrl = url, modifier = Modifier.align(Alignment.TopStart))
        }
    }
}

@Composable
private fun VideoPreview(url: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val playLabel = stringResource(Res.string.media_play_video)
    Box(
        modifier =
        modifier
            .width(PREVIEW_MAX_WIDTH)
            .aspectRatio(VIDEO_ASPECT_RATIO)
            .clip(PREVIEW_SHAPE)
            .background(Color.Black)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
            Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MeshtasticIcons.PlayArrow,
                contentDescription = playLabel,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(36.dp),
            )
        }
        ReportIconButton(mediaUrl = url, modifier = Modifier.align(Alignment.TopStart))
    }
}

/** Small flag in the corner of an inline preview that opens the hosting site's abuse report channel. */
@Composable
private fun ReportIconButton(mediaUrl: String, modifier: Modifier = Modifier) {
    val report = rememberReportMedia(mediaUrl) ?: return
    IconButton(
        onClick = report,
        modifier =
        modifier.padding(4.dp).size(32.dp).clip(CircleShape).background(Color.Black.copy(alpha = SCRIM_ALPHA)),
    ) {
        Icon(
            imageVector = MeshtasticIcons.Flag,
            contentDescription = stringResource(Res.string.media_report_description),
            tint = Color.White,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Labelled report action for the full-screen viewer. */
@Composable
private fun ReportTextButton(mediaUrl: String, modifier: Modifier = Modifier) {
    val report = rememberReportMedia(mediaUrl) ?: return
    val description = stringResource(Res.string.media_report_description)
    TextButton(
        onClick = report,
        modifier = modifier.clip(CircleShape).background(Color.Black.copy(alpha = SCRIM_ALPHA)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(MeshtasticIcons.Flag, contentDescription = description, tint = Color.White)
            Text(
                text = stringResource(Res.string.media_report),
                color = Color.White,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun FullScreenMediaDialog(link: MediaLink, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when (link.kind) {
                MediaLink.Kind.IMAGE ->
                    AsyncImage(
                        model = link.url,
                        contentDescription = stringResource(Res.string.media_image_description),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )

                MediaLink.Kind.VIDEO -> VideoPlayer(url = link.url, modifier = Modifier.fillMaxSize())

                // Voice messages play inline and never open full screen.
                MediaLink.Kind.AUDIO -> Unit
            }
            // Drawn above the media so a tap anywhere returns to the chat, even over the platform video view.
            Box(
                Modifier.fillMaxSize()
                    .clickable(
                        interactionSource = null,
                        indication = null,
                        onClickLabel = stringResource(Res.string.close),
                        onClick = onDismiss,
                    ),
            )
            ReportTextButton(
                mediaUrl = link.url,
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(16.dp),
            )
            IconButton(
                onClick = onDismiss,
                modifier =
                Modifier.align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = SCRIM_ALPHA)),
            ) {
                Icon(MeshtasticIcons.Close, contentDescription = stringResource(Res.string.close), tint = Color.White)
            }
        }
    }
}

/**
 * Plays the video at [url] with platform controls. When it cannot be played (no internet, link expired) the player
 * shows the link instead so it can still be copied or opened later.
 */
@Composable expect fun VideoPlayer(url: String, modifier: Modifier = Modifier)

/** Shown in place of a video that could not be played: the raw link, selectable and tappable. */
@Composable
internal fun VideoUnavailable(url: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(24.dp), contentAlignment = Alignment.Center) {
        ProvideMediaUriHandler {
            SelectionContainer {
                AutoLinkText(text = url, style = MaterialTheme.typography.bodyLarge, color = Color.White)
            }
        }
    }
}
