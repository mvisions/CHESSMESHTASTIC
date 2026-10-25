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

import okio.ByteString
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.model.isBroadcast
import org.meshtastic.proto.ChannelSet
import org.meshtastic.proto.Constants
import org.meshtastic.proto.Data
import org.meshtastic.proto.PortNum

/** Versioned VIP chat transport. Compression only: it does not provide confidentiality or authentication. */
object VipBrotli {
    const val CHANNEL_NAME = "VIP"
    const val QUALITY = 11
    const val MAX_TEXT_BYTES = 4096
    private const val PREFIX = "VIPB1:"

    fun isVip(channels: ChannelSet, channel: Int): Boolean = channels.settings.getOrNull(channel)?.name == CHANNEL_NAME

    fun appliesTo(packet: DataPacket, channels: ChannelSet): Boolean =
        packet.isBroadcast && packet.dataType == PortNum.TEXT_MESSAGE_APP.value && isVip(channels, packet.channel)

    fun transportPayload(packet: DataPacket, channels: ChannelSet): ByteString {
        val plain = packet.bytes ?: ByteString.EMPTY
        if (!appliesTo(packet, channels)) return plain
        val encoded = encode(plain)
        val data =
            Data.Builder()
                .also {
                    it.portnum = PortNum.TEXT_MESSAGE_APP
                    it.payload = encoded
                    it.reply_id = packet.replyId ?: 0
                    it.emoji = packet.emoji
                }
                .build()
        if (Data.ADAPTER.encodedSize(data) > Constants.DATA_PAYLOAD_LEN.value) {
            throw VipMessageTooLongException()
        }
        return encoded
    }

    fun encode(plain: ByteString): ByteString {
        if (plain.size > MAX_TEXT_BYTES) throw VipMessageTooLongException()
        val compressed = compressVipBrotli(plain.toByteArray())
        return (PREFIX + compressed.toByteString().base64()).encodeUtf8()
    }

    fun decode(wire: ByteString): ByteString {
        val text = wire.utf8()
        require(text.startsWith(PREFIX)) { "VIP message does not use the supported Brotli format" }
        val compressed = text.substring(PREFIX.length).decodeBase64()
        requireNotNull(compressed) { "Invalid VIP Brotli encoding" }
        require(compressed.size <= MAX_TEXT_BYTES) { "VIP compressed payload exceeds the size limit" }
        val plain = decompressVipBrotli(compressed.toByteArray())
        require(plain.toByteString().utf8().encodeUtf8() == plain.toByteString()) { "VIP message is not valid UTF-8" }
        return plain.toByteString()
    }
}

class VipMessageTooLongException(message: String = "VIP Brotli message is too long for one radio packet") :
    IllegalArgumentException(message)

internal expect fun compressVipBrotli(plain: ByteArray): ByteArray

internal expect fun decompressVipBrotli(compressed: ByteArray): ByteArray
