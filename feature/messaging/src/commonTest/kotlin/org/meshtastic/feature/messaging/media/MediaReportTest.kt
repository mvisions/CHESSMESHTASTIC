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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaReportTest {

    @Test
    fun litterboxLinksAreReportedByEmailWithContactPageFallback() {
        val target = mediaReportTargetFor("https://litter.catbox.moe/abc123.jpg")!!
        assertTrue(target.primaryUri.startsWith("mailto:admin@catbox.moe?subject=Abuse%20report%3A%20https%3A%2F%2F"))
        assertTrue(target.primaryUri.contains("litter.catbox.moe%2Fabc123.jpg"))
        assertTrue(target.primaryUri.substringAfter("&body=").contains("https%3A%2F%2Flitter.catbox.moe%2Fabc123.jpg"))
        assertEquals("https://catbox.moe/contact.php", target.fallbackUri)
    }

    @Test
    fun otherHostsOpenTheirHomePage() {
        val target = mediaReportTargetFor("https://User@Example.com:8443/a/b.mp4?x=1")!!
        assertEquals("https://example.com/", target.primaryUri)
        assertNull(target.fallbackUri)
    }

    @Test
    fun uguuLinksHaveTheirOwnReportContact() {
        val target = mediaReportTargetFor("https://d.uguu.se/test.m4a")!!
        assertTrue(target.primaryUri.startsWith("mailto:abuse@pomf.se?"))
        assertTrue(target.primaryUri.contains("d.uguu.se%2Ftest.m4a"))
        assertTrue(target.primaryUri.substringAfter("&body=").contains("https%3A%2F%2Fd.uguu.se%2Ftest.m4a"))
        assertEquals("https://uguu.se/faq.html", target.fallbackUri)
        assertEquals(
            "https://uguu.se.attacker.test/",
            mediaReportTargetFor("https://uguu.se.attacker.test/a")?.primaryUri,
        )
    }

    @Test
    fun urlsWithoutHostCannotBeReported() {
        assertNull(mediaReportTargetFor("not a url"))
    }
}
