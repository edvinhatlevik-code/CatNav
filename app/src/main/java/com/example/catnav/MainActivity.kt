package com.example.catnav

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import android.content.pm.PackageManager
import androidx.activity.enableEdgeToEdge
import com.example.catnav.ui.CatNavApp
import com.example.catnav.ui.theme.CatNavTheme

class MainActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        (application as CatNavApplication).appState.setAppForeground(true)
    }

    override fun onStop() {
        (application as CatNavApplication).appState.setAppForeground(false)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        val appState = (application as CatNavApplication).appState
        setContent {
            CatNavTheme {
                val context = LocalContext.current
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    if (!granted) appState.notificationPermissionDenied()
                }
                LaunchedEffect(Unit) {
                    if (ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    appState.onAppOpened()
                }
                CatNavApp(
                    state = appState,
                    onRequestBatteryOptimizationExemption = ::requestBatteryOptimizationExemption,
                    onOpenNotificationSettings = ::openNotificationSettings
                )
            }
        }
    }

    private fun requestBatteryOptimizationExemption() {
        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    private fun openNotificationSettings() {
        startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        )
    }
}
