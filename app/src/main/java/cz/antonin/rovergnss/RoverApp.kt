package cz.antonin.rovergnss

import android.app.Application
import cz.antonin.rovergnss.data.AppDb
import cz.antonin.rovergnss.data.Prefs
import cz.antonin.rovergnss.geo.Sjtsk
import cz.antonin.rovergnss.service.EventLog
import cz.antonin.rovergnss.service.RoverController
import org.maplibre.android.MapLibre

class RoverApp : Application() {
    lateinit var controller: RoverController
        private set

    override fun onCreate() {
        super.onCreate()
        EventLog.init(this)
        MapLibre.getInstance(this)
        controller = RoverController(this, AppDb.create(this), Prefs(this))
        // tabulky ČÚZK pro přesný převod S-JTSK a Bpv (~0,3 s) – do načtení se počítá 7 parametry
        Thread {
            try {
                assets.open("table_yx_3_v1710.dat").use { Sjtsk.loadTable(it) }
                assets.open("CR-2005_v1005.dat").use { Sjtsk.loadGeoid(it) }
            } catch (e: Exception) {
                android.util.Log.e("RoverApp", "tabulky ČÚZK: $e")
            }
        }.start()
    }
}
