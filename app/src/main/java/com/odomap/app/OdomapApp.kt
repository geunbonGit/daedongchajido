package com.odomap.app

import android.app.Application
import com.odomap.app.record.DriveRecorder
import com.odomap.app.tts.AndroidTtsSpeaker
import com.odomap.app.ui.design.LookStore
import org.maplibre.android.MapLibre

class OdomapApp : Application() {
    lateinit var recorder: DriveRecorder
        private set
    lateinit var looks: LookStore
        private set

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        looks = LookStore(this)
        recorder = DriveRecorder(this, filesDir, AndroidTtsSpeaker(this))
    }
}
