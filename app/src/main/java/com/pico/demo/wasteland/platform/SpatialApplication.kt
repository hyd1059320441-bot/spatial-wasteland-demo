package com.pico.demo.wasteland.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import com.pico.demo.wasteland.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        launch(::mainApp)
    }
}
