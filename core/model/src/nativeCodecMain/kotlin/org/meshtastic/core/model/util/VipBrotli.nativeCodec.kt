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

import com.ensody.kompressor.brotli.BrotliCompressor
import com.ensody.kompressor.brotli.BrotliDecompressor
import com.ensody.kompressor.core.ByteArraySlice
import com.ensody.kompressor.core.transform

internal actual fun compressVipBrotli(plain: ByteArray): ByteArray =
    BrotliCompressor(compressionLevel = VipBrotli.QUALITY, lgwin = 16).transform(plain)

internal actual fun decompressVipBrotli(compressed: ByteArray): ByteArray {
    val input = ByteArraySlice(compressed)
    val output = ByteArraySlice(VipBrotli.MAX_TEXT_BYTES + 1)
    try {
        BrotliDecompressor().transform(input, output, finish = true)
    } catch (e: IllegalStateException) {
        throw IllegalArgumentException("Invalid VIP Brotli stream", e)
    }
    require(
        !input.hasData && !input.insufficient && !output.insufficient && output.writeStart <= VipBrotli.MAX_TEXT_BYTES,
    ) {
        "Invalid, truncated or oversized VIP Brotli message"
    }
    return output.data.copyOf(output.writeStart)
}
