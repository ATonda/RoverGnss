package cz.antonin.rovergnss

import android.app.Application
import cz.antonin.rovergnss.data.AppDb
import cz.antonin.rovergnss.data.Prefs
import cz.antonin.rovergnss.service.RoverController
import org.maplibre.android.MapLibre

class RoverApp : Application() {
    lateinit var controller: RoverController
        private set

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        controller = RoverController(this, AppDb.create(this), Prefs(this))
    }
}
