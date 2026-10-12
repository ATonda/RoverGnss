package cz.antonin.rovergnss.ntrip

import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

/** Nastavení připojení k casteru NTRIP. */
data class NtripProfile(
    val name: String = "",
    val host: String = "",
    val port: Int = 2101,
    val mount: String = "",
    val user: String = "",
    val pass: String = "",
    val ggaSec: Int = 10,      // posílat polohu (GGA) casteru; 0 = ne (pevná báze)
    val v2: Boolean = false,   // NTRIP 2.0 (HTTP/1.1), jinak 1.0
)

sealed class NtripState {
    data object Off : NtripState()
    data class Connecting(val attempt: Int, val lastError: String?) : NtripState()
    data class Streaming(val mount: String) : NtripState()
    data class Failed(val error: String) : NtripState()   // chyba, kterou opakování nevyřeší (heslo, mountpoint)
}

class NtripException(msg: String, val fatal: Boolean) : IOException(msg)

/**
 * Klient NTRIP 1.0/2.0: příjem RTCM z casteru, odesílání GGA. Po výpadku se znovu připojuje.
 */
class NtripClient(
    private val scope: CoroutineScope,
    private val onRtcm: (ByteArray) -> Unit,
    private val ggaProvider: () -> String?,
) {
    private val _state = MutableStateFlow<NtripState>(NtripState.Off)
    val state: StateFlow<NtripState> = _state.asStateFlow()

    val bytes = AtomicLong()
    @Volatile var lastDataMs = 0L
        private set

    private var job: Job? = null
    @Volatile private var socket: Socket? = null

    fun start(p: NtripProfile) {
        stop()
        job = scope.launch(Dispatchers.IO) {
            var attempt = 0
            var lastError: String? = null
            while (isActive) {
                attempt++
                _state.value = NtripState.Connecting(attempt, lastError)
                try {
                    session(p) { _state.value = NtripState.Streaming(p.mount); attempt = 0 }
                    lastError = "caster ukončil spojení"
                } catch (e: NtripException) {
                    if (e.fatal) {
                        _state.value = NtripState.Failed(e.message ?: "chyba")
                        return@launch
                    }
                    lastError = e.message
                } catch (e: IOException) {
                    lastError = e.message ?: e.javaClass.simpleName
                } finally {
                    closeSocket()
                }
                delay((2000L * attempt).coerceAtMost(20_000L))
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        closeSocket()
        _state.value = NtripState.Off
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
    }

    private suspend fun session(p: NtripProfile, onStreaming: () -> Unit) {
        val s = Socket()
        socket = s
        s.connect(InetSocketAddress(p.host, p.port), 10_000)
        s.soTimeout = 30_000  // bez dat 30 s = výpadek
        val out = s.getOutputStream()
        val inp = BufferedInputStream(s.getInputStream())
        val gga = if (p.ggaSec > 0) ggaProvider() else null
        out.write(request(p, "/" + p.mount, gga).toByteArray(Charsets.ISO_8859_1))
        out.flush()

        val status = readLine(inp) ?: throw IOException("caster neodpověděl")
        var chunked = false
        when {
            status.startsWith("ICY 200") -> {}
            status.startsWith("HTTP/") && status.contains(" 200") -> {
                while (true) {
                    val h = readLine(inp) ?: throw IOException("neúplná hlavička")
                    if (h.isEmpty()) break
                    if (h.lowercase().startsWith("transfer-encoding:") && h.lowercase().contains("chunked")) chunked = true
                    if (h.lowercase().startsWith("content-type:") && h.lowercase().contains("sourcetable"))
                        throw NtripException("mountpoint „${p.mount}“ neexistuje", true)
                }
            }
            status.startsWith("SOURCETABLE") -> throw NtripException("mountpoint „${p.mount}“ neexistuje", true)
            status.contains(" 401") -> throw NtripException("špatné jméno nebo heslo", true)
            status.contains(" 404") -> throw NtripException("mountpoint „${p.mount}“ neexistuje", true)
            else -> throw IOException("caster: $status")
        }
        onStreaming()

        // GGA casteru (síťová řešení potřebují polohu roveru)
        val ggaJob = if (p.ggaSec > 0) scope.launch(Dispatchers.IO) {
            if (gga != null) delay(p.ggaSec * 1000L)  // první GGA šla s požadavkem
            while (isActive) {
                ggaProvider()?.let { sendGga(out, it, chunked = false) }
                delay(p.ggaSec * 1000L)
            }
        } else null

        try {
            val src: InputStream = if (chunked) ChunkedInputStream(inp) else inp
            val buf = ByteArray(2048)
            while (true) {
                val n = src.read(buf)
                if (n < 0) break
                if (n > 0) {
                    bytes.addAndGet(n.toLong())
                    lastDataMs = System.currentTimeMillis()
                    onRtcm(buf.copyOf(n))
                }
            }
        } finally {
            ggaJob?.cancel()
        }
    }

    private fun sendGga(out: OutputStream, gga: String, chunked: Boolean) {
        try {
            val line = (gga.trim() + "\r\n").toByteArray(Charsets.ISO_8859_1)
            synchronized(out) {
                if (chunked) out.write("${Integer.toHexString(line.size)}\r\n".toByteArray())
                out.write(line)
                if (chunked) out.write("\r\n".toByteArray())
                out.flush()
            }
        } catch (_: IOException) {
        }
    }

    /** Seznam mountpointů ze sourcetable casteru. */
    suspend fun fetchMounts(p: NtripProfile): List<String> = withContext(Dispatchers.IO) {
        Socket().use { s ->
            s.connect(InetSocketAddress(p.host, p.port), 10_000)
            s.soTimeout = 15_000
            s.getOutputStream().write(request(p, "/", null).toByteArray(Charsets.ISO_8859_1))
            val text = s.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
            text.lineSequence()
                .filter { it.startsWith("STR;") }
                .mapNotNull { it.split(';').getOrNull(1) }
                .filter { it.isNotEmpty() }
                .sorted()
                .toList()
        }
    }

    private fun request(p: NtripProfile, path: String, gga: String?): String {
        val sb = StringBuilder()
        if (p.v2) {
            sb.append("GET $path HTTP/1.1\r\n")
            sb.append("Host: ${p.host}:${p.port}\r\n")
            sb.append("Ntrip-Version: Ntrip/2.0\r\n")
        } else {
            sb.append("GET $path HTTP/1.0\r\n")
        }
        sb.append("User-Agent: NTRIP RoverGnss/0.1\r\n")
        if (p.user.isNotEmpty()) {
            val auth = Base64.encodeToString("${p.user}:${p.pass}".toByteArray(), Base64.NO_WRAP)
            sb.append("Authorization: Basic $auth\r\n")
        }
        if (p.v2 && gga != null) sb.append("Ntrip-GGA: ${gga.trim()}\r\n")
        sb.append("Connection: close\r\n\r\n")
        if (!p.v2 && gga != null) sb.append(gga.trim()).append("\r\n")
        return sb.toString()
    }

    private fun readLine(inp: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = inp.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 1024) throw IOException("příliš dlouhá hlavička")
            sb.append(c.toChar())
        }
    }

    /** Dekódování HTTP chunked (NTRIP 2.0). */
    private inner class ChunkedInputStream(private val inp: InputStream) : InputStream() {
        private var left = 0
        private var eof = false

        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (eof) return -1
            if (left == 0) {
                var line = readLine(inp) ?: return -1
                if (line.isEmpty()) line = readLine(inp) ?: return -1  // CRLF za předchozím blokem
                left = line.substringBefore(';').trim().toIntOrNull(16) ?: throw IOException("chybný blok chunked")
                if (left == 0) {
                    eof = true
                    return -1
                }
            }
            val n = inp.read(b, off, minOf(len, left))
            if (n > 0) left -= n
            return n
        }
    }
}
