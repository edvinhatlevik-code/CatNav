package com.example.catnav.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.catnav.CatNavAppState
import com.example.catnav.data.TrackerSetting
import com.example.catnav.data.TrackerSettings

@Composable
internal fun SettingsScreen(
    state: CatNavAppState,
    onRequestBatteryOptimizationExemption: () -> Unit,
    onOpenNotificationSettings: () -> Unit
) {
    var gatewayUrl by rememberSaveable { mutableStateOf(state.preferences.gatewayBaseUrl) }
    var apiToken by rememberSaveable { mutableStateOf(state.preferences.apiToken) }
    var syncInterval by rememberSaveable { mutableStateOf(state.preferences.syncThresholdMinutes.toString()) }
    var autoFetch by rememberSaveable { mutableStateOf(state.preferences.autoFetchEnabled) }
    var monitorEvents by rememberSaveable { mutableStateOf(state.preferences.eventsEnabled) }
    var configTrackerId by rememberSaveable {
        mutableStateOf(state.selectedTrackerId ?: state.trackers.firstOrNull()?.trackerId)
    }
    var configError by rememberSaveable { mutableStateOf<String?>(null) }
    val formValues = remember(configTrackerId) {
        mutableStateMapOf<Int, String>().apply {
            val saved = configTrackerId?.let(state::localConfiguration).orEmpty()
            TrackerSettings.all.forEach { setting ->
                put(
                    setting.id,
                    setting.displayValue(saved[setting.id] ?: setting.defaultApiValue).toString()
                )
            }
        }
    }

    LaunchedEffect(state.trackers, state.selectedTrackerId) {
        if (configTrackerId == null || state.trackers.none { it.trackerId == configTrackerId }) {
            configTrackerId = state.selectedTrackerId ?: state.trackers.firstOrNull()?.trackerId
        }
    }
    LaunchedEffect(state.gatewayBaseUrl) {
        gatewayUrl = state.gatewayBaseUrl
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/xml")
    ) { uri -> uri?.let(state::exportDatabase) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(state::importDatabase) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 15.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column {
            Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Your gateway connection, local history and tracker configuration.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SettingsCard(
            icon = Icons.Filled.Dns,
            title = "Local gateway",
            subtitle = "CatNav connects directly over your Wi-Fi network."
        ) {
            OutlinedTextField(
                value = gatewayUrl,
                onValueChange = { gatewayUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Gateway address") },
                placeholder = { Text("http://cat-gateway.local") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supportingText = { Text("Use cat-gateway.local or enter a manual IP fallback. Save before refreshing.") }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedButton(
                    onClick = { state.discoverGateway() },
                    enabled = !state.isDiscovering,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    if (state.isDiscovering) {
                        CircularProgressIndicator(modifier = Modifier.height(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Discover gateway")
                    }
                }
                Button(onClick = { state.refreshGateway() }, modifier = Modifier.weight(1f)) {
                    Text("Refresh saved connection")
                }
            }
            OutlinedTextField(
                value = apiToken,
                onValueChange = { apiToken = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Gateway API token") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                supportingText = { Text("Sent in the Authorization header; keep the gateway on a trusted network.") }
            )
        }

        SettingsCard(
            icon = Icons.Filled.CloudDownload,
            title = "Automatic data sync",
            subtitle = "On app open, queue FETCH only for active trackers whose local history is stale."
        ) {
            SettingsSwitchRow(
                title = "Fetch active trackers automatically",
                checked = autoFetch,
                onCheckedChange = { autoFetch = it }
            )
            OutlinedTextField(
                value = syncInterval,
                onValueChange = { syncInterval = it.filter(Char::isDigit).take(4) },
                label = { Text("Fetch again after (minutes)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = { Text("Choose 5–1440 minutes to avoid overloading the gateway job queue.") }
            )
        }

        SettingsCard(
            icon = Icons.Filled.BatteryAlert,
            title = "Background alerts",
            subtitle = "A foreground service keeps the authenticated Server-Sent Events stream connected."
        ) {
            SettingsSwitchRow(
                title = "Monitor tracker alerts in background",
                checked = monitorEvents,
                onCheckedChange = { monitorEvents = it }
            )
            Text(
                "CatNav reconciles active low-battery alerts after reconnecting and sends phone notifications. " +
                    "For the most reliable delivery, allow CatNav to run unrestricted in Android battery settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = onRequestBatteryOptimizationExemption,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Security, contentDescription = null)
                Spacer(Modifier.width(7.dp))
                Text("Open battery optimization settings")
            }
            OutlinedButton(
                onClick = onOpenNotificationSettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.NotificationsActive, contentDescription = null)
                Spacer(Modifier.width(7.dp))
                Text("Manage notification permission")
            }
        }

        Button(
            onClick = {
                val interval = syncInterval.toIntOrNull()
                if (interval == null) {
                    state.saveGatewaySettings(gatewayUrl, apiToken, -1, autoFetch, monitorEvents)
                } else {
                    state.saveGatewaySettings(gatewayUrl, apiToken, interval, autoFetch, monitorEvents)
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save connection settings")
        }

        SettingsCard(
            icon = Icons.Filled.Tune,
            title = "Tracker configuration",
            subtitle = "Configuration is stored locally and sent only while the tracker is active."
        ) {
            if (state.trackers.isEmpty()) {
                Text(
                    "Add or discover a tracker to edit its configuration.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    state.trackers.forEach { tracker ->
                        FilterChip(
                            selected = configTrackerId == tracker.trackerId,
                            onClick = {
                                configTrackerId = tracker.trackerId
                                state.selectTracker(tracker.trackerId)
                                configError = null
                            },
                            label = { Text(formatTrackerId(tracker.trackerId)) }
                        )
                    }
                }
                val tracker = state.trackers.firstOrNull { it.trackerId == configTrackerId }
                if (tracker != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TrackerStatePill(tracker)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (tracker.isActive) "Ready for configuration sync"
                            else "Sync disabled while tracker is dormant",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TrackerSettings.all.forEach { setting ->
                    TrackerSettingField(
                        setting = setting,
                        value = formValues[setting.id].orEmpty(),
                        onValueChange = {
                            formValues[setting.id] = it.filter { character -> character.isDigit() }.take(6)
                            configError = null
                        }
                    )
                }
                Text(
                    "Changing CRITICAL_BATTERY_MILLIVOLTS also moves 0% on the battery indicator to that voltage.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                configError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = {
                        val id = configTrackerId ?: return@Button
                        val values = mutableMapOf<Int, Long>()
                        for (setting in TrackerSettings.all) {
                            val displayValue = formValues[setting.id]?.toLongOrNull()
                            if (displayValue == null) {
                                configError = "Enter a number for ${setting.title}."
                                return@Button
                            }
                            values[setting.id] = setting.apiValue(displayValue)
                        }
                        if (state.saveConfiguration(id, values)) configError = null
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save configuration on phone")
                }
                OutlinedButton(
                    onClick = { configTrackerId?.let(state::syncConfiguration) },
                    enabled = tracker?.isActive == true && configTrackerId !in state.syncingTrackerIds,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Sync, contentDescription = null)
                    Spacer(Modifier.width(7.dp))
                    Text(
                        if (configTrackerId in state.syncingTrackerIds) "Syncing selected tracker…"
                        else "Sync selected active tracker"
                    )
                }
            }
            HorizontalDivider()
            OutlinedButton(
                onClick = state::syncAllActiveConfigurations,
                enabled = state.trackers.any { it.isActive } &&
                    state.syncingTrackerIds.isEmpty() &&
                    !state.isSyncingAll,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Sync, contentDescription = null)
                Spacer(Modifier.width(7.dp))
                Text(if (state.isSyncingAll) "Syncing all active trackers…" else "Sync all active trackers")
            }
        }

        SettingsCard(
            icon = Icons.Filled.CloudUpload,
            title = "Local history transfer",
            subtitle = "Export an XML archive or merge one into the permanent local database."
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedButton(
                    onClick = { exportLauncher.launch("catnav-history.xml") },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.CloudUpload, contentDescription = null, modifier = Modifier.height(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Export XML")
                }
                Button(
                    onClick = { importLauncher.launch(arrayOf("application/xml", "text/xml")) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.height(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Import XML")
                }
            }
            Text(
                "${state.totalLocations} location records saved on this phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Text(
            "CatNav uses your local gateway; tracker history is not sent to a cloud service. Map tiles are provided by OpenStreetMap.",
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SettingsCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(title, fontWeight = FontWeight.Bold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            content()
        }
    }
}

@Composable
private fun SettingsSwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun TrackerSettingField(
    setting: TrackerSetting,
    value: String,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("${setting.title} (${setting.unit})") },
        supportingText = { Text("Allowed: ${setting.minimum}–${setting.maximum} ${setting.unit}") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}
