package com.example.catnav

import android.app.Application
import org.maplibre.android.MapLibre

class CatNavApplication : Application() {
    val appState: CatNavAppState by lazy { CatNavAppState(this) }

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
    }
}
