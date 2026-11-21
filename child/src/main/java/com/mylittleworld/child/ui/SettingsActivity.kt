package com.mylittleworld.child.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.mylittleworld.child.ChildApp
import com.mylittleworld.child.R
import com.mylittleworld.child.core.DeviceActions
import com.mylittleworld.child.service.FamilyConnectService
import com.mylittleworld.child.ui.components.ArrowBackIcon
import com.mylittleworld.child.ui.theme.MyLittleWorldTheme
import com.mylittleworld.child.ui.theme.Palette
import com.mylittleworld.shared.logic.PinPolicy
import com.mylittleworld.shared.logic.UrlPolicy
import com.mylittleworld.shared.model.DeviceConfig
import com.mylittleworld.shared.net.ApiException
import com.mylittleworld.shared.store.SettingsStore
import com.mylittleworld.shared.util.AppLog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Adult settings. Reached only through the PIN gate in the launcher. All edits are
 * saved locally first, then mirrored to the family service so both phones agree.
 */
class SettingsActivity : ComponentActivity() {

    private val container get() = (application as ChildApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MyLittleWorldTheme {
                val config by container.config.collectAsStateWithLifecycle()
                val paired by container.paired.collectAsStateWithLifecycle()
                val serverUrl by container.serverUrl.collectAsStateWithLifecycle()
                val autoAccepted by container.settings.autoAnswerAccepted
                    .collectAsStateWithLifecycle(initialValue = false)

                SettingsContent(
                    initialConfig = config,
                    paired = paired,
                    serverUrl = serverUrl,
                    autoAnswerAccepted = autoAccepted,
                    onSave = { updated, url, done ->
                        lifecycleScope.launch {
                            container.settings.saveConfig(updated)
                            if (url != serverUrl) container.settings.saveServerUrl(url)
                            if (paired) {
                                runCatching { container.childApi().updateConfig(updated) }
                                    .onSuccess { container.settings.saveConfig(it) }
                                    .onFailure { e ->
                                        AppLog.w("SettingsActivity", "Config push failed: ${(e as? ApiException)?.kind}")
                                    }
                            }
                            done(fetchWarning(updated, url))
                        }
                    },
                    onAutoAnswerChange = { enabled ->
                        lifecycleScope.launch {
                            container.settings.setAutoAnswerAccepted(enabled)
                        }
                    },
                    onPair = {
                        startActivity(Intent(this, PairingActivity::class.java))
                    },
                    onUnpair = {
                        lifecycleScope.launch {
                            container.settings.unpair()
                            container.secure.clear()
                            finish()
                        }
                    },
                    onClose = { finish() },
                    onRequestPermission = { permission ->
                        if (permission == Manifest.permission.POST_NOTIFICATIONS) {
                            startActivity(DeviceActions.notificationSettingsIntent(this))
                        } else {
                            runCatching {
                                startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                        .setData(Uri.parse("package:$packageName")),
                                )
                            }
                        }
                    },
                    onLocationSettings = { startActivity(DeviceActions.locationSettingsIntent(this)) },
                    onInstallYouTube = { DeviceActions.openPlayStore(this) },
                    onRefresh = {
                        lifecycleScope.launch {
                            container.refreshFromServer()
                            FamilyConnectService.start(this@SettingsActivity)
                        }
                    },
                )
            }
        }
    }

    private fun fetchWarning(config: DeviceConfig, serverUrl: String): String? {
        if (config.learningUrl.isNotBlank() &&
            UrlPolicy.sanitiseApprovedUrl(config.learningUrl, container.isLocalDevMode) == null
        ) {
            return "The learning address must be a secure https:// link. It was not saved."
        }
        if (serverUrl.isNotBlank() && !serverUrl.startsWith("http")) {
            return "The family service address must start with https://"
        }
        return null
    }
}

@Composable
private fun SettingsContent(
    initialConfig: DeviceConfig,
    paired: Boolean,
    serverUrl: String,
    autoAnswerAccepted: Boolean,
    onSave: (DeviceConfig, String, (String?) -> Unit) -> Unit,
    onAutoAnswerChange: (Boolean) -> Unit,
    onPair: () -> Unit,
    onUnpair: () -> Unit,
    onClose: () -> Unit,
    onRequestPermission: (String) -> Unit,
    onLocationSettings: () -> Unit,
    onInstallYouTube: () -> Unit,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    var nickname by remember(initialConfig.nickname) { mutableStateOf(initialConfig.nickname) }
    var parentName by remember(initialConfig.parentName) { mutableStateOf(initialConfig.parentName) }
    var learningUrl by remember(initialConfig.learningUrl) { mutableStateOf(initialConfig.learningUrl) }
    var playUrl by remember(initialConfig.playUrl) { mutableStateOf(initialConfig.playUrl) }
    var theme by remember(initialConfig.theme) { mutableStateOf(initialConfig.theme) }
    var tiles by remember(initialConfig.enabledTiles) { mutableStateOf(initialConfig.enabledTiles.toSet()) }
    var autoAnswer by remember(initialConfig.autoAnswerVoice) { mutableStateOf(initialConfig.autoAnswerVoice) }
    var scheduleStart by remember(initialConfig.scheduleStart) { mutableStateOf(initialConfig.scheduleStart) }
    var scheduleEnd by remember(initialConfig.scheduleEnd) { mutableStateOf(initialConfig.scheduleEnd) }
    var serverField by remember(serverUrl) { mutableStateOf(serverUrl) }
    var message by remember { mutableStateOf<String?>(null) }
    var showAutoAnswerNotice by remember { mutableStateOf(false) }
    var showUnpairConfirm by remember { mutableStateOf(false) }

    val notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
    val locationGranted = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    val youtubeInstalled = DeviceActions.isInstalled(context, DeviceActions.YOUTUBE_KIDS)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Cream)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { ArrowBackIcon(Modifier.size(20.dp), Palette.Ink) }
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.settings_title),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Palette.Ink,
            )
        }
        Spacer(Modifier.height(16.dp))

        SettingsCard("Connection") {
            Text(
                if (paired) stringResource(R.string.settings_pair_status, initialConfig.parentName)
                else stringResource(R.string.settings_not_paired),
                fontSize = 15.sp,
                color = Palette.InkSoft,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!paired) {
                    Button(
                        onClick = onPair,
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.CallEnd),
                    ) { Text(stringResource(R.string.onboarding_pair)) }
                } else {
                    Button(
                        onClick = onRefresh,
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.CallEnd),
                    ) { Text(stringResource(R.string.settings_reload)) }
                    TextButton(onClick = { showUnpairConfirm = true }) {
                        Text(stringResource(R.string.settings_unpair), color = Color(0xFFC0392B))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = serverField,
                onValueChange = { serverField = it },
                label = { Text(stringResource(R.string.settings_server)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.settings_server_help),
                fontSize = 12.sp,
                color = Palette.InkSoft,
            )
        }

        Spacer(Modifier.height(14.dp))
        SettingsCard("Names and colours") {
            OutlinedTextField(
                value = nickname,
                onValueChange = { if (it.length <= 24) nickname = it },
                label = { Text(stringResource(R.string.settings_nickname)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = parentName,
                onValueChange = { if (it.length <= 24) parentName = it },
                label = { Text(stringResource(R.string.settings_parent_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("sunrise" to "Sunrise", "ocean" to "Ocean", "garden" to "Garden").forEach { (key, label) ->
                    FilterChipish(selected = theme == key, label = label) { theme = key }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        SettingsCard("My Learning World") {
            OutlinedTextField(
                value = learningUrl,
                onValueChange = { learningUrl = it },
                label = { Text(stringResource(R.string.settings_learning_url)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = playUrl,
                onValueChange = { playUrl = it },
                label = { Text(stringResource(R.string.settings_play_url)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Only https:// addresses are accepted. The site opens full screen and cannot leave its own domain.",
                fontSize = 12.sp,
                color = Palette.InkSoft,
            )
        }

        Spacer(Modifier.height(14.dp))
        SettingsCard("Home buttons") {
            TileToggle("learning", "My Learning World", tiles) { tiles = it }
            TileToggle("call", "Call $parentName", tiles) { tiles = it }
            TileToggle("youtube", "YouTube Kids", tiles) { tiles = it }
            TileToggle("camera", "My Camera", tiles) { tiles = it }
            TileToggle("play", "Play together", tiles) { tiles = it }
        }

        Spacer(Modifier.height(14.dp))
        SettingsCard("Calling") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_auto_answer, parentName),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Palette.Ink,
                    )
                    Text(
                        stringResource(R.string.settings_auto_answer_help),
                        fontSize = 12.sp,
                        color = Palette.InkSoft,
                    )
                }
                Switch(
                    checked = autoAnswer,
                    onCheckedChange = { checked ->
                        autoAnswer = checked
                        if (checked) showAutoAnswerNotice = true else onAutoAnswerChange(false)
                    },
                )
            }
            if (!autoAnswerAccepted && autoAnswer) {
                Text(
                    "Not active until the notice below is accepted on this phone.",
                    fontSize = 12.sp,
                    color = Color(0xFFC0392B),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimeField(scheduleStart) { scheduleStart = it }
                TimeField(scheduleEnd) { scheduleEnd = it }
            }
            Text(
                "Quiet hours pause the learning world (24h, e.g. 20:00 to 07:00).",
                fontSize = 12.sp,
                color = Palette.InkSoft,
            )
        }

        Spacer(Modifier.height(14.dp))
        SettingsCard("This phone") {
            StatusRow("YouTube Kids", if (youtubeInstalled) "Installed" else "Not installed")
            if (!youtubeInstalled) {
                TextButton(onClick = onInstallYouTube) {
                    Text(stringResource(R.string.settings_install_youtube), color = Palette.LearningEnd)
                }
            }
            StatusRow("Notifications", if (notificationsGranted) "Allowed" else "Blocked", onClick = {
                onRequestPermission(Manifest.permission.POST_NOTIFICATIONS)
            })
            StatusRow("Location sharing", if (locationGranted) "Allowed" else "Blocked", onClick = onLocationSettings)
            StatusRow("Location services", if (DeviceActions.locationEnabled(context)) "On" else "Off", onClick = onLocationSettings)
            StatusRow("Play services", if (DeviceActions.playServicesAvailable(context)) "Available" else "Not available")
            StatusRow("WebView", if (DeviceActions.webViewAvailable(context)) "Available" else "Missing")
            StatusRow("Android", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            StatusRow("Model", Build.MODEL ?: "unknown")
            StatusRow(
                "Last crash",
                container_lastCrashLabel(context),
            )
        }

        Spacer(Modifier.height(18.dp))
        message?.let {
            Text(it, color = Palette.LearningEnd, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
        }
        Button(
            onClick = {
                val cleanedLearning = learningUrl.trim().takeIf { it.isNotBlank() }?.let {
                    UrlPolicy.sanitiseApprovedUrl(it, container_isLocalDev(context, serverField))
                }
                val cleanedPlay = playUrl.trim().takeIf { it.isNotBlank() }?.let {
                    UrlPolicy.sanitiseApprovedUrl(it, container_isLocalDev(context, serverField))
                }
                if (learningUrl.isNotBlank() && cleanedLearning == null) {
                    message = "The learning address must be a secure https:// address."
                    return@Button
                }
                if (playUrl.isNotBlank() && cleanedPlay == null) {
                    message = "The play-together address must be a secure https:// address."
                    return@Button
                }
                val updated = initialConfig.copy(
                    nickname = nickname.trim().ifBlank { "Little Star" },
                    parentName = parentName.trim().ifBlank { "Daddy" },
                    learningUrl = cleanedLearning ?: "",
                    playUrl = cleanedPlay ?: "",
                    theme = theme,
                    enabledTiles = tiles.toList().sorted(),
                    autoAnswerVoice = autoAnswer,
                    // Reflect only what was accepted on this phone.
                    autoAnswerNoticeAccepted = autoAnswerAccepted,
                    scheduleStart = scheduleStart.trim(),
                    scheduleEnd = scheduleEnd.trim(),
                    updatedAt = System.currentTimeMillis().toString(),
                )
                onSave(updated, serverField.trim()) { warning ->
                    message = warning ?: "Saved."
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Palette.CallEnd),
            shape = RoundedCornerShape(24.dp),
        ) { Text(stringResource(R.string.settings_save), fontSize = 17.sp) }

        Spacer(Modifier.height(10.dp))
        ChangePinCard()

        Spacer(Modifier.height(30.dp))
    }

    if (showAutoAnswerNotice) {
        AlertDialog(
            onDismissRequest = { showAutoAnswerNotice = false; autoAnswer = false },
            containerColor = Palette.Cream,
            title = { Text("Auto-answer notice", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Voice calls from your verified parent account will be answered " +
                        "automatically on this device. The phone will ring briefly, say out loud " +
                        "who is calling, and always show an End Call button. Video never starts " +
                        "by itself. Android may still block auto-answer when the screen is off; " +
                        "you will then see the normal incoming call screen.",
                    fontSize = 14.sp,
                    color = Palette.Ink,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showAutoAnswerNotice = false
                    onAutoAnswerChange(true)
                }) { Text("I understand", color = Palette.CallEnd) }
            },
            dismissButton = {
                TextButton(onClick = { showAutoAnswerNotice = false; autoAnswer = false }) {
                    Text("Cancel", color = Palette.InkSoft)
                }
            },
        )
    }

    if (showUnpairConfirm) {
        AlertDialog(
            onDismissRequest = { showUnpairConfirm = false },
            containerColor = Palette.Cream,
            title = { Text("Unpair this phone?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "This phone will stop receiving calls, location requests and settings " +
                        "from the parent app. You can pair again with a new code.",
                    fontSize = 14.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { showUnpairConfirm = false; onUnpair() }) {
                    Text("Unpair", color = Color(0xFFC0392B))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnpairConfirm = false }) { Text("Keep paired") }
            },
        )
    }
}

private fun container_lastCrashLabel(context: android.content.Context): String {
    val prefs = context.getSharedPreferences("mlw_crash", android.content.Context.MODE_PRIVATE)
    val at = prefs.getLong("last_crash_at", 0L)
    if (at <= 0L) return "None recorded"
    return SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(at))
}

private fun container_isLocalDev(context: android.content.Context, server: String): Boolean =
    server.contains("10.0.2.2") || server.contains("localhost") ||
        server.contains("127.0.0.1") || server.contains(".invalid")

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White)
            .padding(18.dp),
    ) {
        Text(title.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Palette.InkSoft)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun TileToggle(
    key: String,
    label: String,
    tiles: Set<String>,
    onChange: (Set<String>) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 15.sp, color = Palette.Ink)
        Switch(
            checked = tiles.contains(key),
            onCheckedChange = { checked ->
                onChange(if (checked) tiles + key else tiles - key)
            },
        )
    }
}

@Composable
private fun StatusRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 14.sp, color = Palette.Ink)
        Text(value, fontSize = 14.sp, color = Palette.InkSoft, textAlign = TextAlign.End)
    }
}

@Composable
private fun FilterChipish(selected: Boolean, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) Palette.LearningEnd else Palette.CreamDeep)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(
            label,
            color = if (selected) Color.White else Palette.Ink,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun TimeField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(5)) },
        label = { Text("HH:mm", fontSize = 12.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.width(120.dp),
    )
}

@Composable
private fun ChangePinCard() {
    val context = LocalContext.current
    val container = (context.applicationContext as ChildApp).container
    var stage by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White)
            .padding(18.dp),
    ) {
        Text(stringResource(R.string.settings_pin_change), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { if (it.length <= 8) input = it.filter { c -> c.isDigit() } },
            label = {
                Text(
                    if (stage == 0) "Current PIN" else "New PIN",
                    fontSize = 12.sp,
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )
        message?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = Color(0xFFC0392B), fontSize = 13.sp)
        }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = {
                scope.launch {
                    if (stage == 0) {
                        val hash = container.settings.pinHash.first()
                        if (PinPolicy.verify(input, hash)) {
                            stage = 1
                            input = ""
                            message = null
                        } else {
                            message = "That PIN is not right."
                        }
                    } else {
                        if (!PinPolicy.isValidPin(input)) {
                            message = "Use 4 to 8 numbers."
                        } else {
                            container.settings.savePinHash(PinPolicy.hash(input))
                            stage = 0
                            input = ""
                            message = "PIN updated."
                        }
                    }
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = Palette.InkSoft),
        ) {
            Text(if (stage == 0) "Continue" else "Save PIN", fontSize = 14.sp)
        }
    }
}
