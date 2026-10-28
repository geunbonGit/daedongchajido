package com.odomap.app.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import com.odomap.app.OdomapApp
import com.odomap.app.record.RecordingService
import com.odomap.app.ui.design.CarScale

/**
 * One activity. Pleos multi-window resizes it at runtime (wide 1700×1184 ↔ narrow 840×1184): the manifest keeps the activity across size /
 * night-mode changes (configChanges), so Compose simply re-lays out and nothing is lost.
 */
class MainActivity : ComponentActivity() {
    private val hasLocation = mutableStateOf(false)

    private val askPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshPermission()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as OdomapApp
        refreshPermission()
        setContent {
            CarScale {
                OdoAppUi(
                    recorder = app.recorder,
                    looks = app.looks,
                    hasLocation = hasLocation.value,
                    onAskPermission = ::askForPermissions,
                )
            }
        }
    }

    private fun askForPermissions() {
        val wanted = arrayListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        if (packageManager.hasSystemFeature("android.hardware.type.automotive")) wanted.add("android.car.permission.CAR_SPEED")
        askPermissions.launch(wanted.toTypedArray())
    }

    private fun refreshPermission() {
        hasLocation.value = RecordingService.canRun(this)
        if (hasLocation.value) RecordingService.start(this)
    }
}
