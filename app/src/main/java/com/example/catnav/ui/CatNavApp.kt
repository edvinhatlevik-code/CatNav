package com.example.catnav.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.catnav.CatNavAppState
import com.example.catnav.data.BatterySoc
import com.example.catnav.data.GatewayJob
import com.example.catnav.data.Tracker
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class MainTab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Filled.Home),
    MAP("Map", Icons.Filled.Map),
    DEVICES("Trackers", Icons.Filled.Devices),
    SETTINGS("Settings", Icons.Filled.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatNavApp(
    state: CatNavAppState,
    onRequestBatteryOptimizationExemption: () -> Unit,
    onOpenNotificationSettings: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by rememberSaveable { mutableStateOf(MainTab.HOME.name) }
    LaunchedEffect(state.message) {
        val currentMessage = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            message = currentMessage,
            withDismissAction = true,
            duration = SnackbarDuration.Short
        )
        if (state.message == currentMessage) state.clearMessage()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                MainTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab.name,
                        onClick = { selectedTab = tab.name },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { scaffoldPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
        ) {
            AppHeader(state, MainTab.valueOf(selectedTab))
            when (MainTab.valueOf(selectedTab)) {
                MainTab.HOME -> HomeScreen(
                    state = state,
                    onOpenMap = { selectedTab = MainTab.MAP.name },
                    onOpenTrackers = { selectedTab = MainTab.DEVICES.name }
                )
                MainTab.MAP -> MapScreen(state)
                MainTab.DEVICES -> TrackerScreen(state)
                MainTab.SETTINGS -> SettingsScreen(
                    state,
                    onRequestBatteryOptimizationExemption,
                    onOpenNotificationSettings
                )
            }
        }
    }
}

@Composable
private fun AppHeader(state: CatNavAppState, tab: MainTab) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(38.dp),
                shape = RoundedCornerShape(13.dp),
                color = MaterialTheme.colorScheme.primary
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Pets,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(23.dp)
                    )
                }
            }
            Spacer(Modifier.width(11.dp))
            Column {
                Text("CATNAV", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
                Text(tab.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = CircleShape,
                color = if (state.gatewayConnected) Color(0xFFE1F3EC) else Color(0xFFFFEFE9)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (state.gatewayConnected) Color(0xFF27956E) else Color(0xFFE17451))
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (state.gatewayConnected) "Gateway online" else "Gateway offline",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    state: CatNavAppState,
    onOpenMap: () -> Unit,
    onOpenTrackers: () -> Unit
) {
    val selected = state.trackers.firstOrNull { it.trackerId == state.selectedTrackerId }
        ?: state.trackers.firstOrNull()
    val critical = selected?.let { state.localConfiguration(it.trackerId)[5]?.toInt() } ?: 3_300
    val locations = state.locationsFor(selected?.trackerId)
    val latest = locations.lastOrNull()
    val activeCount = state.trackers.count { it.isActive }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 17.dp, bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Your cat, in view.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "A quiet little window into their adventures.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (state.isRefreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    FilledTonalButton(onClick = { state.refreshGateway() }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Refresh")
                    }
                }
            }
        }
        item {
            DashboardBanner(
                trackerCount = state.trackers.size,
                activeCount = activeCount,
                gatewayConnected = state.gatewayConnected
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard(
                    title = "Trackers",
                    value = state.trackers.size.toString(),
                    caption = "$activeCount active",
                    icon = Icons.Filled.Devices,
                    modifier = Modifier.weight(1f)
                )
                MetricCard(
                    title = "Saved places",
                    value = state.totalLocations.toString(),
                    caption = "on this phone",
                    icon = Icons.Filled.LocationOn,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        if (selected == null) {
            item {
                EmptyTrackerCard(
                    onAddTracker = onOpenTrackers,
                    onSettings = { state.refreshGateway() }
                )
            }
        } else {
            item {
                SectionHeading("Your tracker", "Latest reading and quick controls")
            }
            item {
                TrackerSummaryCard(
                    tracker = selected,
                    criticalMillivolts = critical,
                    latestLocation = latest,
                    onOpenMap = onOpenMap
                )
            }
            item {
                SectionHeading("Quick actions", "Commands are sent only when you tap")
            }
            item {
                TrackerActions(
                    onWake = { state.queueCommand(selected.trackerId, "WAKE") },
                    onFetch = { state.queueCommand(selected.trackerId, "FETCH") },
                    onSleep = { state.queueCommand(selected.trackerId, "SLEEP") },
                    onPowerSave = { state.queueCommand(selected.trackerId, "POWER_SAVE") },
                    enabled = selected.registered
                )
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    SectionHeading("Recent activity", "Asynchronous gateway jobs")
                }
                TextButton(onClick = onOpenTrackers) { Text("All trackers") }
            }
        }
        if (state.recentJobs.isEmpty()) {
            item {
                Text(
                    "No commands yet. Fetch locations or control a tracker to see job progress here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
        } else {
            items(state.recentJobs.take(4), key = { it.jobId }) { job ->
                JobRow(
                    job,
                    state.trackers.firstOrNull { it.trackerId == job.trackerId }?.displayName
                        ?: "Tracker ${formatTrackerId(job.trackerId)}"
                )
            }
        }
    }
}

@Composable
private fun DashboardBanner(trackerCount: Int, activeCount: Int, gatewayConnected: Boolean) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 19.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Adventures, without the guesswork.", color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                Text(
                    if (gatewayConnected) "$activeCount of $trackerCount trackers are active right now."
                    else "Connect to your local gateway to update tracker status.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.86f)
                )
            }
            Icon(Icons.Filled.Pets, contentDescription = null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(38.dp))
        }
    }
}

@Composable
private fun MetricCard(
    title: String,
    value: String,
    caption: String,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyTrackerCard(onAddTracker: () -> Unit, onSettings: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Filled.Pets, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(42.dp))
            Spacer(Modifier.height(10.dp))
            Text("No trackers yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Add a tracker ID or connect to the gateway to discover registered devices.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))
            Button(onClick = onAddTracker) { Text("Add a tracker") }
            TextButton(onClick = onSettings) { Text("Check gateway connection") }
        }
    }
}

@Composable
private fun TrackerSummaryCard(
    tracker: Tracker,
    criticalMillivolts: Int,
    latestLocation: com.example.catnav.data.LocationRecord?,
    onOpenMap: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(Modifier.padding(17.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(46.dp),
                    shape = RoundedCornerShape(15.dp),
                    color = Color(0xFFE6F3EF)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Pets, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(tracker.displayName, fontWeight = FontWeight.Bold)
                    Text(
                        tracker.lastSeenAtMs?.let { "Seen ${formatTimestamp(it)}" } ?: "Waiting for the first report",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TrackerStatePill(tracker)
            }
            Spacer(Modifier.height(17.dp))
            BatteryMeter(tracker.batteryMillivolts, criticalMillivolts, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(13.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f))
                    .clickable(onClick = onOpenMap)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Last location", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        latestLocation?.let { "${formatCoordinate(it.latitude)}, ${formatCoordinate(it.longitude)} · ${formatTimestamp(it.timestampMs)}" }
                            ?: "No saved location yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (tracker.lowBatteryLockout) {
                Spacer(Modifier.height(11.dp))
                Text(
                    "Low-battery lockout is active. Wake may be refused until the tracker recovers.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun TrackerActions(
    onWake: () -> Unit,
    onFetch: () -> Unit,
    onSleep: () -> Unit,
    onPowerSave: () -> Unit,
    enabled: Boolean = true
) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            FilledTonalButton(onClick = onWake, enabled = enabled, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) {
                Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(4.dp))
                Text("Wake")
            }
            Button(onClick = onFetch, enabled = enabled, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) {
                Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(4.dp))
                Text("Fetch")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            OutlinedButton(onClick = onSleep, enabled = enabled, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) {
                Icon(Icons.Filled.Bedtime, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(4.dp))
                Text("Sleep")
            }
            OutlinedButton(onClick = onPowerSave, enabled = enabled, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) {
                Icon(Icons.Filled.BatteryStd, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(4.dp))
                Text("Power save")
            }
        }
    }
}

@Composable
internal fun TrackerStatePill(tracker: Tracker) {
    val (text, color, foreground) = when {
        !tracker.registered -> Triple("Local only", Color(0xFFEDEFF4), Color(0xFF626B78))
        tracker.lowBatteryLockout -> Triple("Low battery", Color(0xFFFFE7DF), Color(0xFFA64027))
        tracker.isActive -> Triple("Active", Color(0xFFE2F3EA), Color(0xFF267B59))
        tracker.state.equals("POWER_SAVING", ignoreCase = true) ->
            Triple("Power saving", Color(0xFFE2F3EA), Color(0xFF267B59))
        tracker.state.equals("DORMANT", ignoreCase = true) -> Triple("Dormant", Color(0xFFEDEFF4), Color(0xFF626B78))
        else -> Triple(tracker.state.lowercase().replaceFirstChar { it.titlecase() }, Color(0xFFF0F2F1), Color(0xFF68736F))
    }
    Surface(shape = CircleShape, color = color) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = foreground
        )
    }
}

@Composable
internal fun BatteryMeter(millivolts: Int?, criticalMillivolts: Int, modifier: Modifier = Modifier) {
    val percent = BatterySoc.percent(millivolts, criticalMillivolts)
    val levelColor = when {
        percent == null -> MaterialTheme.colorScheme.primary
        percent <= 15 -> MaterialTheme.colorScheme.error
        percent <= 35 -> Color(0xFFE49A38)
        else -> MaterialTheme.colorScheme.primary
    }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.BatteryStd, contentDescription = null, tint = levelColor, modifier = Modifier.size(21.dp))
            Spacer(Modifier.width(7.dp))
            Text(
                percent?.let { "$it%" } ?: "—",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            Text(
                millivolts?.let { "$it mV" } ?: "No battery report",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(7.dp))
        LinearProgressIndicator(
            progress = { (percent ?: 0) / 100f },
            modifier = Modifier.fillMaxWidth().height(7.dp).clip(CircleShape),
            color = levelColor,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

@Composable
private fun SectionHeading(title: String, subtitle: String? = null) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TrackerScreen(state: CatNavAppState) {
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.trackers) {
        selectedIds = selectedIds intersect state.trackers.map { it.trackerId }.toSet()
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 17.dp, bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Your trackers", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Select one or more devices for a direct command.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilledTonalButton(onClick = { state.refreshGateway() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Refresh")
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Button(onClick = { showAddDialog = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add tracker")
                }
                OutlinedButton(
                    onClick = {
                        selectedIds = if (selectedIds.size == state.trackers.size) {
                            emptySet()
                        } else {
                            state.trackers.map { it.trackerId }.toSet()
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (selectedIds.size == state.trackers.size && selectedIds.isNotEmpty()) "Clear selection" else "Select all")
                }
            }
        }
        if (state.trackers.isEmpty()) {
            item {
                EmptyTrackerCard(
                    onAddTracker = { showAddDialog = true },
                    onSettings = { state.refreshGateway() }
                )
            }
        } else {
            items(state.trackers, key = { it.trackerId }) { tracker ->
                TrackerListCard(
                    tracker = tracker,
                    selected = tracker.trackerId in selectedIds,
                    criticalMillivolts = state.localConfiguration(tracker.trackerId)[5]?.toInt() ?: 3_300,
                    onCheckedChange = { checked ->
                        selectedIds = if (checked) selectedIds + tracker.trackerId else selectedIds - tracker.trackerId
                    },
                    onSelectTracker = { state.selectTracker(tracker.trackerId) }
                )
            }
            item {
                SectionHeading("Bulk controls", "${selectedIds.size} selected · commands are queued on the gateway")
            }
            item {
                TrackerActions(
                    onWake = { state.queueBulkCommand(selectedIds.toList(), "WAKE") },
                    onFetch = { state.queueBulkCommand(selectedIds.toList(), "FETCH") },
                    onSleep = { state.queueBulkCommand(selectedIds.toList(), "SLEEP") },
                    onPowerSave = { state.queueBulkCommand(selectedIds.toList(), "POWER_SAVE") },
                    enabled = state.trackers.any { it.trackerId in selectedIds && it.registered }
                )
            }
        }
        item {
            SectionHeading("Command activity", "Polls asynchronous job progress")
        }
        if (state.recentJobs.isEmpty()) {
            item {
                Text("No tracker commands have been sent yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            items(state.recentJobs.take(15), key = { "job-${it.jobId}" }) { job ->
                JobRow(
                    job,
                    state.trackers.firstOrNull { it.trackerId == job.trackerId }?.displayName
                        ?: "Tracker ${formatTrackerId(job.trackerId)}"
                )
            }
        }
    }
    if (showAddDialog) {
        AddTrackerDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { trackerId, catName ->
                state.registerTracker(trackerId, catName)
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun TrackerListCard(
    tracker: Tracker,
    selected: Boolean,
    criticalMillivolts: Int,
    onCheckedChange: (Boolean) -> Unit,
    onSelectTracker: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelectTracker),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = selected, onCheckedChange = onCheckedChange)
                Column(modifier = Modifier.weight(1f)) {
                    Text(tracker.displayName, fontWeight = FontWeight.Bold)
                    Text(
                        tracker.lastSeenAtMs?.let { "Last seen ${formatTimestamp(it)}" } ?: "No recent gateway contact",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TrackerStatePill(tracker)
            }
            Spacer(Modifier.height(5.dp))
            BatteryMeter(tracker.batteryMillivolts, criticalMillivolts)
        }
    }
}

@Composable
private fun AddTrackerDialog(onDismiss: () -> Unit, onAdd: (Long, String) -> Unit) {
    var trackerIdInput by rememberSaveable { mutableStateOf("") }
    var catNameInput by rememberSaveable { mutableStateOf("") }
    var validationError by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a tracker") },
        text = {
            Column {
                Text("Enter the tracker ID and a name to identify your cat in CatNav.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = trackerIdInput,
                    onValueChange = {
                        trackerIdInput = it.trim()
                        validationError = null
                    },
                    label = { Text("Tracker ID") },
                    placeholder = { Text("123456789 or 0x075BCD15") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    isError = validationError?.contains("ID") == true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = catNameInput,
                    onValueChange = {
                        catNameInput = it.take(40)
                        validationError = null
                    },
                    label = { Text("Cat Name") },
                    placeholder = { Text("e.g. Luna") },
                    singleLine = true,
                    isError = validationError?.contains("name") == true
                )
                validationError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val id = parseTrackerId(trackerIdInput)
                val catName = catNameInput.trim()
                if (id == null) {
                    validationError = "Enter a non-zero 32-bit decimal or hexadecimal ID."
                } else if (catName.isEmpty()) {
                    validationError = "Enter a name for your cat."
                } else {
                    onAdd(id, catName)
                }
            }) { Text("Register") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun JobRow(job: GatewayJob, trackerName: String) {
    val statusColor = when (job.status.uppercase()) {
        "COMPLETED" -> Color(0xFFE3F3EB)
        "FAILED", "TIMED_OUT" -> Color(0xFFFFE7DF)
        else -> Color(0xFFEAF0F4)
    }
    val textColor = when (job.status.uppercase()) {
        "COMPLETED" -> Color(0xFF267B59)
        "FAILED", "TIMED_OUT" -> Color(0xFFA64027)
        else -> Color(0xFF526A75)
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (job.status == "COMPLETED") Icons.Filled.CheckCircle else Icons.Filled.Sync,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(19.dp)
            )
            Spacer(Modifier.width(9.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("${job.command} · $trackerName", fontWeight = FontWeight.SemiBold)
                Text(
                    jobProgressText(job),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Surface(shape = CircleShape, color = statusColor) {
                Text(
                    job.status.replace('_', ' '),
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

internal fun jobProgressText(job: GatewayJob): String {
    val detail = job.detail
        ?.takeIf(String::isNotBlank)
        ?.takeUnless {
            it.contains("waiting for", ignoreCase = true) &&
                it.contains("receive window", ignoreCase = true)
        }
    if (detail != null) return detail
    if (job.partial) return "Partial FETCH · ${job.pendingRecords ?: 0} records remain"

    return when (job.status.uppercase()) {
        "QUEUED" -> "Queued at gateway"
        "IN_PROGRESS" -> if (job.command.equals("WAKE", ignoreCase = true)) {
            "Gateway is attempting to wake the tracker"
        } else {
            "Gateway is processing the command"
        }
        "FAILED" -> "Gateway reported command failure"
        "TIMED_OUT" -> "Timed out waiting for a tracker response"
        else -> formatTimestamp(job.createdAtMs)
    }
}

internal fun formatTrackerId(trackerId: Long): String =
    "0x${trackerId.toString(16).uppercase().padStart(8, '0')}"

internal fun formatTimestamp(timestampMs: Long): String =
    DateTimeFormatter.ofPattern("d MMM · HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(timestampMs))

private fun formatCoordinate(value: Double): String = String.format(Locale.US, "%.5f", value)

private fun parseTrackerId(value: String): Long? {
    val text = value.trim()
    val id = if (text.startsWith("0x", ignoreCase = true)) {
        text.substring(2).toLongOrNull(16)
    } else {
        text.toLongOrNull()
    }
    return id?.takeIf { it in 1L..0xFFFF_FFFFL }
}
