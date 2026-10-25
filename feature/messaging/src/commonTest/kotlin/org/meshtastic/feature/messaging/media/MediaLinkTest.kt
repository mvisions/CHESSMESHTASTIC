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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaLinkTest {

    @Test
    fun detectsImageLink() {
        assertEquals(
            MediaLink("https://litter.catbox.moe/3fe6m2.png", MediaLink.Kind.IMAGE),
            findMediaLink("https://litter.catbox.moe/3fe6m2.png"),
        )
    }

    @Test
    fun detectsVideoLinkInsideText() {
        assertEquals(
            MediaLink("https://example.com/clip.MP4?x=1", MediaLink.Kind.VIDEO),
            findMediaLink("mira esto https://example.com/clip.MP4?x=1, increíble"),
        )
    }

    @Test
    fun ignoresNonMediaLinks() {
        assertNull(findMediaLink("https://meshtastic.org/docs"))
        assertNull(findMediaLink("foto.jpg sin enlace"))
    }

    @Test
    fun bareLinkIsOnlyMedia() {
        val link = findMediaLink("  https://a.b/c.jpg ")!!
        assertTrue(isOnlyMediaLink("  https://a.b/c.jpg ", link))
        assertFalse(isOnlyMediaLink("hola https://a.b/c.jpg", link))
    }

    @Test
    fun mapsMimeTypesToExtensions() {
        assertEquals("jpg", mediaExtensionFor("image/jpeg"))
        assertEquals("mov", mediaExtensionFor("video/quicktime"))
        assertEquals("jpg", mediaExtensionFor("image/x-unknown"))
        assertNull(mediaExtensionFor("application/pdf"))
        assertNull(mediaExtensionFor(null))
    }

    @Test
    fun guessesMimeTypeFromName() {
        assertEquals("image/jpeg", mimeTypeFromName("file:///tmp/a.JPEG"))
        assertEquals("video/mp4", mimeTypeFromName("clip.mp4"))
        assertNull(mimeTypeFromName("notes.txt"))
    }

    @Test
    fun litterboxLinksFitInOneMessage() {
        assertTrue(fitsInOneMessage("https://litter.catbox.moe/abc123.jpg"))
        assertFalse(fitsInOneMessage("https://example.com/" + "a".repeat(200) + ".jpg"))
    }

    @Test
    fun findsVoiceMessageLinkWithDurationHint() {
        val link = findMediaLink("https://litter.catbox.moe/ab12cd.m4a#d=12")
        assertEquals(MediaLink.Kind.AUDIO, link?.kind)
        assertEquals("https://litter.catbox.moe/ab12cd.m4a", link?.fetchUrl)
        assertEquals(12_000L, link?.durationMillis)
    }

    @Test
    fun durationHintIsRoundedToSeconds() {
        assertEquals("https://x.y/a.m4a#d=8", withDurationHint("https://x.y/a.m4a", 7_600))
        assertEquals(null, MediaLink("https://x.y/a.m4a", MediaLink.Kind.AUDIO).durationMillis)
    }

    @Test
    fun voiceRecordingsUploadAsM4a() {
        assertEquals("m4a", mediaExtensionFor("audio/mp4"))
        assertEquals("m4a", mediaExtensionFor("audio/3gpp"))
        assertEquals("audio/mp4", mimeTypeFromName("voice_1.m4a"))
    }

    @Test
    fun formatsPlaybackTimeAndSpeed() {
        assertEquals("0:07", formatPlaybackTime(7_400))
        assertEquals("2:05", formatPlaybackTime(125_000))
        assertEquals("1.5x", formatPlaybackSpeed(1.5f))
        assertEquals("2x", formatPlaybackSpeed(2f))
        assertEquals(1.5f, nextPlaybackSpeed(1f))
        assertEquals(2f, nextPlaybackSpeed(1.5f))
        assertEquals(1f, nextPlaybackSpeed(2f))
    }
}
