package com.mylittleworld.parent.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import com.mylittleworld.parent.ParentApp
import com.mylittleworld.parent.R
import com.mylittleworld.shared.model.CallTicket
import com.mylittleworld.shared.net.ApiException
import com.mylittleworld.shared.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parent-side call screen. Joins the same private room as the child phone through
 * the family service; media itself is peer-to-peer WebRTC.
 */
class ParentCallActivity : ComponentActivity() {

    private val container get() = (application as ParentApp).container
    private var webView: WebView? = null
    private var pageReady = false
    private var pendingTicket: CallTicket? = null
    private var callId: String? = null
    private var media: String = "audio"
    private var scope = CoroutineScope(Dispatchers.Main + Job())
    private var audioFocus: AudioFocusRequest? = null

    private var callState by mutableStateOf(State.CONNECTING)
    private var statusText by mutableStateOf("")
    private var errorText by mutableStateOf<String?>(null)
    private var muted by mutableStateOf(true)
    private var speakerOn by mutableStateOf(true)
    private var videoOn by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == false) {
            errorText = getString(R.string.call_failed)
        } else {
            join()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        callId = intent.getStringExtra(EXTRA_CALL_ID)
        media = intent.getStringExtra(EXTRA_MEDIA) ?: "audio"
        statusText = getString(R.string.call_connecting)
        setContent { CallUi() }
        requestMicrophoneThenJoin()
    }

    private fun requestMicrophoneThenJoin() {
        val needed = mutableListOf<String>()
        if (!hasMic()) needed.add(Manifest.permission.RECORD_AUDIO)
        if (media == "video" && !hasCamera()) needed.add(Manifest.permission.CAMERA)
        if (needed.isEmpty()) join() else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    private fun hasCamera() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA,
    ) == PackageManager.PERMISSION_GRANTED

    private fun join() {
        val id = callId ?: run {
            errorText = getString(R.string.call_failed)
            return
        }
        scope.launch {
            try {
                val ticket = container.api().joinCall(id)
                if (ticket.mode == "simulated") {
                    callState = State.SIMULATED
                    statusText = getString(R.string.call_not_configured)
                    return@launch
                }
                configureAudio()
                pendingTicket = ticket
                startSessionIfReady()
            } catch (e: ApiException) {
                AppLog.w("ParentCall", "Join failed: ${e.kind}")
                errorText = getString(R.string.call_failed)
                callState = State.ENDED
            }
        }
    }

    private fun startSessionIfReady() {
        val ticket = pendingTicket ?: return
        val view = webView ?: return
        if (!pageReady) return
        val quoted = org.json.JSONObject.quote(bootstrapJson(ticket))
        view.evaluateJavascript("window.mlwStart($quoted);", null)
        statusText = getString(R.string.call_connecting)
    }

    private fun bootstrapJson(ticket: CallTicket): String {
        val ice = ticket.iceServers.map { server ->
            buildString {
                append("{\"urls\":[")
                append(server.urls.joinToString(",") { "\"$it\"" })
                append("]")
                server.username?.let { append(",\"username\":\"$it\"") }
                server.credential?.let { append(",\"credential\":\"$it\"") }
                append("}")
            }
        }
        return buildString {
            append("{")
            append("\"websocketUrl\":\"${ticket.websocketUrl}\",")
            append("\"callId\":\"${ticket.callId}\",")
            append("\"role\":\"parent\",")
            append("\"media\":\"${if (media == "video") "video" else "audio"}\",")
            append("\"parentName\":\"${container.prefs.parentName ?: "Parent"}\",")
            append("\"mode\":\"${ticket.mode}\",")
            append("\"startsMuted\":true,")
            append("\"iceServers\":[")
            append(ice.joinToString(","))
            append("]}")
        }
    }

    private fun configureAudio() {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        runCatching {
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            audio.isSpeakerphoneOn = media == "video"
            speakerOn = audio.isSpeakerphoneOn
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

    private fun buildWebView(): WebView {
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        return WebView(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    if (!request.origin.toString().startsWith(ASSET_ORIGIN)) {
                        request.deny()
                        return
                    }
                    val grants = mutableListOf<String>()
                    if (hasMic()) grants.add(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                    if (hasCamera()) grants.add(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                    if (grants.isEmpty()) request.deny()
                    else request.grant(grants.toTypedArray())
                }
            }
            addJavascriptInterface(Bridge(), "AndroidCallBridge")
            webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    pageReady = true
                    startSessionIfReady()
                }

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: android.webkit.WebResourceRequest,
                ): android.webkit.WebResourceResponse? = loader.shouldInterceptRequest(request.url)

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: android.webkit.WebResourceRequest,
                ): Boolean = !request.url.toString().startsWith(ASSET_ORIGIN)
            }
            loadUrl("$ASSET_ORIGIN/assets/call/call.html")
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun post(json: String) {
            runOnUiThread {
                val obj = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return@runOnUiThread
                when (obj["type"]?.jsonPrimitive?.content) {
                    "state" -> when (obj["value"]?.jsonPrimitive?.content) {
                        "connected" -> {
                            callState = State.CONNECTED
                            statusText = getString(R.string.call_connected)
                        }
                        "reconnecting" -> {
                            callState = State.RECONNECTING
                            statusText = getString(R.string.call_reconnecting)
                        }
                        "ended" -> callState = State.ENDED
                        "simulated" -> {
                            callState = State.SIMULATED
                            statusText = getString(R.string.call_not_configured)
                        }
                        "failed" -> {
                            errorText = getString(R.string.call_failed)
                            callState = State.ENDED
                        }
                    }
                    "error" -> errorText = getString(R.string.call_failed)
                }
            }
        }
    }

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
        if (!hasCamera()) {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
            return
        }
        videoOn = !videoOn
        webView?.evaluateJavascript("window.mlwSetVideo(${if (videoOn) "true" else "false"});", null)
    }

    private fun hangUp() {
        runCatching { webView?.evaluateJavascript("window.mlwHangup();", null) }
        releaseAudio()
        scope.launch {
            delay(400)
            finishAndRemoveTask()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseAudio()
        runCatching { webView?.destroy() }
        webView = null
    }

    @Composable
    private fun CallUi() {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF14131A)),
        ) {
            if (callState != State.SIMULATED) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { buildWebView().also { webView = it } },
                    onRelease = { view -> runCatching { view.destroy() } },
                )
            }
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Spacer(Modifier.height(20.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        statusText,
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                    errorText?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = Color(0xFFFFD5D5), fontSize = 14.sp, textAlign = TextAlign.Center)
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        Control(stringResource(R.string.call_mute), muted, Color(0xFF8E6BD1)) { toggleMute() }
                        Control(stringResource(R.string.call_speaker), speakerOn, Color(0xFF4C6FD6)) { toggleSpeaker() }
                        Control(stringResource(R.string.call_video), videoOn, Color(0xFF2FA37C)) { toggleVideo() }
                    }
                    Spacer(Modifier.height(22.dp))
                    Box(
                        modifier = Modifier
                            .size(84.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFD2505B))
                            .clickable { hangUp() }
                            .semantics { contentDescription = "End call" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(stringResource(R.string.call_end), color = Color.White, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(28.dp))
                }
            }
        }
    }

    @Composable
    private fun Control(label: String, active: Boolean, color: Color, onClick: () -> Unit) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(if (active) color else Color(0xFF3A3846))
                    .clickable(onClick = onClick)
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                Text(label.take(1), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(6.dp))
            Text(label, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
        }
    }

    enum class State { CONNECTING, CONNECTED, RECONNECTING, SIMULATED, ENDED }

    companion object {
        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_MEDIA = "media"
        private const val ASSET_ORIGIN = "https://appassets.androidplatform.net"

        fun answeringIntent(context: Context, callId: String, media: String): Intent =
            Intent(context, ParentCallActivity::class.java).apply {
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_MEDIA, media)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}
