package com.mylittleworld.parent.ui

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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.mylittleworld.parent.ParentApp
import com.mylittleworld.parent.R
import com.mylittleworld.parent.ui.theme.ParentPalette
import com.mylittleworld.parent.ui.theme.ParentTheme
import com.mylittleworld.shared.logic.Schedule
import com.mylittleworld.shared.logic.UrlPolicy
import com.mylittleworld.shared.model.DeviceConfig
import com.mylittleworld.shared.net.ApiException
import com.mylittleworld.shared.util.AppLog
import kotlinx.coroutines.launch

/**
 * Edits the parent-owned configuration. Every change is written through the
 * authenticated API and acknowledged by the child device's next sync.
 */
class DeviceSettingsActivity : ComponentActivity() {

    private val container get() = (application as ParentApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
        if (deviceId.isNullOrBlank()) {
            finish()
            return
        }
        setContent {
            ParentTheme {
                var config by remember { mutableStateOf<DeviceConfig?>(null) }
                var loading by remember { mutableStateOf(true) }
                var error by remember { mutableStateOf<String?>(null) }
                var saved by remember { mutableStateOf<String?>(null) }

                LaunchedEffect(deviceId) {
                    try {
                        config = container.api().device(deviceId).config
                        error = null
                    } catch (e: ApiException) {
                        error = e.friendly
                    } finally {
                        loading = false
                    }
                }

                Box(
                    Modifier
                        .fillMaxSize()
                        .background(ParentPalette.Background)
                        .safeDrawingPadding(),
                ) {
                    if (loading) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = ParentPalette.Accent)
                        }
                    } else {
                        config?.let { current ->
                            ConfigEditor(
                                initial = current,
                                error = error,
                                saved = saved,
                                onBack = { finish() },
                                onSave = { updated ->
                                    lifecycleScope.launch {
                                        try {
                                            val result = container.api().updateConfig(deviceId, updated)
                                            config = result
                                            saved = getString(R.string.settings_saved)
                                        } catch (e: Exception) {
                                            AppLog.w("DeviceSettings", "Save failed", e)
                                            saved = null
                                            error = (e as? ApiException)?.friendly
                                                ?: getString(R.string.settings_pending)
                                        }
                                    }
                                },
                            )
                        } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(error ?: getString(R.string.error_generic), color = ParentPalette.Danger)
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_DEVICE_ID = "device_id"
    }
}

@Composable
private fun ConfigEditor(
    initial: DeviceConfig,
    error: String?,
    saved: String?,
    onBack: () -> Unit,
    onSave: (DeviceConfig) -> Unit,
) {
    var nickname by remember(initial.nickname) { mutableStateOf(initial.nickname) }
    var parentName by remember(initial.parentName) { mutableStateOf(initial.parentName) }
    var learningUrl by remember(initial.learningUrl) { mutableStateOf(initial.learningUrl) }
    var playUrl by remember(initial.playUrl) { mutableStateOf(initial.playUrl) }
    var theme by remember(initial.theme) { mutableStateOf(initial.theme) }
    var tiles by remember(initial.enabledTiles) { mutableStateOf(initial.enabledTiles.toSet()) }
    var autoAnswer by remember(initial.autoAnswerVoice) { mutableStateOf(initial.autoAnswerVoice) }
    var quietStart by remember(initial.scheduleStart) { mutableStateOf(initial.scheduleStart) }
    var quietEnd by remember(initial.scheduleEnd) { mutableStateOf(initial.scheduleEnd) }
    var localError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { Text("←", fontSize = 20.sp, color = ParentPalette.Ink) }
            Spacer(Modifier.width(12.dp))
            Text("Child phone settings", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = ParentPalette.Ink)
        }
        Spacer(Modifier.height(16.dp))

        Block("Names") {
            OutlinedTextField(
                value = nickname,
                onValueChange = { if (it.length <= 24) nickname = it },
                label = { Text(stringResource(R.string.settings_nickname)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = parentName,
                onValueChange = { if (it.length <= 24) parentName = it },
                label = { Text(stringResource(R.string.settings_parent_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("sunrise" to "Sunrise", "ocean" to "Ocean", "garden" to "Garden").forEach { (key, label) ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (theme == key) ParentPalette.Accent else ParentPalette.AccentSoft)
                            .clickable { theme = key }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(
                            label,
                            color = if (theme == key) Color.White else ParentPalette.Accent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Block("Learning website") {
            OutlinedTextField(
                value = learningUrl,
                onValueChange = { learningUrl = it },
                label = { Text(stringResource(R.string.settings_learning_url)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = playUrl,
                onValueChange = { playUrl = it },
                label = { Text(stringResource(R.string.settings_play_url)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "The child phone opens this full screen and blocks every other website. " +
                    "Updates take effect without reinstalling the app.",
                fontSize = 11.sp,
                color = ParentPalette.InkMuted,
            )
        }

        Spacer(Modifier.height(12.dp))
        Block("Home screen buttons") {
            listOf(
                "learning" to "My Learning World",
                "call" to "Call me",
                "youtube" to "YouTube Kids",
                "camera" to "My Camera",
                "play" to "Play together",
            ).forEach { (key, label) ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, modifier = Modifier.weight(1f), fontSize = 14.sp, color = ParentPalette.Ink)
                    Switch(
                        checked = tiles.contains(key),
                        onCheckedChange = { checked ->
                            tiles = if (checked) tiles + key else tiles - key
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Block("Calls and quiet hours") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_auto_answer),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ParentPalette.Ink,
                    )
                    Text(
                        stringResource(R.string.settings_auto_answer_help),
                        fontSize = 11.sp,
                        color = ParentPalette.InkMuted,
                    )
                }
                Switch(checked = autoAnswer, onCheckedChange = { autoAnswer = it })
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = quietStart,
                    onValueChange = { quietStart = it.take(5) },
                    label = { Text("From", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = quietEnd,
                    onValueChange = { quietEnd = it.take(5) },
                    label = { Text("Until", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "Leave empty for no quiet hours. Example: 20:00 until 07:00.",
                fontSize = 11.sp,
                color = ParentPalette.InkMuted,
            )
        }

        localError?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = ParentPalette.Danger, fontSize = 13.sp)
        }
        error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = ParentPalette.Danger, fontSize = 13.sp)
        }
        saved?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = ParentPalette.Mint, fontSize = 13.sp)
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                val cleanedLearning = learningUrl.trim().takeIf { it.isNotBlank() }
                    ?.let { UrlPolicy.sanitiseApprovedUrl(it) }
                val cleanedPlay = playUrl.trim().takeIf { it.isNotBlank() }
                    ?.let { UrlPolicy.sanitiseApprovedUrl(it) }
                when {
                    learningUrl.isNotBlank() && cleanedLearning == null ->
                        localError = "The learning website must be an https:// address."
                    playUrl.isNotBlank() && cleanedPlay == null ->
                        localError = "The play website must be an https:// address."
                    !quietStart.isBlank() && !quietEnd.isBlank() &&
                        Schedule.parseWindow(quietStart, quietEnd) == null ->
                        localError = "Quiet hours need two different times in 24h format (e.g. 20:00)."
                    else -> {
                        localError = null
                        onSave(
                            initial.copy(
                                nickname = nickname.trim().ifBlank { "Little Star" },
                                parentName = parentName.trim().ifBlank { "Daddy" },
                                learningUrl = cleanedLearning ?: "",
                                playUrl = cleanedPlay ?: "",
                                theme = theme,
                                enabledTiles = tiles.toList().sorted(),
                                autoAnswerVoice = autoAnswer,
                                // The child phone records acceptance locally; a remote
                                // toggle must never claim it.
                                autoAnswerNoticeAccepted = initial.autoAnswerNoticeAccepted,
                                scheduleStart = if (quietStart.isBlank()) "" else quietStart.trim(),
                                scheduleEnd = if (quietEnd.isBlank()) "" else quietEnd.trim(),
                            ),
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(54.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ParentPalette.Accent),
            shape = RoundedCornerShape(18.dp),
        ) { Text(stringResource(R.string.settings_save), fontSize = 16.sp) }

        Spacer(Modifier.height(8.dp))
        Text(
            "Settings marked here are owned by My Little World. Android and Google " +
                "Family Link controls (installed apps, system permissions, screen unlock) " +
                "stay with those tools.",
            fontSize = 11.sp,
            color = ParentPalette.InkMuted,
        )
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun Block(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ParentPalette.Card),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ParentPalette.InkMuted)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}
