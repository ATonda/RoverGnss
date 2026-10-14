package cz.antonin.rovergnss.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import cz.antonin.rovergnss.MainActivity
import cz.antonin.rovergnss.R
import cz.antonin.rovergnss.RoverApp
import cz.antonin.rovergnss.link.LinkState
import cz.antonin.rovergnss.ntrip.NtripState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Služba na popředí: drží proces (BT, NTRIP, záznam) při zhasnutém displeji a ukazuje stav v oznámení.
 * Spouští a zastavuje ji RoverController podle toho, zda je něco aktivní.
 */
class RoverService : LifecycleService() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        if (!startFg(build("Spouštím…"))) {
            // systém službu na popředí odmítl (oprávnění, start z pozadí) – aplikace běží dál bez ní
            stopSelf()
            return
        }
        instance = this
        // CPU musí běžet i při zhasnutém displeji (příjem BT, NTRIP, zápis trasy)
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RoverGnss:link").apply { acquire() }

        val c = (application as RoverApp).controller
        lifecycleScope.launch {
            while (true) {
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIF_ID, build(statusText(c)))
                delay(2000)
            }
        }
    }

    /** Odsunutí ze seznamu aplikací: bez záznamu se aplikace ukončí, běžící záznam pokračuje. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val c = (application as RoverApp).controller
        if (c.recording.value == null) c.shutdown()
        super.onTaskRemoved(rootIntent)
    }

    /**
     * Služba na popředí: vždy „připojené zařízení“ (rover), s polohou telefonu navíc typ „poloha“ –
     * jinak Android při zhasnutém displeji polohu telefonu nedodává. Když systém typ polohy odmítne, jen bez něj.
     */
    private fun startFg(n: Notification): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return try {
                ServiceCompat.startForeground(this, NOTIF_ID, n, 0)
                true
            } catch (e: Exception) {
                Log.w(TAG, "startForeground odmítnuto: $e")
                false
            }
        }
        val c = (application as RoverApp).controller
        val base = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        val withLoc = c.phone.enabled.value.isNotEmpty() && c.phone.hasPermission()
        if (withLoc) try {
            ServiceCompat.startForeground(this, NOTIF_ID, n, base or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            return true
        } catch (e: Exception) {
            Log.w(TAG, "startForeground s polohou odmítnuto: $e")
        }
        return try {
            ServiceCompat.startForeground(this, NOTIF_ID, n, base)
            true
        } catch (e: Exception) {
            Log.w(TAG, "startForeground odmítnuto: $e")
            false
        }
    }

    override fun onDestroy() {
        instance = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun statusText(c: RoverController): String {
        val parts = ArrayList<String>()
        parts += when (val l = c.linkState.value) {
            is LinkState.Connected -> l.name
            is LinkState.Connecting -> "připojuji ${l.name}…"
            LinkState.Idle -> "rover odpojen"
            is LinkState.Failed -> "chyba spojení"
        }
        c.pos.value?.let { p ->
            parts += p.quality.label
            parts += String.format("±%.2f m", p.hAcc)
        }
        when (val n = c.ntripState.value) {
            is NtripState.Streaming -> parts += "NTRIP ${n.mount}"
            is NtripState.Connecting -> parts += "NTRIP připojuji"
            is NtripState.Failed -> parts += "NTRIP chyba"
            NtripState.Off -> {}
        }
        c.recording.value?.let { parts += "záznam ${it.points} b." }
        return parts.joinToString(" · ")
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun build(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val exit = PendingIntent.getBroadcast(
            this, 1, Intent(this, ExitReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val recording = (application as RoverApp).controller.recording.value != null
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .addAction(0, if (recording) "Ukončit (uloží záznam)" else "Ukončit", exit)
            .build()
    }

    /** Akce „Ukončit“ z oznámení. */
    class ExitReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            (context.applicationContext as RoverApp).controller.shutdown()
        }
    }

    companion object {
        private const val TAG = "RoverService"
        private const val CHANNEL = "link"
        private const val NOTIF_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RoverService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RoverService::class.java))
        }

        @Volatile private var instance: RoverService? = null

        /** Po zapnutí / vypnutí zdrojů polohy telefonu: obnovit typ služby na popředí (volat z popředí aplikace). */
        fun refreshType(context: Context) {
            val s = instance ?: return
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (instance === s) s.startFg(s.build(s.statusText((s.application as RoverApp).controller)))
            }
        }
    }
}
