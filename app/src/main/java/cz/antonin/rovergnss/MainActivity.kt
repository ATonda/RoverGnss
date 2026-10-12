package cz.antonin.rovergnss

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import cz.antonin.rovergnss.ui.RoverUi

class MainActivity : ComponentActivity() {
    private val permissionsOk = mutableStateOf(false)

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { checkPermissions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkPermissions()
        if (!permissionsOk.value) requestPermissions.launch(neededPermissions())
        val controller = (application as RoverApp).controller
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
