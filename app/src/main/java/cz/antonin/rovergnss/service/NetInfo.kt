package cz.antonin.rovergnss.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build

/** Síť telefonu pro diagnostiku (souběh Wi-Fi 2,4 GHz a Bluetooth). */
object NetInfo {
    fun type(context: Context): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "zadna"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                    val freq = wifiFrequency(context, caps)
                    when {
                        freq in 2400..2500 -> "wifi2.4"
                        freq >= 4900 -> "wifi5"
                        else -> "wifi"
                    }
                }
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobil"
                else -> "jina"
            }
        } catch (e: Exception) {
            "?"
        }
    }

    @Suppress("DEPRECATION")
    private fun wifiFrequency(context: Context, caps: NetworkCapabilities): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (caps.transportInfo as? WifiInfo)?.let { return it.frequency }
        }
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return wm.connectionInfo?.frequency ?: 0
    }

    /** Přijato ze sítě celkem (všechny aplikace) – vzestup = provoz Wi-Fi/mobilu. */
    fun rxBytes(): Long = TrafficStats.getTotalRxBytes().coerceAtLeast(0)
}
