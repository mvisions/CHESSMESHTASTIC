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
package org.meshtastic.feature.messaging.chess

import okio.ByteString.Companion.toByteString
import org.meshtastic.core.model.Node
import org.meshtastic.proto.User
import kotlin.test.Test
import kotlin.test.assertEquals

class PrivateMessageContactKeyTest {
    private val key = ByteArray(32) { 1 }.toByteString()

    private fun node(num: Int, id: String, channel: Int, withKey: Boolean) = Node(
        num = num,
        channel = channel,
        user =
        User.Builder()
            .also {
                it.id = id
                if (withKey) it.public_key = key
            }
            .build(),
    )

    private val ours = node(1, "!00000001", 0, withKey = true)

    @Test
    fun bothKeysUsePublicKeyConversation() {
        assertEquals("8!0000b54c", privateMessageContactKey(node(2, "!0000b54c", 1, withKey = true), ours))
    }

    @Test
    fun missingRemoteKeyFallsBackToHeardChannel() {
        assertEquals("1!0000b54c", privateMessageContactKey(node(2, "!0000b54c", 1, withKey = false), ours))
    }

    @Test
    fun missingLocalKeyFallsBackToHeardChannel() {
        val remote = node(2, "!0000b54c", 2, withKey = true)
        assertEquals("2!0000b54c", privateMessageContactKey(remote, node(1, "!00000001", 0, withKey = false)))
        assertEquals("2!0000b54c", privateMessageContactKey(remote, null))
    }
}
