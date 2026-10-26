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
package org.meshtastic.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.meshtastic.core.ui.util.MediaCaptureType
import org.meshtastic.core.ui.util.createMediaCaptureIntent
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MediaCaptureIntentTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val output = Uri.parse("content://com.geeksville.mesh.fdroid.debug.media-capture/media_captures/test")

    @Test
    fun photoMatchesStandardCameraFilterAndGrantsAccessToOutput() {
        verifyCapture(MediaCaptureType.PHOTO, MediaStore.ACTION_IMAGE_CAPTURE)
    }

    @Test
    fun videoMatchesStandardCameraFilterAndGrantsAccessToOutput() {
        verifyCapture(MediaCaptureType.VIDEO, MediaStore.ACTION_VIDEO_CAPTURE)
    }

    @Test
    fun mimeTypeReproducesTheCameraResolutionFailure() {
        for (type in MediaCaptureType.entries) {
            val intent = createMediaCaptureIntent(context, type, output)
            val filter = IntentFilter(intent.action).apply { addCategory(Intent.CATEGORY_DEFAULT) }
            intent.type = if (type == MediaCaptureType.PHOTO) "image/jpeg" else "video/mp4"
            assertEquals(IntentFilter.NO_MATCH_DATA, filter.match(context.contentResolver, intent, true, "capture"))
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyCapture(type: MediaCaptureType, expectedAction: String) {
        val intent = createMediaCaptureIntent(context, type, output)
        assertEquals(expectedAction, intent.action)
        assertNull(intent.type)
        assertNull(intent.data)
        assertEquals(output, intent.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT))
        assertEquals(output, intent.clipData?.getItemAt(0)?.uri)
        assertNotEquals(0, intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertNotEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val filter = IntentFilter(expectedAction).apply { addCategory(Intent.CATEGORY_DEFAULT) }
        assertTrue(filter.match(context.contentResolver, intent, true, "capture") >= 0)
    }
}
