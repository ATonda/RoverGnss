package cz.antonin.rovergnss.link

/**
 * Rozdělí proud bajtů z roveru na:
 *  - textové řádky začínající '$' (NMEA, $PRVR) nebo '{' (JSON),
 *  - rámce UBX (0xB5 0x62 …) – pro záznam RAWX (etapa 3).
 * Ostatní bajty zahodí. Není vláknově bezpečné – volat z jednoho vlákna čtení.
 */
class StreamParser(
    private val onLine: (String) -> Unit,
    private val onUbx: (ByteArray) -> Unit = {},
) {
    private enum class St { IDLE, TEXT, UBX }

    private var st = St.IDLE
    private val text = StringBuilder(256)
    private var ubx = ByteArray(1024)
    private var ubxPos = 0
    private var ubxLen = -1

    var droppedLines = 0L
        private set
    var badUbx = 0L
        private set

    fun feed(buf: ByteArray, n: Int) {
        for (i in 0 until n) feed(buf[i])
    }

    private fun feed(b: Byte) {
        val c = b.toInt() and 0xFF
        when (st) {
            St.IDLE -> when (c) {
                '$'.code, '{'.code -> {
                    text.setLength(0)
                    text.append(c.toChar())
                    utfLeft = 0
                    st = St.TEXT
                }
                0xB5 -> {
                    ubxPos = 0
                    ubxLen = -1
                    putUbx(b)
                    st = St.UBX
                }
            }
            St.TEXT -> when {
                c == '\n'.code -> {
                    st = St.IDLE
                    val s = text.toString().trimEnd('\r')
                    if (s.isNotEmpty()) onLine(s)
                }
                c == '$'.code && text.isNotEmpty() && text[0] == '$' -> {
                    // nová věta bez ukončení předchozí – předchozí je useknutá
                    droppedLines++
                    text.setLength(0)
                    text.append('$')
                }
                c < 0x20 && c != '\r'.code || text.length > MAX_LINE -> {
                    droppedLines++
                    st = St.IDLE
                }
                else -> text.append(decodeByte(c))
            }
            St.UBX -> {
                putUbx(b)
                if (ubxPos == 2 && c != 0x62) {
                    st = St.IDLE
                    return
                }
                if (ubxPos == 6) {
                    ubxLen = (ubx[4].toInt() and 0xFF) or ((ubx[5].toInt() and 0xFF) shl 8)
                    if (ubxLen > MAX_UBX) {
                        badUbx++
                        st = St.IDLE
                        return
                    }
                }
                if (ubxLen >= 0 && ubxPos == ubxLen + 8) {
                    st = St.IDLE
                    if (ubxChecksumOk()) onUbx(ubx.copyOf(ubxPos)) else badUbx++
                }
            }
        }
    }

    // Texty z roveru jsou v UTF-8 (čeština v JSON). Skládání vícebajtových znaků:
    private val utf = java.io.ByteArrayOutputStream(4)
    private var utfLeft = 0

    private fun decodeByte(c: Int): String {
        if (utfLeft == 0) {
            when {
                c < 0x80 -> return c.toChar().toString()
                c and 0xE0 == 0xC0 -> utfLeft = 1
                c and 0xF0 == 0xE0 -> utfLeft = 2
                c and 0xF8 == 0xF0 -> utfLeft = 3
                else -> return "?"
            }
            utf.reset()
            utf.write(c)
            return ""
        }
        utf.write(c)
        utfLeft--
        return if (utfLeft == 0) String(utf.toByteArray(), Charsets.UTF_8) else ""
    }

    private fun putUbx(b: Byte) {
        if (ubxPos >= ubx.size) ubx = ubx.copyOf(ubx.size * 2)
        ubx[ubxPos++] = b
    }

    private fun ubxChecksumOk(): Boolean {
        var a = 0
        var b = 0
        for (i in 2 until ubxPos - 2) {
            a = (a + (ubx[i].toInt() and 0xFF)) and 0xFF
            b = (b + a) and 0xFF
        }
        return a == (ubx[ubxPos - 2].toInt() and 0xFF) && b == (ubx[ubxPos - 1].toInt() and 0xFF)
    }

    companion object {
        const val MAX_LINE = 8192
        const val MAX_UBX = 8192
    }
}
