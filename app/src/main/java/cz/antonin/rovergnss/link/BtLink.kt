package cz.antonin.rovergnss.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Stav spojení s roverem. */
sealed class LinkState {
    data object Idle : LinkState()
    data class Connecting(val name: String, val attempt: Int, val lastError: String?) : LinkState()
    data class Connected(val name: String, val address: String) : LinkState()
}

/**
 * Spojení Bluetooth Classic SPP s roverem. Po přerušení se samo obnovuje, dokud se nezavolá [disconnect].
 * Oprávnění BLUETOOTH_CONNECT kontroluje UI před voláním [connect].
 */
@SuppressLint("MissingPermission")
class BtLink(
    context: Context,
    private val scope: CoroutineScope,
    private val onBytes: (ByteArray, Int) -> Unit,
) {
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    val rxBytes = AtomicLong()
    val txBytes = AtomicLong()
    val txDropped = AtomicLong()

    private var job: Job? = null
    private var socket: BluetoothSocket? = null
    private var txQueue = Channel<ByteArray>(TX_QUEUE)

    val isEnabled: Boolean get() = adapter?.isEnabled == true

    /** Spárovaná zařízení (rover se páruje v nastavení Androidu). */
    fun bondedDevices(): List<BluetoothDevice> =
        adapter?.bondedDevices?.sortedBy { it.name ?: it.address } ?: emptyList()

    fun connect(address: String) {
        disconnect()
        val dev = adapter?.getRemoteDevice(address) ?: return
        val name = dev.name ?: address
        txQueue = Channel(TX_QUEUE)
        job = scope.launch(Dispatchers.IO) {
            var attempt = 0
            var lastError: String? = null
            while (isActive) {
                attempt++
                _state.value = LinkState.Connecting(name, attempt, lastError)
                val s = try {
                    open(dev, attempt)
                } catch (e: IOException) {
                    lastError = e.message ?: "spojení selhalo"
                    delay(backoff(attempt))
                    continue
                }
                socket = s
                attempt = 0
                lastError = null
                _state.value = LinkState.Connected(name, address)
                val writer = launch { writeLoop(s) }
                try {
                    val input = s.inputStream
                    val buf = ByteArray(4096)
                    while (isActive) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n > 0) {
                            rxBytes.addAndGet(n.toLong())
                            onBytes(buf, n)
                        }
                    }
                } catch (e: IOException) {
                    lastError = "spojení přerušeno"
                } finally {
                    writer.cancel()
                    closeQuietly(s)
                    socket = null
                }
                if (isActive) delay(1000)
            }
        }
    }

    fun disconnect() {
        job?.cancel()
        job = null
        socket?.let { closeQuietly(it) }  // přeruší blokující read
        socket = null
        txQueue.close()
        _state.value = LinkState.Idle
    }

    /** Data do roveru (RTCM, příkazy). Neblokuje; při plné frontě se dávka zahodí. */
    fun send(data: ByteArray) {
        if (_state.value !is LinkState.Connected) return
        if (txQueue.trySend(data).isFailure) txDropped.incrementAndGet()
    }

    private suspend fun writeLoop(s: BluetoothSocket) {
        val out = s.outputStream
        try {
            for (d in txQueue) {
                out.write(d)
                txBytes.addAndGet(d.size.toLong())
            }
        } catch (e: IOException) {
            closeQuietly(s)  // čtení skončí a spojení se obnoví
        }
    }

    private fun open(dev: BluetoothDevice, attempt: Int): BluetoothSocket {
        adapter?.cancelDiscovery()
        val s: BluetoothSocket = if (attempt % 2 == 1) {
            dev.createRfcommSocketToServiceRecord(SPP_UUID)
        } else {
            // záloha: přímo kanál 1 (některé zásobníky nenajdou záznam SDP)
            try {
                dev.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(dev, 1) as BluetoothSocket
            } catch (e: Exception) {
                dev.createRfcommSocketToServiceRecord(SPP_UUID)
            }
        }
        try {
            s.connect()
        } catch (e: IOException) {
            closeQuietly(s)
            throw e
        }
        return s
    }

    private fun closeQuietly(s: BluetoothSocket) {
        try {
            s.close()
        } catch (_: IOException) {
        }
    }

    private fun backoff(attempt: Int): Long = (1000L * attempt).coerceAtMost(10_000L)

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val TX_QUEUE = 256
    }
}
