package cz.antonin.rovergnss.ntrip

/**
 * Skládá proud z casteru do celých rámců RTCM3 (0xD3, délka, data, CRC-24Q).
 * Rámce se špatným CRC a bajty mimo rámce se zahodí.
 */
class RtcmFramer(private val onFrame: (ByteArray) -> Unit) {
    private val buf = ByteArray(1029 * 4)
    private var len = 0

    var frames = 0L
        private set
    var badCrc = 0L
        private set
    var lastType = 0
        private set

    fun feed(data: ByteArray) {
        var i = 0
        while (i < data.size) {
            val n = minOf(data.size - i, buf.size - len)
            System.arraycopy(data, i, buf, len, n)
            len += n
            i += n
            extract()
        }
    }

    private fun extract() {
        var p = 0
        while (true) {
            while (p < len && (buf[p].toInt() and 0xFF) != 0xD3) p++
            if (len - p < 3) break
            val b1 = buf[p + 1].toInt() and 0xFF
            if (b1 and 0xFC != 0) {  // rezervované bity – není to začátek rámce
                p++
                continue
            }
            val l = ((b1 and 0x03) shl 8) or (buf[p + 2].toInt() and 0xFF)
            val total = l + 6
            if (len - p < total) break
            val crc = ((buf[p + l + 3].toInt() and 0xFF) shl 16) or
                ((buf[p + l + 4].toInt() and 0xFF) shl 8) or (buf[p + l + 5].toInt() and 0xFF)
            if (crc24q(buf, p, l + 3) == crc) {
                frames++
                if (l >= 2) lastType = ((buf[p + 3].toInt() and 0xFF) shl 4) or ((buf[p + 4].toInt() and 0xFF) shr 4)
                onFrame(buf.copyOfRange(p, p + total))
                p += total
            } else {
                badCrc++
                p++
            }
        }
        if (p > 0) {
            System.arraycopy(buf, p, buf, 0, len - p)
            len -= p
        }
    }

    private fun crc24q(d: ByteArray, off: Int, n: Int): Int {
        var crc = 0
        for (i in off until off + n) {
            crc = crc xor ((d[i].toInt() and 0xFF) shl 16)
            repeat(8) {
                crc = crc shl 1
                if (crc and 0x1000000 != 0) crc = crc xor 0x1864CFB
            }
        }
        return crc and 0xFFFFFF
    }
}
