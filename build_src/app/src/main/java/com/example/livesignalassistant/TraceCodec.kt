package com.example.livesignalassistant

import android.util.Base64
import java.util.zip.Deflater

/**
 * Compact lossless encoding of one extracted trace for the memory log (diagnostics only; never read back by the bot).
 * Layout before deflate: [n:uint16][n x y:uint16 (rounded pixel row)][ceil(n/8) bytes of "real pick" bits, LSB first].
 * Then deflate (raw zlib) and Base64 (no wrap). See validation/decode_trace.py.
 */
object TraceCodec {
    fun encode(tr: TraceResult): String {
        val n = tr.ys.size
        val bitBytes = (n + 7) / 8
        val raw = ByteArray(2 + 2 * n + bitBytes)
        raw[0] = (n shr 8).toByte()
        raw[1] = n.toByte()
        for (i in 0 until n) {
            val v = Math.round(tr.ys[i]).toInt().coerceIn(0, 32767)
            raw[2 + 2 * i] = (v shr 8).toByte()
            raw[3 + 2 * i] = v.toByte()
        }
        for (i in 0 until n) {
            if (tr.real[i]) {
                val idx = 2 + 2 * n + i / 8
                raw[idx] = (raw[idx].toInt() or (1 shl (i % 8))).toByte()
            }
        }
        val d = Deflater(Deflater.BEST_SPEED)
        d.setInput(raw)
        d.finish()
        val out = ByteArray(raw.size + 64)
        val len = d.deflate(out)
        d.end()
        return Base64.encodeToString(out, 0, len, Base64.NO_WRAP)
    }
}
