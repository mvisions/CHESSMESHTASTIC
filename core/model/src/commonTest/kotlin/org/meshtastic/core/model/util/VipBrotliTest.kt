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
package org.meshtastic.core.model.util

import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.model.NodeAddress
import org.meshtastic.proto.ChannelSet
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Constants
import org.meshtastic.proto.Data
import org.meshtastic.proto.PortNum
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class VipBrotliTest {
    private fun channelSet(name: String = "VIP"): ChannelSet = ChannelSet.Builder()
        .also {
            it.settings =
                listOf(ChannelSettings.Builder().build(), ChannelSettings.Builder().also { it.name = name }.build())
        }
        .build()

    private val channels = channelSet()

    @Test
    fun `text unicode and multimedia links roundtrip exactly`() {
        val samples =
            listOf(
                "",
                "Hola, VIP",
                "Fotografía y vídeo: mañana, pingüino",
                "\uD83D\uDCF7 \uD83C\uDFB5",
                "https://files.catbox.moe/photo.jpg",
                "https://files.catbox.moe/movie.mp4",
                "https://files.catbox.moe/voice.m4a#d=12345",
                "VIPB1:this is ordinary user text",
            )
        assertEquals(11, VipBrotli.QUALITY)
        samples.forEach { text ->
            val wire = VipBrotli.encode(text.encodeUtf8())
            assertTrue(wire.utf8().startsWith("VIPB1:"))
            assertNotEquals(text.encodeUtf8(), wire)
            assertEquals(text, VipBrotli.decode(wire).utf8())
        }
    }

    @Test
    fun `decodes independently generated Brotli stream`() {
        assertEquals(
            "hello compression world",
            VipBrotli.decode("VIPB1:HxYA+KXL0Nje1CDoVAnrLHUQ".encodeUtf8()).utf8(),
        )
    }

    @Test
    fun `plain unknown and malformed messages are rejected`() {
        listOf("hello", "VIPB2:AAAA", "VIPB1:%%%%", "VIPB1:", "VIPB1:AAAA").forEach { wire ->
            assertFailsWith<IllegalArgumentException> { VipBrotli.decode(wire.encodeUtf8()) }
        }
    }

    @Test
    fun `decoded output limit is enforced without unbounded allocation`() {
        val maximum = "a".repeat(VipBrotli.MAX_TEXT_BYTES).encodeUtf8()
        assertEquals(maximum, VipBrotli.decode(VipBrotli.encode(maximum)))
        assertFailsWith<VipMessageTooLongException> {
            VipBrotli.encode("a".repeat(VipBrotli.MAX_TEXT_BYTES + 1).encodeUtf8())
        }
        assertFailsWith<IllegalArgumentException> {
            VipBrotli.decode("VIPB1:4v8Tv0RYPBYI5F4CAA==".encodeUtf8())
        }
    }

    @Test
    fun `truncated stream trailing bytes and invalid UTF8 are rejected`() {
        val valid = requireNotNull(VipBrotli.encode("hello".encodeUtf8()).utf8().removePrefix("VIPB1:").decodeBase64())
        listOf(valid.substring(0, valid.size - 1), (valid.toByteArray() + byteArrayOf(0)).toByteString()).forEach {
            assertFailsWith<IllegalArgumentException> {
                VipBrotli.decode(("VIPB1:" + it.base64()).encodeUtf8())
            }
        }
        assertFailsWith<IllegalArgumentException> {
            VipBrotli.decode("VIPB1:AAAQ/wM=".encodeUtf8())
        }
    }

    @Test
    fun `other channels direct messages and non-text packets are unchanged`() {
        val plain = "hello".encodeUtf8()
        val primary = DataPacket(NodeAddress.ID_BROADCAST, 0, "hello")
        val direct = DataPacket("!12345678", 1, "hello")
        val telemetry = primary.copy(channel = 1, dataType = PortNum.TELEMETRY_APP.value)
        listOf(primary, direct, telemetry).forEach { packet ->
            assertEquals(plain, VipBrotli.transportPayload(packet, channels))
        }
        assertEquals(plain, VipBrotli.transportPayload(primary.copy(channel = 9), channels))
        assertEquals(
            plain,
            VipBrotli.transportPayload(
                primary.copy(channel = 1),
                channelSet("vip"),
            ),
        )
    }

    @Test
    fun `radio packet size threshold is enforced using the full encoded Data protobuf`() {
        val random = Random(20)
        val source = CharArray(300) { random.nextInt(33, 127).toChar() }.concatToString()
        var accepted = 0
        var rejected = 0
        for (length in 1..source.length) {
            val packet = DataPacket(NodeAddress.ID_BROADCAST, 1, source.take(length), replyId = Int.MAX_VALUE)
            val wire = VipBrotli.encode(requireNotNull(packet.bytes))
            val data =
                Data.Builder()
                    .also {
                        it.portnum = PortNum.TEXT_MESSAGE_APP
                        it.payload = wire
                        it.reply_id = Int.MAX_VALUE
                    }
                    .build()
            if (Data.ADAPTER.encodedSize(data) <= Constants.DATA_PAYLOAD_LEN.value) {
                assertEquals(wire, VipBrotli.transportPayload(packet, channels))
                accepted++
            } else {
                assertFailsWith<VipMessageTooLongException> { VipBrotli.transportPayload(packet, channels) }
                rejected++
            }
        }
        assertTrue(accepted > 0)
        assertTrue(rejected > 0)
    }

    @Test
    fun `wire size includes protobuf reply metadata and never mutates display packet`() {
        val packet = DataPacket(NodeAddress.ID_BROADCAST, 1, "Hola VIP", replyId = Int.MAX_VALUE)
        val plain = packet.bytes
        val wire = VipBrotli.transportPayload(packet, channels)
        assertEquals(plain, packet.bytes)
        assertEquals(plain, VipBrotli.decode(wire))
        val data =
            Data.Builder()
                .also {
                    it.portnum = PortNum.TEXT_MESSAGE_APP
                    it.payload = wire
                    it.reply_id = Int.MAX_VALUE
                }
                .build()
        assertTrue(Data.ADAPTER.encodedSize(data) <= Constants.DATA_PAYLOAD_LEN.value)
        val random = Random(7)
        val longText = CharArray(1000) { random.nextInt(33, 127).toChar() }.concatToString()
        assertFailsWith<VipMessageTooLongException> {
            VipBrotli.transportPayload(DataPacket(NodeAddress.ID_BROADCAST, 1, longText), channels)
        }
    }
}
