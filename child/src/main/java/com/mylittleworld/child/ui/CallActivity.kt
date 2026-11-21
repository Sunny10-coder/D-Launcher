package com.mylittleworld.child.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import com.mylittleworld.child.ChildApp
import com.mylittleworld.child.R
import com.mylittleworld.child.call.CallTicketJson
import com.mylittleworld.child.core.DeviceActions
import com.mylittleworld.shared.model.CallTicket
import com.mylittleworld.shared.net.ApiException
import com.mylittleworld.shared.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

/**
 * Voice/video call experience.
 *
 * Media runs in the platform WebView (secure app-assets origin) so the app does
 * not need a proprietary WebRTC SDK or Google Play Services. Signalling, tokens
 * and identity come from the family service; the child never sees a credential.
 *
 * Safety: the microphone starts muted, the camera only ever turns on through an
 * explicit on-screen tap, and auto-answer (voice only) is off unless a parent
 * enabled it and accepted the notice on this device.
 */
class CallActivity : ComponentActivity() {

    private var webView: WebView? = null
    private var pendingTicket: CallTicket? = null
    private var pageReady = false
    private var callId: String? = null
    private var direction: String = DIRECTION_INCOMING
    private var media: String = "audio"
    private var parentName: String = "Daddy"
    private var autoAnswer: Boolean = false

    private var callState by mutableStateOf(State.RINGING)
    private var muted by mutableStateOf(true)
    private var speakerOn by mutableStateOf(true)
    private var videoOn by mutableStateOf(false)
    private var statusText by mutableStateOf("")
    private var errorText by mutableStateOf<String?>(null)

    private var ringtone: Ringtone? = null
    private var tts: TextToSpeech? = null
    private var audioFocus: AudioFocusRequest? = null
    private var joinJob: Job? = null

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val mic = grants[Manifest.permission.RECORD_AUDIO] ?: hasMic()
        if (!mic) {
            errorText = getString(R.string.call_no_mic)
            return@registerForActivityResult
        }
        if (callState == State.ANSWERED_PENDING_JOIN) joinCall()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        parseIntent(intent)
        setContent { CallScreen() }
        if (intent.action == ACTION_DECLINE_FROM_NOTIFICATION) {
            declineAndFinish()
            return
        }
        if (direction == DIRECTION_INCOMING) {
            startRinging()
            if (autoAnswer) {
                scope.launch { autoAnswerAfterAnnouncement() }
            }
        } else {
            callState = State.CONNECTING
            requestPermissionsThenJoin()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        parseIntent(intent)
    }

    private fun parseIntent(intent: Intent?) {
        if (intent == null) return
        callId = intent.getStringExtra(EXTRA_CALL_ID)
        direction = intent.getStringExtra(EXTRA_DIRECTION) ?: DIRECTION_INCOMING
        media = intent.getStringExtra(EXTRA_MEDIA) ?: "audio"
        parentName = intent.getStringExtra(EXTRA_PARENT_NAME) ?: "Daddy"
        autoAnswer = intent.getBooleanExtra(EXTRA_AUTO_ANSWER, false)
        if (direction == DIRECTION_OUTGOING) autoAnswer = false
        statusText = if (direction == DIRECTION_INCOMING) {
            getString(R.string.call_incoming, parentName)
        } else {
            getString(R.string.call_calling, parentName)
        }
    }

    override fun onResume() {
        super.onResume()
        if (callState == State.RINGING && direction == DIRECTION_INCOMING) startRinging()
    }

    override fun onPause() {
        super.onPause()
        // Do not stop an active call when the child presses home; only stop the
        // ringtone so it does not play forever in the background.
        if (callState == State.RINGING) stopRinging()
    }

    /* ---------------- ringing ---------------- */

    private fun startRinging() {
        if (ringtone != null) return
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: Uri.parse("content://settings/system/ringtone")
            ringtone = RingtoneManager.getRingtone(applicationContext, uri).apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                play()
            }
            DeviceActions.vibration(this)?.let { vibrator ->
                val pattern = longArrayOf(0, 700, 500)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
                }
            }
        }.onFailure { AppLog.w("CallActivity", "Ringtone unavailable", it) }
    }

    private fun stopRinging() {
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { DeviceActions.vibration(this)?.cancel() }
    }

    /* ---------------- answering ---------------- */

    private fun hasMic(): Boolean = ContextCompat.checkSelfPermission(
        this, Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    private fun hasCamera(): Boolean = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA,
    ) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissionsThenJoin() {
        val missing = mutableListOf<String>()
        if (!hasMic()) missing.add(Manifest.permission.RECORD_AUDIO)
        if (missing.isEmpty()) {
            joinCall()
        } else {
            callState = State.ANSWERED_PENDING_JOIN
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun answer() {
        stopRinging()
        com.mylittleworld.child.service.Notifications.cancelIncomingCall(this)
        callState = State.ANSWERED_PENDING_JOIN
        statusText = getString(R.string.call_connecting)
        requestPermissionsThenJoin()
    }

    /**
     * Parent-enabled auto-answer. Voice only, announced out loud, and always with
     * visible End Call controls. Android may have blocked this activity launch;
     * in that case the child sees the normal incoming-call notification instead.
     */
    private suspend fun autoAnswerAfterAnnouncement() {
        val app = application as? ChildApp ?: return
        val config = app.container.settings.currentConfig()
        val accepted = app.container.settings.autoAnswerAccepted.first()
        if (!config.autoAnswerVoice || !accepted || media != "audio") {
            return
        }
        announce("${config.parentName} is calling. Answering now.")
        delay(1800)
        if (callState == State.RINGING) {
            statusText = getString(R.string.call_auto_answer_notice, parentName)
            answer()
        }
    }

    private fun announce(text: String) {
        val engine = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "mlw-call-announce")
            }
        }
        tts = engine
    }

    /* ---------------- media ---------------- */

    private fun joinCall() {
        joinJob?.cancel()
        joinJob = scope.launch {
            callState = State.CONNECTING
            try {
                val app = application as ChildApp
                val api = app.container.childApi()
                val ticket = if (callId == null) {
                    val created = api.startOutgoingCall(media)
                    callId = created.callId
                    api.joinCall(created.callId)
                } else {
                    api.joinCall(callId!!)
                }
                pendingTicket = ticket
                if (ticket.mode == "simulated") {
                    callState = State.SIMULATED
                    statusText = getString(R.string.call_not_configured)
                    return@launch
                }
                configureAudioForCall()
                startWebViewSession(ticket)
            } catch (e: ApiException) {
                AppLog.w("CallActivity", "Join failed: ${e.kind}")
                errorText = getString(R.string.call_failed)
                callState = State.ENDED
            } catch (e: Exception) {
                AppLog.w("CallActivity", "Join failed", e)
                errorText = getString(R.string.call_failed)
                callState = State.ENDED
            }
        }
    }

    private fun startWebViewSession(ticket: CallTicket) {
        pendingTicket = ticket
        val view = webView ?: return
        if (!pageReady) return
        val bootstrap = CallTicketJson.toBootstrap(ticket, startsMuted = true)
        val quoted = org.json.JSONObject.quote(bootstrap)
        view.evaluateJavascript("window.mlwStart($quoted);", null)
        statusText = getString(R.string.call_connecting)
    }

    private fun configureAudioForCall() {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        runCatching {
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            audio.isSpeakerphoneOn = true
            speakerOn = true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .build()
            audioFocus = focus
            audio.requestAudioFocus(focus)
        }
    }

    private fun releaseAudio() {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        runCatching {
            audio.mode = AudioManager.MODE_NORMAL
            audio.isSpeakerphoneOn = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocus?.let { audio.abandonAudioFocusRequest(it) }
        }
        audioFocus = null
    }

    private fun createWebView(): WebView {
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        return WebView(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setGeolocationEnabled(false)
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    val origin = request.origin?.toString().orEmpty()
                    if (!origin.startsWith(ASSET_ORIGIN)) {
                        request.deny()
                        return
                    }
                    val requested = request.resources.toSet()
                    val grants = mutableListOf<String>()
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in requested && hasMic()) {
                        grants.add(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                    }
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in requested && hasCamera()) {
                        grants.add(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                    }
                    if (grants.isEmpty()) request.deny()
                    else request.grant(grants.toTypedArray())
                }
            }
            addJavascriptInterface(CallBridge(), "AndroidCallBridge")
            webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    pageReady = true
                    pendingTicket?.let { ticket -> startWebViewSession(ticket) }
                }

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: android.webkit.WebResourceRequest,
                ): android.webkit.WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: android.webkit.WebResourceRequest,
                ): Boolean = !request.url.toString().startsWith(ASSET_ORIGIN)
            }
            loadUrl("$ASSET_ORIGIN/assets/call/call.html")
        }
    }

    inner class CallBridge {
        @JavascriptInterface
        fun post(json: String) {
            runOnUiThread { handleBridgeEvent(json) }
        }
    }

    private fun handleBridgeEvent(json: String) {
        val obj = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return
        val type = obj["type"]?.jsonPrimitive?.content ?: return
        when (type) {
            "state" -> {
                when (obj["value"]?.jsonPrimitive?.content) {
                    "connected" -> {
                        callState = State.CONNECTED
                        statusText = getString(R.string.call_connected, parentName)
                    }
                    "connecting" -> {
                        callState = State.CONNECTING
                        statusText = getString(R.string.call_connecting)
                    }
                    "reconnecting" -> {
                        callState = State.RECONNECTING
                        statusText = getString(R.string.call_reconnecting)
                    }
                    "media-flowing" -> Unit
                    "simulated" -> {
                        callState = State.SIMULATED
                        statusText = getString(R.string.call_not_configured)
                    }
                    "failed" -> {
                        errorText = getString(R.string.call_failed)
                        callState = State.ENDED
                    }
                    "ended" -> if (callState != State.ENDED) callState = State.ENDED
                }
            }
            "error" -> {
                val message = obj["message"]?.jsonPrimitive?.content ?: ""
                errorText = when (message) {
                    "permission" -> getString(R.string.call_no_mic)
                    "camera" -> getString(R.string.camera_permission)
                    else -> getString(R.string.call_failed)
                }
            }
            "closed" -> {
                if (callState != State.ENDED) callState = State.ENDED
            }
            "media" -> Unit
            else -> Unit
        }
    }

    /* ---------------- controls ---------------- */

    private fun toggleMute() {
        muted = !muted
        webView?.evaluateJavascript("window.mlwSetMuted(${if (muted) "true" else "false"});", null)
    }

    private fun toggleSpeaker() {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        speakerOn = !speakerOn
        runCatching { audio.isSpeakerphoneOn = speakerOn }
    }

    private fun toggleVideo() {
        if (!videoOn && !hasCamera()) {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
            return
        }
        videoOn = !videoOn
        webView?.evaluateJavascript("window.mlwSetVideo(${if (videoOn) "true" else "false"});", null)
    }

    private fun hangUp() {
        runCatching { webView?.evaluateJavascript("window.mlwHangup();", null) }
        if (callState == State.RINGING) declineAndFinish() else finishCall()
    }

    private fun declineAndFinish() {
        val id = callId
        if (id != null) {
            scope.launch {
                runCatching {
                    (application as ChildApp).container.childApi().declineCall(id)
                }
            }
        }
        stopRinging()
        com.mylittleworld.child.service.Notifications.cancelIncomingCall(this)
        finish()
    }

    private fun finishCall() {
        stopRinging()
        releaseAudio()
        runCatching { webView?.destroy() }
        webView = null
        com.mylittleworld.child.service.Notifications.cancelIncomingCall(this)
        scope.launch {
            delay(600)
            finishAndRemoveTask()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        joinJob?.cancel()
        stopRinging()
        releaseAudio()
        runCatching { tts?.shutdown() }
        tts = null
        runCatching { webView?.destroy() }
        webView = null
    }

    /* ---------------- UI ---------------- */

    @Composable
    private fun CallScreen() {
        val context = LocalContext.current
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(Modifier),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Spacer(Modifier.height(24.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier.size(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    listOf(Color(0xFFFFE0B2), Color(0xFFFF9F7A), Color(0xFFD96B8A)),
                                ),
                            )
                        }
                        Text(
                            text = parentName.take(1).uppercase(),
                            fontSize = 48.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF3B2E3F),
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    Text(
                        text = statusText,
                        color = Color.White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                    errorText?.let { message ->
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = message,
                            color = Color(0xFFFFD9D9),
                            fontSize = 16.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (callState == State.RINGING) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            ActionButton(
                                label = context.getString(R.string.call_end),
                                icon = ActionIcon.END,
                                color = Color(0xFFE05263),
                                onClick = { declineAndFinish() },
                            )
                            ActionButton(
                                label = context.getString(R.string.call_answer),
                                icon = ActionIcon.ANSWER,
                                color = Color(0xFF4CAF7D),
                                onClick = { answer() },
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            ActionButton(
                                label = if (muted) context.getString(R.string.call_unmute)
                                else context.getString(R.string.call_mute),
                                icon = ActionIcon.MIC,
                                color = if (muted) Color(0xFF6B5B70) else Color(0xFF8A7B92),
                                onClick = { toggleMute() },
                            )
                            ActionButton(
                                label = context.getString(R.string.call_speaker),
                                icon = ActionIcon.SPEAKER,
                                color = if (speakerOn) Color(0xFF8E6BD1) else Color(0xFF6B5B70),
                                onClick = { toggleSpeaker() },
                            )
                            ActionButton(
                                label = if (videoOn) context.getString(R.string.call_video_off)
                                else context.getString(R.string.call_video_on),
                                icon = ActionIcon.CAMERA,
                                color = if (videoOn) Color(0xFF8E6BD1) else Color(0xFF6B5B70),
                                onClick = { toggleVideo() },
                            )
                        }
                        Spacer(Modifier.height(20.dp))
                        ActionButton(
                            label = context.getString(R.string.call_end),
                            icon = ActionIcon.END,
                            color = Color(0xFFE05263),
                            onClick = { hangUp() },
                        )
                    }
                    Spacer(Modifier.height(32.dp))
                }
            }

            // The media surface sits behind the controls so taps always reach the
            // large, clearly labelled buttons above it.
            if (callState != State.RINGING) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { createWebView().also { webView = it } },
                )
            }
        }
    }

    @Composable
    private fun ActionButton(
        label: String,
        icon: ActionIcon,
        color: Color,
        onClick: () -> Unit,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(color)
                    .clickable(onClick = onClick)
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                CallGlyph(icon)
            }
            Spacer(Modifier.height(8.dp))
            Text(label, color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center)
        }
    }

    @Composable
    private fun CallGlyph(icon: ActionIcon) {
        androidx.compose.foundation.Canvas(Modifier.size(32.dp)) {
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
                width = size.minDimension * 0.1f,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
            when (icon) {
                ActionIcon.END -> {
                    // Handset rotated down: a simple rounded bar + arc.
                    drawArc(
                        color = Color.White,
                        startAngle = 200f,
                        sweepAngle = 140f,
                        useCenter = false,
                        style = stroke,
                        topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.1f, size.height * 0.35f),
                        size = androidx.compose.ui.geometry.Size(size.width * 0.8f, size.height * 0.55f),
                    )
                }
                ActionIcon.ANSWER -> {
                    drawArc(
                        color = Color.White,
                        startAngle = 200f,
                        sweepAngle = 140f,
                        useCenter = false,
                        style = stroke,
                        topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.1f, size.height * 0.35f),
                        size = androidx.compose.ui.geometry.Size(size.width * 0.8f, size.height * 0.55f),
                    )
                    drawLine(
                        color = Color.White,
                        start = androidx.compose.ui.geometry.Offset(size.width * 0.2f, size.height * 0.72f),
                        end = androidx.compose.ui.geometry.Offset(size.width * 0.55f, size.height * 0.45f),
                        strokeWidth = size.minDimension * 0.1f,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    )
                }
                ActionIcon.MIC -> {
                    drawRoundRect(
                        color = Color.White,
                        topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.35f, size.height * 0.12f),
                        size = androidx.compose.ui.geometry.Size(size.width * 0.3f, size.height * 0.5f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.15f),
                        style = stroke,
                    )
                    drawArc(
                        color = Color.White,
                        startAngle = 0f,
                        sweepAngle = 180f,
                        useCenter = false,
                        style = stroke,
                        topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.22f, size.height * 0.3f),
                        size = androidx.compose.ui.geometry.Size(size.width * 0.56f, size.height * 0.45f),
                    )
                    drawLine(
                        color = Color.White,
                        start = androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 0.78f),
                        end = androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 0.92f),
                        strokeWidth = size.minDimension * 0.1f,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    )
                }
                ActionIcon.SPEAKER -> {
                    drawRoundRect(
                        color = Color.White,
                        topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.1f, size.height * 0.35f),
                        size = androidx.compose.ui.geometry.Size(size.width * 0.3f, size.height * 0.3f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.05f),
                    )
                    drawLine(
                        color = Color.White,
                        start = androidx.compose.ui.geometry.Offset(size.width * 0.4f, size.height * 0.5f),
                        end = androidx.compose.ui.geometry.Offset(size.width * 0.68f, size.height * 0.2f),
                        strokeWidth = size.minDimension * 0.1f,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    )
                    drawLine(
                        color = Color.White,
                        start = androidx.compose.ui.geometry.Offset(size.width * 0.4f, size.height * 0.5f),
                        end = androidx.compose.ui.geometry.Offset(size.width * 0.68f, size.height * 0.8f),
                        strokeWidth = size.minDimension * 0.1f,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    )
                }
                ActionIcon.CAMERA -> {
                    drawRoundRect(
                        color = Color.White,
                        topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.1f, size.height * 0.25f),
                        size = androidx.compose.ui.geometry.Size(size.width * 0.8f, size.height * 0.55f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.12f),
                        style = stroke,
                    )
                    drawCircle(
                        color = Color.White,
                        radius = size.minDimension * 0.14f,
                        center = androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 0.52f),
                        style = stroke,
                    )
                }
            }
        }
    }

    private enum class ActionIcon { END, ANSWER, MIC, SPEAKER, CAMERA }

    enum class State {
        RINGING, ANSWERED_PENDING_JOIN, CONNECTING, CONNECTED, RECONNECTING, SIMULATED, ENDED,
    }

    companion object {
        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_DIRECTION = "direction"
        const val EXTRA_MEDIA = "media"
        const val EXTRA_PARENT_NAME = "parent_name"
        const val EXTRA_AUTO_ANSWER = "auto_answer"
        const val ACTION_DECLINE_FROM_NOTIFICATION = "com.mylittleworld.child.DECLINE"
        const val DIRECTION_INCOMING = "in"
        const val DIRECTION_OUTGOING = "out"
        private const val ASSET_ORIGIN = "https://appassets.androidplatform.net"

        fun incomingIntent(
            context: Context,
            callId: String,
            media: String,
            parentName: String,
            autoAnswer: Boolean,
        ): Intent = Intent(context, CallActivity::class.java).apply {
            putExtra(EXTRA_CALL_ID, callId)
            putExtra(EXTRA_DIRECTION, DIRECTION_INCOMING)
            putExtra(EXTRA_MEDIA, media)
            putExtra(EXTRA_PARENT_NAME, parentName)
            putExtra(EXTRA_AUTO_ANSWER, autoAnswer)
        }

        fun outgoingIntent(context: Context, media: String, parentName: String): Intent =
            Intent(context, CallActivity::class.java).apply {
                putExtra(EXTRA_DIRECTION, DIRECTION_OUTGOING)
                putExtra(EXTRA_MEDIA, media)
                putExtra(EXTRA_PARENT_NAME, parentName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}
