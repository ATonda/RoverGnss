package cz.antonin.rovergnss.service

import cz.antonin.rovergnss.data.Prefs
import cz.antonin.rovergnss.ntrip.NtripState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Doba připojení k RTK (NTRIP) a cena od vynulování. */
data class RtkUsageState(
    val usedSec: Long,          // účtovaná doba včetně probíhajícího spojení [s]
    val sessions: Int,          // počet spojení
    val currentSec: Long?,      // probíhající spojení [s], null = nepřipojeno
    val pricePerHour: Double,   // [Kč/h]
    val resetMs: Long,          // čas vynulování (0 = nikdy)
) {
    val cost: Double get() = usedSec * pricePerHour / 3600.0
}

/**
 * Počítadlo RTK (0.9.0): doba se počítá od přijetí spojení casterem (stav Streaming) do jeho konce; každé spojení se
 * zaokrouhlí nahoru na celou sekundu (účtuje se každá započatá sekunda). Cena = doba × cena za hodinu / 3600, jen
 * celkem od vynulování. Probíhající spojení se každých 5 s ukládá – po pádu aplikace se při dalším startu započte
 * do posledního uloženého okamžiku.
 *
 * Nečinnost: RTK běží (platí se), ale nic ho nevyužívá – upozornění (bez vypnutí), když je rover odpojený > 1 min,
 * nebo neběží záznam ani měření bodu a rover stojí > 10 min. Jedno upozornění na každý úsek nečinnosti.
 */
class RtkUsage(
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    private val ntripState: StateFlow<NtripState>,
    private val isRoverConnected: () -> Boolean,
    private val isBusy: () -> Boolean,          // záznam trasy nebo měření bodu
    private val isRoverMoving: () -> Boolean,   // rover se pohybuje (rychlost GNSS nebo stav přístroje)
    private val warn: (String) -> Unit,
) {
    private val _state = MutableStateFlow(snapshot(null))
    val state: StateFlow<RtkUsageState> = _state.asStateFlow()

    private var sessStart = 0L

    init {
        // spojení přerušené pádem aplikace – započítat do posledního uloženého okamžiku
        if (prefs.rtkSessStart > 0) {
            val last = maxOf(prefs.rtkSessLast, prefs.rtkSessStart)
            prefs.rtkUsedSec += ceilSec(last - prefs.rtkSessStart)
            prefs.rtkSessions += 1
            prefs.rtkSessStart = 0
            prefs.rtkSessLast = 0
        }
        scope.launch {
            ntripState.collect { s ->
                val now = System.currentTimeMillis()
                if (s is NtripState.Streaming) {
                    if (sessStart == 0L) {
                        sessStart = now
                        prefs.rtkSessStart = now
                        prefs.rtkSessLast = now
                    }
                } else if (sessStart > 0) {
                    endSession(now)
                }
                _state.value = snapshot(now)
            }
        }
        scope.launch {
            var lastSave = 0L
            var disconnectedSince = 0L
            var idleSince = 0L
            var warnedDisc = false
            var warnedIdle = false
            while (true) {
                delay(1000)
                val now = System.currentTimeMillis()
                if (sessStart > 0 && now - lastSave >= 5000) {
                    lastSave = now
                    prefs.rtkSessLast = now
                }
                _state.value = snapshot(now)
                // nečinnost
                if (sessStart == 0L) {
                    disconnectedSince = 0; idleSince = 0; warnedDisc = false; warnedIdle = false
                    continue
                }
                if (!isRoverConnected()) {
                    if (disconnectedSince == 0L) disconnectedSince = now
                    if (!warnedDisc && now - disconnectedSince > DISCONNECTED_MS) {
                        warnedDisc = true
                        warn("RTK běží a platí se, ale rover je odpojený déle než 1 min")
                    }
                } else {
                    disconnectedSince = 0; warnedDisc = false
                }
                if (!isBusy() && !isRoverMoving()) {
                    if (idleSince == 0L) idleSince = now
                    if (!warnedIdle && now - idleSince > IDLE_MS) {
                        warnedIdle = true
                        warn("RTK běží a platí se: 10 min bez záznamu i měření a rover stojí")
                    }
                } else {
                    idleSince = 0; warnedIdle = false
                }
            }
        }
    }

    private fun endSession(now: Long) {
        prefs.rtkUsedSec += ceilSec(now - sessStart)
        prefs.rtkSessions += 1
        sessStart = 0
        prefs.rtkSessStart = 0
        prefs.rtkSessLast = 0
    }

    private fun snapshot(now: Long?): RtkUsageState {
        val t = now ?: System.currentTimeMillis()
        val cur = if (sessStart > 0) ceilSec(t - sessStart) else null
        return RtkUsageState(prefs.rtkUsedSec + (cur ?: 0), prefs.rtkSessions, cur, prefs.rtkPricePerHour, prefs.rtkResetMs)
    }

    fun setPrice(perHour: Double) {
        prefs.rtkPricePerHour = perHour.coerceAtLeast(0.0)
        _state.value = snapshot(null)
    }

    /** Vynulování: doba, počet spojení a cena od teď (probíhající spojení se počítá od vynulování). */
    fun reset() {
        val now = System.currentTimeMillis()
        prefs.rtkUsedSec = 0
        prefs.rtkSessions = 0
        prefs.rtkResetMs = now
        if (sessStart > 0) {
            sessStart = now
            prefs.rtkSessStart = now
            prefs.rtkSessLast = now
        }
        _state.value = snapshot(now)
    }

    companion object {
        private const val DISCONNECTED_MS = 60_000L
        private const val IDLE_MS = 10 * 60_000L
        fun ceilSec(ms: Long): Long = if (ms <= 0) 0 else (ms + 999) / 1000
    }
}
