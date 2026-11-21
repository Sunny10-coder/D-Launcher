package com.mylittleworld.child.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
import com.mylittleworld.child.ui.components.GrownUpsIcon
import com.mylittleworld.child.ui.components.PinDialog
import com.mylittleworld.child.ui.components.TileIcon
import com.mylittleworld.child.ui.components.TileKind
import com.mylittleworld.child.ui.theme.MyLittleWorldTheme
import com.mylittleworld.child.ui.theme.Palette
import com.mylittleworld.shared.logic.PinPolicy
import com.mylittleworld.shared.logic.Schedule
import com.mylittleworld.shared.model.DeviceConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The HOME activity. Runs as the default launcher when the parent selects it in
 * Android's "Home app" chooser; everything here must survive a cold start in
 * well under a second on old hardware, so nothing blocks the first frame.
 */
class LauncherActivity : ComponentActivity() {

    private val container get() = (application as ChildApp).container
    private var reduceMotion: Boolean = false

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            // Calling still works in-app; the notification path degrades. Nothing
            // is blocked, the child is never trapped behind a dialog.
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reduceMotion = animationsDisabled()
        maybeAskForNotifications()
        setContent {
            MyLittleWorldTheme {
                val config by container.config.collectAsStateWithLifecycle()
                val paired by container.paired.collectAsStateWithLifecycle()
                val serverUrl by container.serverUrl.collectAsStateWithLifecycle()
                var pinGate by remember { mutableStateOf<PinGate?>(null) }
                var pinError by remember { mutableStateOf<String?>(null) }
                var banner by remember { mutableStateOf<String?>(null) }

                LaunchedEffect(paired) {
                    if (paired) FamilyConnectService.start(this@LauncherActivity)
                }

                HomeScreen(
                    config = config,
                    paired = paired,
                    localDevMode = serverUrl.contains(".invalid") || serverUrl.contains("10.0.2.2"),
                    reduceMotion = reduceMotion,
                    onOpenLearning = { openLearning(LEARN_URL, translate(R.string.tile_learning)) },
                    onCall = { startActivity(CallActivity.outgoingIntent(this@LauncherActivity, "audio", config.parentName)) },
                    onYouTube = { openYouTube() },
                    onCamera = { openCamera() },
                    onPlay = { openLearning(PLAY_URL, translate(R.string.tile_play, config.parentName)) },
                    onGrownUps = { pinGate = PinGate.VERIFY },
                    onPair = {
                        startActivity(Intent(this@LauncherActivity, PairingActivity::class.java))
                    },
                    banner = banner,
                    onDismissBanner = { banner = null },
                )

                val storedPin = container.settings.pinHash
                    .collectAsStateWithLifecycle(initialValue = null).value
                when (pinGate) {
                    PinGate.VERIFY -> {
                        val createMode = storedPin == null
                        PinDialog(
                            title = stringResource(
                                if (createMode) R.string.pin_set_title else R.string.settings_locked,
                            ),
                            helper = stringResource(
                                if (createMode) R.string.pin_set_help else R.string.pin_prompt,
                            ),
                            errorText = pinError,
                            onCancel = { pinGate = null; pinError = null },
                            onSubmit = { pin ->
                                if (createMode) {
                                    if (!PinPolicy.isValidPin(pin)) {
                                        pinError = "Use 4 to 8 numbers."
                                    } else {
                                        lifecycleScope.launch {
                                            container.settings.savePinHash(PinPolicy.hash(pin))
                                            pinGate = null
                                            pinError = null
                                            openSettings()
                                        }
                                    }
                                } else if (PinPolicy.verify(pin, storedPin)) {
                                    pinGate = null
                                    pinError = null
                                    openSettings()
                                } else {
                                    pinError = getString(R.string.pin_wrong)
                                }
                            },
                        )
                    }
                    null -> Unit
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        val paired = runCatching {
            kotlinx.coroutines.runBlocking { container.settings.paired.first() }
        }.getOrDefault(false)
        if (paired) FamilyConnectService.start(this)
    }

    /**
     * A launcher cannot be meaningfully "backed out of": pressing back returns to
     * the same home screen instead of exposing the system launcher.
     */
    @SuppressLint("MissingSuperCall") // A HOME activity must not navigate back.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Intentionally ignored: Back must not leave the child launcher.
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun openLearning(mode: String, label: String) {
        val config = container.config.value
        val quiet = isQuietHours(config)
        if (quiet) {
            // The interface explains the pause rather than failing silently.
            android.widget.Toast.makeText(
                this,
                getString(R.string.quiet_hours_body, config.scheduleEnd),
                android.widget.Toast.LENGTH_LONG,
            ).show()
            return
        }
        startActivity(
            Intent(this, LearningActivity::class.java)
                .putExtra(LearningActivity.EXTRA_MODE, mode)
                .putExtra(LearningActivity.EXTRA_TITLE, label),
        )
    }

    private fun openCamera() {
        startActivity(Intent(this, CameraActivity::class.java))
    }

    private fun openYouTube() {
        if (DeviceActions.isInstalled(this, DeviceActions.YOUTUBE_KIDS)) {
            val launched = DeviceActions.launchPackage(this, DeviceActions.YOUTUBE_KIDS)
            if (!launched) {
                android.widget.Toast.makeText(
                    this,
                    getString(R.string.youtube_missing_body),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
                DeviceActions.openPlayStore(this)
            }
        } else {
            startActivity(
                Intent(this, LearningActivity::class.java)
                    .putExtra(LearningActivity.EXTRA_MODE, YOUTUBE_INFO),
            )
        }
    }

    private fun maybeAskForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun translate(resId: Int, vararg args: Any): String = getString(resId, *args)

    private fun isQuietHours(config: DeviceConfig): Boolean {
        val window = Schedule.parseWindow(config.scheduleStart, config.scheduleEnd) ?: return false
        val calendar = Calendar.getInstance()
        return Schedule.isWithin(window, calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE))
    }

    private fun animationsDisabled(): Boolean = runCatching {
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)

    private enum class PinGate { VERIFY }

    companion object {
        const val LEARN_URL = "learning"
        const val PLAY_URL = "play"
        private const val YOUTUBE_INFO = "youtube_info"
    }
}

@Composable
private fun HomeScreen(
    config: DeviceConfig,
    paired: Boolean,
    localDevMode: Boolean,
    reduceMotion: Boolean,
    onOpenLearning: () -> Unit,
    onCall: () -> Unit,
    onYouTube: () -> Unit,
    onCamera: () -> Unit,
    onPlay: () -> Unit,
    onGrownUps: () -> Unit,
    onPair: () -> Unit,
    banner: String?,
    onDismissBanner: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.background(config.theme)),
    ) {
        val landscape = maxWidth > maxHeight
        AmbientBackground(reduceMotion)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 22.dp, vertical = 14.dp),
        ) {
            Header(
                config = config,
                paired = paired,
                localDevMode = localDevMode,
                onGrownUps = onGrownUps,
            )
            Spacer(Modifier.height(12.dp))
            if (!paired) {
                PairingCard(onPair)
                Spacer(Modifier.height(10.dp))
            }
            banner?.let {
                InfoBanner(text = it, onDismiss = onDismissBanner)
                Spacer(Modifier.height(10.dp))
            }

            val learningEnabled = config.tileEnabled("learning")
            val callEnabled = config.tileEnabled("call")
            val youtubeEnabled = config.tileEnabled("youtube")
            val cameraEnabled = config.tileEnabled("camera")
            val playEnabled = config.tileEnabled("play") && config.playUrl.isNotBlank()

            if (landscape) {
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(1.05f).fillMaxHeight()) {
                        HeroTile(
                            kind = TileKind.LEARNING,
                            title = stringResource(R.string.tile_learning),
                            subtitle = stringResource(R.string.tile_learning_sub),
                            gradient = Palette.tileGradient("learning", config.theme),
                            enabled = learningEnabled,
                            a11y = stringResource(R.string.a11y_open_learning),
                            reduceMotion = reduceMotion,
                            onClick = onOpenLearning,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            SquareTile(
                                kind = TileKind.CALL,
                                title = stringResource(R.string.tile_call, config.parentName),
                                subtitle = stringResource(R.string.tile_call_sub),
                                gradient = Palette.tileGradient("call", config.theme),
                                enabled = callEnabled,
                                a11y = stringResource(R.string.a11y_call_parent, config.parentName),
                                reduceMotion = reduceMotion,
                                onClick = onCall,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                            SquareTile(
                                kind = TileKind.VIDEO,
                                title = stringResource(R.string.tile_youtube),
                                subtitle = stringResource(R.string.tile_youtube_sub),
                                gradient = Palette.tileGradient("youtube", config.theme),
                                enabled = youtubeEnabled,
                                a11y = stringResource(R.string.a11y_open_youtube),
                                reduceMotion = reduceMotion,
                                onClick = onYouTube,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            SquareTile(
                                kind = TileKind.CAMERA,
                                title = stringResource(R.string.tile_camera),
                                subtitle = stringResource(R.string.tile_camera_sub),
                                gradient = Palette.tileGradient("camera", config.theme),
                                enabled = cameraEnabled,
                                a11y = stringResource(R.string.a11y_open_camera),
                                reduceMotion = reduceMotion,
                                onClick = onCamera,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                            SquareTile(
                                kind = TileKind.PLAY,
                                title = stringResource(R.string.tile_play, config.parentName),
                                subtitle = if (playEnabled) stringResource(R.string.tile_play_sub)
                                else "Ask a grown-up to set this up",
                                gradient = Palette.tileGradient("play", config.theme),
                                enabled = playEnabled,
                                a11y = stringResource(R.string.a11y_open_play, config.parentName),
                                reduceMotion = reduceMotion,
                                onClick = onPlay,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    HeroTile(
                        kind = TileKind.LEARNING,
                        title = stringResource(R.string.tile_learning),
                        subtitle = stringResource(R.string.tile_learning_sub),
                        gradient = Palette.tileGradient("learning", config.theme),
                        enabled = learningEnabled,
                        a11y = stringResource(R.string.a11y_open_learning),
                        reduceMotion = reduceMotion,
                        onClick = onOpenLearning,
                        modifier = Modifier.weight(1.15f).fillMaxWidth(),
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        SquareTile(
                            kind = TileKind.CALL,
                            title = stringResource(R.string.tile_call, config.parentName),
                            subtitle = stringResource(R.string.tile_call_sub),
                            gradient = Palette.tileGradient("call", config.theme),
                            enabled = callEnabled,
                            a11y = stringResource(R.string.a11y_call_parent, config.parentName),
                            reduceMotion = reduceMotion,
                            onClick = onCall,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                        SquareTile(
                            kind = TileKind.VIDEO,
                            title = stringResource(R.string.tile_youtube),
                            subtitle = stringResource(R.string.tile_youtube_sub),
                            gradient = Palette.tileGradient("youtube", config.theme),
                            enabled = youtubeEnabled,
                            a11y = stringResource(R.string.a11y_open_youtube),
                            reduceMotion = reduceMotion,
                            onClick = onYouTube,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        SquareTile(
                            kind = TileKind.CAMERA,
                            title = stringResource(R.string.tile_camera),
                            subtitle = stringResource(R.string.tile_camera_sub),
                            gradient = Palette.tileGradient("camera", config.theme),
                            enabled = cameraEnabled,
                            a11y = stringResource(R.string.a11y_open_camera),
                            reduceMotion = reduceMotion,
                            onClick = onCamera,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                        SquareTile(
                            kind = TileKind.PLAY,
                            title = stringResource(R.string.tile_play, config.parentName),
                            subtitle = if (playEnabled) stringResource(R.string.tile_play_sub)
                            else "Ask a grown-up to set this up",
                            gradient = Palette.tileGradient("play", config.theme),
                            enabled = playEnabled,
                            a11y = stringResource(R.string.a11y_open_play, config.parentName),
                            reduceMotion = reduceMotion,
                            onClick = onPlay,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun Header(
    config: DeviceConfig,
    paired: Boolean,
    localDevMode: Boolean,
    onGrownUps: () -> Unit,
) {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val greetingRes = when (hour) {
        in 5..11 -> R.string.greeting_morning
        in 12..17 -> R.string.greeting_day
        else -> R.string.greeting_evening
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(greetingRes),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = Palette.InkSoft,
            )
            Text(
                text = config.nickname,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                color = Palette.Ink,
                letterSpacing = (-0.5).sp,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date()),
                fontSize = 13.sp,
                color = Palette.InkSoft,
            )
            if (!paired || localDevMode) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (!paired) stringResource(R.string.settings_not_paired)
                    else stringResource(R.string.dev_mode_banner),
                    fontSize = 11.sp,
                    color = Palette.InkSoft,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.75f))
                .clickable(onClick = onGrownUps)
                .semantics {
                    contentDescription = "Grown-up settings"
                    role = Role.Button
                },
            contentAlignment = Alignment.Center,
        ) {
            GrownUpsIcon(modifier = Modifier.size(26.dp), color = Palette.InkSoft)
        }
    }
}

@Composable
private fun PairingCard(onPair: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Color.White.copy(alpha = 0.8f))
            .clickable(onClick = onPair)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.onboarding_title),
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = Palette.Ink,
            )
            Text(
                stringResource(R.string.onboarding_body),
                fontSize = 13.sp,
                color = Palette.InkSoft,
            )
        }
        Text(
            stringResource(R.string.onboarding_pair),
            fontWeight = FontWeight.Bold,
            color = Palette.LearningEnd,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun InfoBanner(text: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFFFFF1CC))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, modifier = Modifier.weight(1f), fontSize = 13.sp, color = Palette.Ink)
        TextButton(onClick = onDismiss) { Text("OK", fontSize = 13.sp) }
    }
}

@Composable
private fun HeroTile(
    kind: TileKind,
    title: String,
    subtitle: String,
    gradient: List<Color>,
    enabled: Boolean,
    a11y: String,
    reduceMotion: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TileSurface(
        modifier = modifier,
        gradient = gradient,
        enabled = enabled,
        a11y = a11y,
        reduceMotion = reduceMotion,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = Color.White,
                    fontSize = 30.sp,
                    lineHeight = 34.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    subtitle,
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.24f))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        if (enabled) "Start" else "Ask a grown-up",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            TileIcon(kind, modifier = Modifier.size(84.dp))
        }
    }
}

@Composable
private fun SquareTile(
    kind: TileKind,
    title: String,
    subtitle: String,
    gradient: List<Color>,
    enabled: Boolean,
    a11y: String,
    reduceMotion: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TileSurface(
        modifier = modifier,
        gradient = gradient,
        enabled = enabled,
        a11y = a11y,
        reduceMotion = reduceMotion,
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(18.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            TileIcon(kind, modifier = Modifier.size(46.dp))
            Column {
                Text(
                    title,
                    color = Color.White,
                    fontSize = 19.sp,
                    lineHeight = 23.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    subtitle,
                    color = Color.White.copy(alpha = 0.88f),
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun TileSurface(
    modifier: Modifier,
    gradient: List<Color>,
    enabled: Boolean,
    a11y: String,
    reduceMotion: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptics = LocalHapticFeedback.current
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduceMotion) 0.965f else 1f,
        animationSpec = tween(durationMillis = if (reduceMotion) 0 else 140),
        label = "tileScale",
    )
    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(32.dp))
            .background(
                Brush.linearGradient(
                    colors = if (enabled) gradient else gradient.map { it.copy(alpha = 0.45f) },
                    start = Offset.Zero,
                    end = Offset.Infinite,
                ),
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .semantics(mergeDescendants = true) {
                contentDescription = a11y
                role = Role.Button
            },
    ) {
        content()
    }
}

@Composable
private fun AmbientBackground(reduceMotion: Boolean) {
    val transition = rememberInfiniteTransition(label = "ambient")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 9000),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "drift",
    )
    val motion = if (reduceMotion) 0f else drift
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color(0xFFFFC9A8).copy(alpha = 0.55f), Color.Transparent),
                center = Offset(size.width * (0.15f + 0.06f * motion), size.height * 0.12f),
                radius = size.minDimension * 0.55f,
            ),
            radius = size.minDimension * 0.55f,
            center = Offset(size.width * (0.15f + 0.06f * motion), size.height * 0.12f),
        )
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color(0xFFB9E4FF).copy(alpha = 0.45f), Color.Transparent),
                center = Offset(size.width * (0.85f - 0.05f * motion), size.height * 0.78f),
                radius = size.minDimension * 0.5f,
            ),
            radius = size.minDimension * 0.5f,
            center = Offset(size.width * (0.85f - 0.05f * motion), size.height * 0.78f),
        )
    }
}
