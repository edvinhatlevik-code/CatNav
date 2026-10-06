package com.example.catnav

import android.app.Application

class CatNavApplication : Application() {
    val appState: CatNavAppState by lazy { CatNavAppState(this) }
}
