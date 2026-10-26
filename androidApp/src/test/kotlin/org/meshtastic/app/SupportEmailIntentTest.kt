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

import android.content.Intent
import android.net.Uri
import org.junit.Test
import org.junit.runner.RunWith
import org.meshtastic.feature.settings.component.createSupportEmailIntent
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SupportEmailIntentTest {
    @Test
    fun emailDraftUsesSupportAddressAndPreservesEncodedTemplate() {
        val subject = "Informe - 2.8.4-media-part5 & test"
        val body = "Version: Parte 5\nError: foto + video? #1"
        val intent = createSupportEmailIntent(subject, body)
        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals("mailto", intent.data?.scheme)
        assertEquals("msabanda@gmail.com", intent.data?.schemeSpecificPart?.substringBefore('?'))
        val query = Uri.parse("https://support.invalid/?${intent.data?.encodedSchemeSpecificPart?.substringAfter('?')}")
        assertEquals(subject, query.getQueryParameter("subject"))
        assertEquals(body, query.getQueryParameter("body"))
        assertNull(intent.type)
        assertNull(intent.extras)
    }
}
