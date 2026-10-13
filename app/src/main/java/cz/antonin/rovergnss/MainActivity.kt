package cz.antonin.rovergnss

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import cz.antonin.rovergnss.ui.RoverUi

class MainActivity : ComponentActivity() {
    private val permissionsOk = mutableStateOf(false)

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { checkPermissions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        // obsah pod systémovými lištami; tmavé ikony lišt na světlém pozadí (Android 15 barvu lišty z tématu ignoruje)
        val bars = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        super.onCreate(savedInstanceState)
        checkPermissions()
        if (!permissionsOk.value) requestPermissions.launch(neededPermissions())
        val controller = (application as RoverApp).controller
        // „Ukončit“ (tlačítko, oznámení, odsunutí ze seznamu) zavře i okno
        lifecycleScope.launch { controller.exit.collect { finishAndRemoveTask() } }
        setContent {
            RoverUi(
                controller = controller,
                permissionsOk = permissionsOk.value,
                onRequestPermissions = { requestPermissions.launch(neededPermissions()) },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        (application as RoverApp).controller.foreground = true
    }

    override fun onStop() {
        (application as RoverApp).controller.foreground = false
        super.onStop()
    }

    private fun neededPermissions(): Array<String> {
        val p = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) p += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) p += Manifest.permission.POST_NOTIFICATIONS
        return p.toTypedArray()
    }

    /** Bez BLUETOOTH_CONNECT (Android 12+) se k roveru nelze připojit; oznámení jsou volitelná. */
    private fun checkPermissions() {
        permissionsOk.value = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }
}
