package com.mylittleworld.parent.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.mylittleworld.parent.ParentApp
import com.mylittleworld.parent.R
import com.mylittleworld.parent.service.ParentCallService
import com.mylittleworld.parent.ui.components.OsmMap
import com.mylittleworld.parent.ui.theme.ParentPalette
import com.mylittleworld.parent.ui.theme.ParentTheme
import com.mylittleworld.shared.logic.LocationFreshness
import com.mylittleworld.shared.logic.PairingCode
import com.mylittleworld.shared.model.DeviceSnapshot
import com.mylittleworld.shared.model.LocationSample
import com.mylittleworld.shared.model.PairedDevice
import com.mylittleworld.shared.model.SafeZone
import com.mylittleworld.shared.net.ApiException
import com.mylittleworld.shared.net.SafeZoneDraft
import com.mylittleworld.shared.util.AppLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class DashboardActivity : ComponentActivity() {

    private val container get() = (application as ParentApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!container.signedIn.value) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        ParentCallService.start(this)
        setContent {
            ParentTheme {
                var devices by remember { mutableStateOf<List<PairedDevice>>(emptyList()) }
                var selected by remember { mutableStateOf<String?>(null) }
                var snapshot by remember { mutableStateOf<DeviceSnapshot?>(null) }
                var locations by remember { mutableStateOf<List<LocationSample>>(emptyList()) }
                var zones by remember { mutableStateOf<List<SafeZone>>(emptyList()) }
                var loading by remember { mutableStateOf(true) }
                var error by remember { mutableStateOf<String?>(null) }
                var tab by remember { mutableStateOf(0) }
                var showPairing by remember { mutableStateOf(false) }
                var showDevicePicker by remember { mutableStateOf(false) }
                var showZoneDialog by remember { mutableStateOf(false) }
                var refreshTick by remember { mutableStateOf(0) }

                suspend fun load() {
                    try {
                        val list = container.api().devices()
                        devices = list
                        val target = selected?.takeIf { id -> list.any { it.deviceId == id } }
                            ?: container.prefs.lastDeviceId?.takeIf { id -> list.any { it.deviceId == id } }
                            ?: list.firstOrNull()?.deviceId
                        if (target != null) {
                            selected = target
                            container.prefs.lastDeviceId = target
                            snapshot = container.api().device(target)
                            locations = runCatching { container.api().locations(target) }.getOrDefault(emptyList())
                            zones = runCatching { container.api().safeZones(target) }.getOrDefault(emptyList())
                        } else {
                            snapshot = null
                        }
                        error = null
                    } catch (e: ApiException) {
                        AppLog.w("Dashboard", "Load failed: ${e.kind}")
                        error = e.friendly
                        if (e.kind == com.mylittleworld.shared.net.ApiErrorKind.UNAUTHORIZED) {
                            container.signOut()
                            startActivity(Intent(this@DashboardActivity, LoginActivity::class.java))
                            finish()
                        }
                    } finally {
                        loading = false
                    }
                }

                LaunchedEffect(Unit) { load() }
                LaunchedEffect(refreshTick, selected) {
                    while (true) {
                        delay(20_000)
                        load()
                    }
                }

                DashboardScreen(
                    devices = devices,
                    snapshot = snapshot,
                    locations = locations,
                    zones = zones,
                    loading = loading,
                    error = error,
                    tab = tab,
                    onTab = { tab = it },
                    onRefresh = { refreshTick += 1 },
                    onPickDevice = { showDevicePicker = true },
                    onPair = { showPairing = true },
                    onSignOut = {
                        container.signOut()
                        ParentCallService.stop(this@DashboardActivity)
                        startActivity(Intent(this@DashboardActivity, LoginActivity::class.java))
                        finish()
                    },
                    onLocate = {
                        val id = selected ?: return@DashboardScreen
                        lifecycleScope.launch {
                            runCatching { container.api().sendCommand(id, "LOCATE") }
                                .onFailure { error = it.message }
                            delay(4000)
                            refreshTick += 1
                        }
                    },
                    onRingPhone = {
                        val id = selected ?: return@DashboardScreen
                        lifecycleScope.launch {
                            runCatching { container.api().sendCommand(id, "FIND_PHONE") }
                                .onFailure { error = (it as? ApiException)?.friendly ?: it.message }
                        }
                    },
                    onVoiceCall = { startCall("audio") },
                    onVideoCall = { startCall("video") },
                    onManageSettings = {
                        selected?.let {
                            startActivity(
                                Intent(this, DeviceSettingsActivity::class.java)
                                    .putExtra(DeviceSettingsActivity.EXTRA_DEVICE_ID, it),
                            )
                        }
                    },
                    onAddZone = { showZoneDialog = true },
                    onDeleteZone = { zoneId ->
                        val id = selected ?: return@DashboardScreen
                        lifecycleScope.launch {
                            runCatching { container.api().removeSafeZone(id, zoneId) }
                            load()
                        }
                    },
                    onOpenMaps = { sample -> openInMaps(sample) },
                    onCopyCoordinates = { sample ->
                        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(
                            android.content.ClipData.newPlainText(
                                "location",
                                "${sample.lat}, ${sample.lng}",
                            ),
                        )
                    },
                )

                if (showPairing) {
                    PairingDialog(
                        onClose = { showPairing = false },
                        onCreate = { container.api().createPairingCode() },
                    )
                }
                if (showDevicePicker) {
                    AlertDialog(
                        onDismissRequest = { showDevicePicker = false },
                        containerColor = ParentPalette.Card,
                        title = { Text("Child phones") },
                        text = {
                            Column {
                                devices.forEach { device ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                selected = device.deviceId
                                                container.prefs.lastDeviceId = device.deviceId
                                                showDevicePicker = false
                                                refreshTick += 1
                                            }
                                            .padding(vertical = 12.dp),
                                    ) {
                                        Text(
                                            device.name,
                                            modifier = Modifier.weight(1f),
                                            color = ParentPalette.Ink,
                                        )
                                        Text(
                                            if (device.online) "online" else "offline",
                                            color = if (device.online) ParentPalette.Mint
                                            else ParentPalette.InkMuted,
                                            fontSize = 12.sp,
                                        )
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showDevicePicker = false }) { Text("Close") }
                        },
                    )
                }
                if (showZoneDialog) {
                    val base = snapshot?.lastLocation
                    AddZoneDialog(
                        defaultLat = base?.lat,
                        defaultLng = base?.lng,
                        onDismiss = { showZoneDialog = false },
                        onSave = { draft ->
                            val id = selected ?: return@AddZoneDialog
                            lifecycleScope.launch {
                                try {
                                    container.api().addSafeZone(id, draft)
                                    showZoneDialog = false
                                    load()
                                } catch (e: Exception) {
                                    error = (e as? ApiException)?.friendly ?: "Could not add the zone."
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    private fun startCall(media: String) {
        val id = container.prefs.lastDeviceId ?: return
        lifecycleScope.launch {
            try {
                val call = container.api().startCall(id, media)
                startActivity(
                    ParentCallActivity.answeringIntent(this@DashboardActivity, call.callId, media),
                )
            } catch (e: ApiException) {
                AppLog.w("Dashboard", "Call start failed: ${e.kind}")
                android.widget.Toast.makeText(
                    this@DashboardActivity,
                    e.friendly,
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun openInMaps(sample: LocationSample) {
        val uri = Uri.parse("geo:${sample.lat},${sample.lng}?q=${sample.lat},${sample.lng}")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.openstreetmap.org/?mlat=${sample.lat}&mlon=${sample.lng}#map=17/${sample.lat}/${sample.lng}"),
                ),
            )
        }
    }
}

@Composable
private fun DashboardScreen(
    devices: List<PairedDevice>,
    snapshot: DeviceSnapshot?,
    locations: List<LocationSample>,
    zones: List<SafeZone>,
    loading: Boolean,
    error: String?,
    tab: Int,
    onTab: (Int) -> Unit,
    onRefresh: () -> Unit,
    onPickDevice: () -> Unit,
    onPair: () -> Unit,
    onSignOut: () -> Unit,
    onLocate: () -> Unit,
    onRingPhone: () -> Unit,
    onVoiceCall: () -> Unit,
    onVideoCall: () -> Unit,
    onManageSettings: () -> Unit,
    onAddZone: () -> Unit,
    onDeleteZone: (String) -> Unit,
    onOpenMaps: (LocationSample) -> Unit,
    onCopyCoordinates: (LocationSample) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ParentPalette.Background)
            .safeDrawingPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.dashboard_title),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = ParentPalette.Ink,
                )
                Text(
                    snapshot?.name ?: "No child phone selected",
                    fontSize = 13.sp,
                    color = ParentPalette.InkMuted,
                    modifier = Modifier.clickable(enabled = devices.isNotEmpty(), onClick = onPickDevice),
                )
            }
            TextButton(onClick = onRefresh) {
                Text(stringResource(R.string.dashboard_reload), color = ParentPalette.Accent)
            }
            TextButton(onClick = onSignOut) {
                Text(stringResource(R.string.sign_out), color = ParentPalette.InkMuted, fontSize = 12.sp)
            }
        }

        if (devices.isEmpty()) {
            EmptyState(onPair = onPair)
            return@Column
        }

        TabRow(tab = tab, onTab = onTab)

        if (loading && snapshot == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = ParentPalette.Accent)
            }
            return@Column
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            error?.let {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF1EE)),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                ) {
                    Text(
                        it,
                        modifier = Modifier.padding(14.dp),
                        color = ParentPalette.Danger,
                        fontSize = 13.sp,
                    )
                }
            }
            when (tab) {
                0 -> HomeTab(snapshot, onLocate, onRingPhone, onVoiceCall, onVideoCall, onManageSettings)
                1 -> LocationTab(
                    snapshot = snapshot,
                    locations = locations,
                    zones = zones,
                    onLocate = onLocate,
                    onAddZone = onAddZone,
                    onDeleteZone = onDeleteZone,
                    onOpenMaps = onOpenMaps,
                    onCopy = onCopyCoordinates,
                )
                2 -> SettingsSummaryTab(snapshot, onManageSettings)
                else -> CallsTab(snapshot, onVoiceCall, onVideoCall, onRingPhone, onPair)
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun TabRow(tab: Int, onTab: (Int) -> Unit) {
    val labels = listOf(
        stringResource(R.string.tab_home),
        stringResource(R.string.tab_location),
        stringResource(R.string.tab_settings),
        stringResource(R.string.tab_calls),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        labels.forEachIndexed { index, label ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (tab == index) ParentPalette.Accent else Color.Transparent)
                    .clickable { onTab(index) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (tab == index) Color.White else ParentPalette.InkMuted,
                )
            }
        }
    }
}

@Composable
private fun EmptyState(onPair: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(ParentPalette.AccentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Text("+", fontSize = 40.sp, color = ParentPalette.Accent, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.dashboard_no_devices),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = ParentPalette.Ink,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.dashboard_no_devices_body),
            fontSize = 14.sp,
            color = ParentPalette.InkMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(22.dp))
        Button(
            onClick = onPair,
            colors = ButtonDefaults.buttonColors(containerColor = ParentPalette.Accent),
            shape = RoundedCornerShape(18.dp),
        ) { Text(stringResource(R.string.dashboard_add_device)) }
    }
}

@Composable
private fun HomeTab(
    snapshot: DeviceSnapshot?,
    onLocate: () -> Unit,
    onRingPhone: () -> Unit,
    onVoiceCall: () -> Unit,
    onVideoCall: () -> Unit,
    onManageSettings: () -> Unit,
) {
    if (snapshot == null) return
    StatusHero(snapshot)
    Spacer(Modifier.height(14.dp))
    SectionCard("Quick actions") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionChip(stringResource(R.string.location_now), ParentPalette.Accent, onLocate)
            ActionChip(stringResource(R.string.call_find_phone), ParentPalette.Warn, onRingPhone)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionChip(stringResource(R.string.call_start_voice), ParentPalette.Mint, onVoiceCall)
            ActionChip(stringResource(R.string.call_start_video), ParentPalette.Accent, onVideoCall)
        }
    }
    Spacer(Modifier.height(14.dp))
    SectionCard("Device health") {
        InfoRow("Connection", if (snapshot.online) "Online" else "Offline")
        InfoRow("Battery", snapshot.batteryPercent?.let { "$it%" } ?: "unknown")
        InfoRow("Charging", snapshot.charging?.let { if (it) "Yes" else "No" } ?: "unknown")
        InfoRow("Last seen", formatTimestamp(snapshot.lastSeen))
        InfoRow("App version", snapshot.appVersion ?: "unknown")
        InfoRow("WebView", snapshot.webViewVersion ?: "unknown")
        InfoRow("Android", snapshot.androidVersion ?: "unknown")
        InfoRow("Model", snapshot.deviceModel ?: "unknown")
        InfoRow("Auto-answer possible", if (snapshot.autoAnswerSupported) "Yes" else "Not on this device")
    }
    Spacer(Modifier.height(14.dp))
    Button(
        onClick = onManageSettings,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        colors = ButtonDefaults.buttonColors(containerColor = ParentPalette.Ink),
        shape = RoundedCornerShape(18.dp),
    ) { Text("Manage home screen and learning site") }
}

@Composable
private fun StatusHero(snapshot: DeviceSnapshot) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF4C6FD6), Color(0xFF6E8BE8)),
                    ),
                )
                .padding(20.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.22f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            snapshot.config.nickname.take(1).uppercase(),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            snapshot.config.nickname,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                        Text(
                            if (snapshot.online) "Online now" else "Offline · last seen ${formatTimestamp(snapshot.lastSeen)}",
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.85f),
                        )
                    }
                    snapshot.batteryPercent?.let { battery ->
                        Text(
                            "$battery%",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(if (snapshot.config.learningUrl.isBlank()) "No learning site" else "Learning site set")
                    Pill(if (snapshot.config.autoAnswerVoice) "Auto-answer on" else "Auto-answer off")
                    Pill("${snapshot.safeZones.size} safe zones")
                }
            }
        }
    }
}

@Composable
private fun Pill(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.18f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(text, fontSize = 11.sp, color = Color.White)
    }
}

@Composable
private fun LocationTab(
    snapshot: DeviceSnapshot?,
    locations: List<LocationSample>,
    zones: List<SafeZone>,
    onLocate: () -> Unit,
    onAddZone: () -> Unit,
    onDeleteZone: (String) -> Unit,
    onOpenMaps: (LocationSample) -> Unit,
    onCopy: (LocationSample) -> Unit,
) {
    val latest = snapshot?.lastLocation ?: locations.firstOrNull()
    if (latest == null) {
        SectionCard(stringResource(R.string.location_last)) {
            Text(
                stringResource(R.string.location_none),
                fontSize = 14.sp,
                color = ParentPalette.InkMuted,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.location_permission_hint),
                fontSize = 12.sp,
                color = ParentPalette.InkMuted,
            )
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onLocate,
                colors = ButtonDefaults.buttonColors(containerColor = ParentPalette.Accent),
            ) { Text(stringResource(R.string.location_now)) }
        }
        Spacer(Modifier.height(14.dp))
    } else {
        val captured = runCatching { Instant.parse(latest.capturedAt) }.getOrNull()
        val freshState = captured?.let { LocationFreshness.state(it) } ?: LocationFreshness.State.EXPIRED
        val stale = freshState != LocationFreshness.State.LIVE && freshState != LocationFreshness.State.RECENT
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = ParentPalette.Card),
        ) {
            Column {
                OsmMap(
                    lat = latest.lat,
                    lng = latest.lng,
                    accuracyMeters = latest.accuracyMeters.takeIf { it > 0 },
                    stale = stale,
                    modifier = Modifier.fillMaxWidth().height(240.dp),
                )
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (stale) "Last known location" else "Live location",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ParentPalette.Ink,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (captured != null) {
                            stringResource(
                                R.string.location_stale,
                                LocationFreshness.describe(captured),
                            )
                        } else {
                            "Timestamp unavailable"
                        },
                        fontSize = 13.sp,
                        color = if (stale) ParentPalette.Warn else ParentPalette.Mint,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(
                            R.string.location_accuracy,
                            LocationFreshness.accuracyLabel(latest.accuracyMeters),
                        ),
                        fontSize = 13.sp,
                        color = ParentPalette.InkMuted,
                    )
                    if (latest.isMock) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "This fix looks simulated by the device and is not trusted.",
                            fontSize = 12.sp,
                            color = ParentPalette.Danger,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ActionChip(stringResource(R.string.location_open_maps), ParentPalette.Accent) {
                            onOpenMaps(latest)
                        }
                        ActionChip("Copy coordinates", ParentPalette.InkMuted) { onCopy(latest) }
                        ActionChip(stringResource(R.string.location_now), ParentPalette.Mint, onLocate)
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
    }

    SectionCard(stringResource(R.string.location_safe_zones)) {
        if (zones.isEmpty()) {
            Text(
                "No zones yet. Add one around home or school to hear about arrivals and departures.",
                fontSize = 13.sp,
                color = ParentPalette.InkMuted,
            )
        } else {
            zones.forEach { zone ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(zone.name, fontSize = 14.sp, color = ParentPalette.Ink, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${zone.radiusMeters} m · ${"%.4f".format(zone.lat)}, ${"%.4f".format(zone.lng)}",
                            fontSize = 12.sp,
                            color = ParentPalette.InkMuted,
                        )
                    }
                    TextButton(onClick = { onDeleteZone(zone.id) }) {
                        Text(stringResource(R.string.location_delete_zone), color = ParentPalette.Danger, fontSize = 12.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onAddZone,
            colors = ButtonDefaults.buttonColors(containerColor = ParentPalette.Ink),
            shape = RoundedCornerShape(16.dp),
        ) { Text(stringResource(R.string.location_add_zone)) }
    }

    if (locations.size > 1) {
        Spacer(Modifier.height(14.dp))
        SectionCard(stringResource(R.string.location_history)) {
            locations.take(8).forEach { sample ->
                InfoRow(
                    formatTimestamp(sample.capturedAt),
                    "${"%.4f".format(sample.lat)}, ${"%.4f".format(sample.lng)} · ±${sample.accuracyMeters.toInt()} m",
                )
            }
        }
    }
}

@Composable
private fun SettingsSummaryTab(snapshot: DeviceSnapshot?, onManageSettings: () -> Unit) {
    if (snapshot == null) return
    SectionCard("Home screen") {
        InfoRow("Child's name", snapshot.config.nickname)
        InfoRow("Parent's name", snapshot.config.parentName)
        InfoRow("Colours", snapshot.config.theme)
        InfoRow("Buttons", snapshot.config.enabledTiles.joinToString(", "))
    }
    Spacer(Modifier.height(14.dp))
    SectionCard("Learning") {
        InfoRow("Website", snapshot.config.learningUrl.ifBlank { "Not set" })
        InfoRow("Play together", snapshot.config.playUrl.ifBlank { "Not set" })
    }
    Spacer(Modifier.height(14.dp))
    SectionCard("Calls and quiet hours") {
        InfoRow("Auto-answer voice", if (snapshot.config.autoAnswerVoice) "On" else "Off")
        InfoRow(
            "Quiet hours",
            if (snapshot.config.scheduleStart.isBlank() || snapshot.config.scheduleEnd.isBlank()) {
                "Not set"
            } else {
                "${snapshot.config.scheduleStart} – ${snapshot.config.scheduleEnd}"
            },
        )
    }
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = onManageSettings,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        colors = ButtonDefaults.buttonColors(containerColor = ParentPalette.Accent),
        shape = RoundedCornerShape(18.dp),
    ) { Text(stringResource(R.string.tab_settings)) }
}

@Composable
private fun CallsTab(
    snapshot: DeviceSnapshot?,
    onVoiceCall: () -> Unit,
    onVideoCall: () -> Unit,
    onRingPhone: () -> Unit,
    onPair: () -> Unit,
) {
    SectionCard("Call the child phone") {
        Text(
            "Calls open a private room on your family service. The child phone rings with big " +
                "Answer and End Call buttons, and the call always shows an End Call control.",
            fontSize = 13.sp,
            color = ParentPalette.InkMuted,
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionChip(stringResource(R.string.call_start_voice), ParentPalette.Mint, onVoiceCall)
            ActionChip(stringResource(R.string.call_start_video), ParentPalette.Accent, onVideoCall)
        }
        Spacer(Modifier.height(10.dp))
        ActionChip(stringResource(R.string.call_find_phone), ParentPalette.Warn, onRingPhone)
    }
    Spacer(Modifier.height(14.dp))
    SectionCard("Add another phone") {
        Text(
            "Create a pairing code and type it on the child phone.",
            fontSize = 13.sp,
            color = ParentPalette.InkMuted,
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onPair,
            colors = ButtonDefaults.buttonColors(containerColor = ParentPalette.Ink),
        ) { Text(stringResource(R.string.dashboard_add_device)) }
    }
    snapshot?.let {
        Spacer(Modifier.height(14.dp))
        SectionCard("Connection") {
            InfoRow("Status", if (it.online) "Online" else "Offline")
            InfoRow("Last seen", formatTimestamp(it.lastSeen))
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ParentPalette.Card),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                title.uppercase(Locale.getDefault()),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = ParentPalette.InkMuted,
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 13.sp, color = ParentPalette.InkMuted)
        Text(
            value,
            modifier = Modifier.weight(1.3f),
            fontSize = 13.sp,
            color = ParentPalette.Ink,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun ActionChip(label: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

@Composable
private fun PairingDialog(onClose: () -> Unit, onCreate: suspend () -> com.mylittleworld.shared.model.PairingCode) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var code by remember { mutableStateOf<com.mylittleworld.shared.model.PairingCode?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { onCreate() }
            .onSuccess { code = it }
            .onFailure { error = (it as? ApiException)?.friendly ?: "Could not create a code." }
    }
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = ParentPalette.Card,
        title = { Text(stringResource(R.string.pairing_code_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.pairing_code_help),
                    fontSize = 13.sp,
                    color = ParentPalette.InkMuted,
                )
                Spacer(Modifier.height(16.dp))
                code?.let {
                    Text(
                        PairingCode.display(it.code),
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 4.sp,
                        color = ParentPalette.Ink,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.pairing_code_expires, formatTimestamp(it.expiresAt)),
                        fontSize = 12.sp,
                        color = ParentPalette.InkMuted,
                    )
                } ?: run {
                    if (error == null) CircularProgressIndicator(color = ParentPalette.Accent)
                    else Text(error!!, color = ParentPalette.Danger, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text(stringResource(R.string.pairing_done)) }
        },
        dismissButton = {
            if (error != null) {
                TextButton(onClick = {
                    error = null
                    scope.launch {
                        runCatching { onCreate() }
                            .onSuccess { code = it }
                            .onFailure { error = (it as? ApiException)?.friendly ?: "Could not create a code." }
                    }
                }) { Text("Retry") }
            }
        },
    )
}

@Composable
private fun AddZoneDialog(
    defaultLat: Double?,
    defaultLng: Double?,
    onDismiss: () -> Unit,
    onSave: (SafeZoneDraft) -> Unit,
) {
    var name by remember { mutableStateOf("Home") }
    var radius by remember { mutableStateOf("200") }
    var lat by remember { mutableStateOf(defaultLat?.let { "%.5f".format(it) } ?: "") }
    var lng by remember { mutableStateOf(defaultLng?.let { "%.5f".format(it) } ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ParentPalette.Card,
        title = { Text(stringResource(R.string.location_add_zone), fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text(stringResource(R.string.location_zone_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = radius,
                    onValueChange = { radius = it.filter { c -> c.isDigit() }.take(5) },
                    label = { Text(stringResource(R.string.location_zone_radius)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = lat,
                        onValueChange = { lat = it.take(12) },
                        label = { Text(stringResource(R.string.location_zone_lat)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = lng,
                        onValueChange = { lng = it.take(12) },
                        label = { Text(stringResource(R.string.location_zone_lng)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                }
                if (defaultLat == null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "No location has been received yet. Type coordinates or send a locate request first.",
                        fontSize = 11.sp,
                        color = ParentPalette.Warn,
                    )
                }
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = ParentPalette.Danger, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val latValue = lat.toDoubleOrNull()
                val lngValue = lng.toDoubleOrNull()
                val radiusValue = radius.toIntOrNull()
                when {
                    name.isBlank() -> error = "Give the zone a name."
                    radiusValue == null || radiusValue !in 50..5000 ->
                        error = "Radius must be between 50 and 5000 metres."
                    latValue == null || latValue !in -90.0..90.0 -> error = "Latitude is not valid."
                    lngValue == null || lngValue !in -180.0..180.0 -> error = "Longitude is not valid."
                    else -> onSave(SafeZoneDraft(name.trim(), latValue, lngValue, radiusValue))
                }
            }) { Text(stringResource(R.string.settings_save), color = ParentPalette.Accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

internal fun formatTimestamp(iso: String?): String {
    if (iso.isNullOrBlank()) return "unknown"
    return runCatching {
        val instant = Instant.parse(iso)
        DateTimeFormatter.ofPattern("d MMM, HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(instant)
    }.getOrDefault(iso)
}
