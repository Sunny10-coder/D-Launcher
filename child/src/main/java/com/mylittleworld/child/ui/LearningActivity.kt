package com.mylittleworld.child.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import com.mylittleworld.child.ChildApp
import com.mylittleworld.child.R
import com.mylittleworld.child.core.DeviceActions
import com.mylittleworld.child.ui.components.ArrowBackIcon
import com.mylittleworld.child.ui.components.TileIcon
import com.mylittleworld.child.ui.components.TileKind
import com.mylittleworld.child.ui.theme.MyLittleWorldTheme
import com.mylittleworld.child.ui.theme.Palette
import com.mylittleworld.shared.logic.UrlPolicy
import com.mylittleworld.shared.util.AppLog

/**
 * Full-screen, allow-listed learning surface.
 *
 * The WebView has no JavaScript bridge at all: the learning website cannot issue
 * device commands. Navigation is validated for every request, including
 * sub-frames, redirects and service-worker fetches. Failures degrade to native
 * fallback screens with a single obvious way forward, so the child is never stuck.
 */
class LearningActivity : ComponentActivity() {

    private val container get() = (application as ChildApp).container
    private var webView: WebView? = null
    private var allowCleartextLocalDev = false

    private var approvedUrl: String? = null
    private var approvedHosts: List<String> = emptyList()
    private var generation by mutableIntStateOf(0)
    private var state by mutableStateOf(ScreenState.Loading)
    private var title: String = ""
    private var mode: String = MODE_LEARNING

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterImmersive()
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_LEARNING
        title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.tile_learning)
        allowCleartextLocalDev = container.isLocalDevMode

        setContent {
            MyLittleWorldTheme {
                val config by container.config.collectAsStateWithLifecycle()
                val raw = when (mode) {
                    MODE_PLAY -> config.playUrl
                    MODE_YOUTUBE_INFO -> ""
                    else -> config.learningUrl
                }
                val sanitised = remember(raw, allowCleartextLocalDev) {
                    UrlPolicy.sanitiseApprovedUrl(raw, allowCleartextLocalDev)
                }
                LaunchedEffect(sanitised) {
                    approvedUrl = sanitised
                    approvedHosts = UrlPolicy.approvedHostsFor(sanitised)
                    if (mode == MODE_YOUTUBE_INFO) {
                        state = ScreenState.YouTubeHelp
                    } else if (sanitised == null) {
                        state = ScreenState.NotConfigured
                    } else {
                        state = ScreenState.Loading
                        generation += 1
                    }
                }
                LearningScreen(
                    state = state,
                    title = title,
                    mode = mode,
                    approvedUrl = approvedUrl,
                    generation = generation,
                    onWebViewReady = { view ->
                        webView = view
                        view.loadUrl(approvedUrl ?: "about:blank")
                    },
                    onRetry = {
                        if (approvedUrl != null) {
                            state = ScreenState.Loading
                            webView?.reload()
                        }
                    },
                    onHome = { finish() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        enterImmersive()
        webView?.onResume()
        webView?.resumeTimers()
    }

    override fun onPause() {
        webView?.onPause()
        // Pausing timers keeps audio/narration from continuing after the child
        // leaves for a call or the camera; the page state itself is preserved.
        webView?.pauseTimers()
        super.onPause()
    }

    override fun onDestroy() {
        runCatching {
            webView?.let { view ->
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
            }
        }
        webView = null
        super.onDestroy()
    }

    private fun enterImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun goBackOrHome() {
        val view = webView
        if (view != null && view.canGoBack()) {
            view.goBack()
        } else {
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val view = webView
        if (view != null && view.canGoBack()) {
            view.goBack()
        } else {
            super.onBackPressed()
        }
    }

    /* ------------------------- WebView configuration ------------------------- */

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(): WebView {
        val view = WebView(this)
        view.setBackgroundColor(android.graphics.Color.parseColor("#FFF7EC"))
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(false)
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            saveFormData = false
            userAgentString = "$userAgentString MyLittleWorldChild/${container.appVersion()}"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                isAlgorithmicDarkeningAllowed = false
            }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)

        view.webViewClient = object : android.webkit.WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val target = request.url.toString()
                return if (UrlPolicy.isAllowed(target, approvedHosts, allowCleartextLocalDev = allowCleartextLocalDev)) {
                    false
                } else {
                    AppLog.w("LearningActivity", "Blocked navigation to an unapproved destination")
                    state = ScreenState.Blocked
                    true
                }
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                val target = request.url.toString()
                if (target.startsWith("data:") || target.startsWith("blob:")) return null
                return if (UrlPolicy.isAllowed(target, approvedHosts, allowCleartextLocalDev = allowCleartextLocalDev)) {
                    null
                } else {
                    AppLog.d("LearningActivity", "Blocked subresource")
                    WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), null)
                }
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (state == ScreenState.Offline) state = ScreenState.Loading
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                if (state == ScreenState.Loading || state == ScreenState.Blocked) {
                    state = ScreenState.Ready
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (!request.isForMainFrame) return
                AppLog.w("LearningActivity", "Main frame error ${error.errorCode}")
                state = ScreenState.Offline
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse,
            ) {
                if (!request.isForMainFrame) return
                if (errorResponse.statusCode >= 400) {
                    AppLog.w("LearningActivity", "HTTP ${errorResponse.statusCode} on main frame")
                    state = ScreenState.Offline
                }
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail?,
            ): Boolean {
                // Recover rather than kill the activity: drop the dead WebView and
                // rebuild it. Session state is reloaded from the site itself.
                AppLog.e("LearningActivity", "WebView renderer died, recreating")
                runCatching {
                    (view.parent as? ViewGroup)?.removeView(view)
                    view.destroy()
                }
                webView = null
                state = ScreenState.Loading
                generation += 1
                return true
            }
        }

        view.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                // The learning site never gets camera, microphone or any other
                // device capability through the WebView.
                request.deny()
            }

            override fun onJsAlert(
                view: WebView,
                url: String?,
                message: String?,
                result: JsResult,
            ): Boolean {
                result.confirm()
                return true
            }
        }

        view.setDownloadListener { _, _, _, _, _ ->
            // Downloads are not part of the learning experience and are dropped.
            AppLog.w("LearningActivity", "Download attempt ignored")
        }

        registerServiceWorkerPolicy()
        return view
    }

    private fun registerServiceWorkerPolicy() {
        runCatching {
            ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(
                object : ServiceWorkerClientCompat() {
                    override fun shouldInterceptRequest(
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        val target = request.url.toString()
                        return if (UrlPolicy.isAllowed(target, approvedHosts, allowCleartextLocalDev = allowCleartextLocalDev)) {
                            null
                        } else {
                            WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), null)
                        }
                    }
                },
            )
        }.onFailure { AppLog.w("LearningActivity", "Service worker policy unavailable", it) }
    }

    /* -------------------------------- UI -------------------------------- */

    enum class ScreenState { Loading, Ready, Offline, Blocked, NotConfigured, YouTubeHelp }

    @Composable
    private fun LearningScreen(
        state: ScreenState,
        title: String,
        mode: String,
        approvedUrl: String?,
        generation: Int,
        onWebViewReady: (WebView) -> Unit,
        onRetry: () -> Unit,
        onHome: () -> Unit,
    ) {
        val context = LocalContext.current
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Palette.Cream),
        ) {
            if (state != ScreenState.NotConfigured && state != ScreenState.YouTubeHelp && approvedUrl != null) {
                androidx.compose.runtime.key(generation) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { buildWebView().also(onWebViewReady) },
                    )
                }
            }

            AnimatedVisibility(
                visible = state == ScreenState.Loading,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Palette.Cream.copy(alpha = 0.92f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Palette.LearningEnd, strokeWidth = 5.dp)
                        Spacer(Modifier.height(16.dp))
                        Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
                    }
                }
            }

            when (state) {
                ScreenState.Offline -> FallbackPanel(
                    icon = TileKind.LEARNING,
                    heading = stringResource(R.string.offline_title),
                    body = stringResource(R.string.offline_body),
                    primaryLabel = stringResource(R.string.offline_retry),
                    onPrimary = onRetry,
                    secondaryLabel = stringResource(R.string.learning_go_back),
                    onSecondary = onHome,
                )
                ScreenState.Blocked -> FallbackPanel(
                    icon = TileKind.LEARNING,
                    heading = stringResource(R.string.learning_blocked_title),
                    body = stringResource(R.string.learning_blocked_body),
                    primaryLabel = stringResource(R.string.learning_go_back),
                    onPrimary = onHome,
                    secondaryLabel = null,
                    onSecondary = null,
                )
                ScreenState.NotConfigured -> DefaultLearningPanel(onHome = onHome)
                ScreenState.YouTubeHelp -> YouTubeHelpPanel(onHome = onHome)
                else -> Unit
            }

            // Always-available way home, even if the site grabs every gesture.
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SmallCircleButton(
                    description = stringResource(R.string.learning_go_back),
                    onClick = { goBackOrHome() },
                ) {
                    ArrowBackIcon(modifier = Modifier.size(22.dp), color = Palette.Ink)
                }
                SmallCircleButton(
                    description = stringResource(R.string.a11y_home),
                    onClick = { finish() },
                ) {
                    androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
                        val w = size.width
                        val h = size.height
                        val path = androidx.compose.ui.graphics.Path().apply {
                            moveTo(w * 0.1f, h * 0.5f)
                            lineTo(w * 0.5f, h * 0.12f)
                            lineTo(w * 0.9f, h * 0.5f)
                            lineTo(w * 0.9f, h * 0.9f)
                            lineTo(w * 0.1f, h * 0.9f)
                            close()
                        }
                        drawPath(path, Palette.Ink, style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.12f))
                    }
                }
            }
        }
    }

    @Composable
    private fun SmallCircleButton(
        description: String,
        onClick: () -> Unit,
        content: @Composable () -> Unit,
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.86f))
                .clickable(onClick = onClick)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) { content() }
    }

    @Composable
    private fun FallbackPanel(
        icon: TileKind,
        heading: String,
        body: String,
        primaryLabel: String,
        onPrimary: () -> Unit,
        secondaryLabel: String?,
        onSecondary: (() -> Unit)?,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Palette.Cream),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(110.dp)
                        .clip(RoundedCornerShape(34.dp))
                        .background(Palette.CreamDeep),
                    contentAlignment = Alignment.Center,
                ) {
                    TileIcon(icon, color = Palette.LearningEnd, modifier = Modifier.size(58.dp))
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    heading,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Palette.Ink,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                Text(body, fontSize = 16.sp, color = Palette.InkSoft, textAlign = TextAlign.Center)
                Spacer(Modifier.height(26.dp))
                Button(
                    onClick = onPrimary,
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.LearningEnd),
                    modifier = Modifier.height(56.dp),
                ) {
                    Text(primaryLabel, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
                if (secondaryLabel != null && onSecondary != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        secondaryLabel,
                        modifier = Modifier
                            .clickable(onClick = onSecondary)
                            .padding(12.dp),
                        fontSize = 15.sp,
                        color = Palette.InkSoft,
                    )
                }
            }
        }
    }

    @Composable
    private fun DefaultLearningPanel(onHome: () -> Unit) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Palette.background("sunrise")),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("A", "B", "C").forEachIndexed { index, letter ->
                        Box(
                            modifier = Modifier
                                .size(84.dp)
                                .clip(RoundedCornerShape(26.dp))
                                .background(
                                    listOf(
                                        Color(0xFFFFB37A), Color(0xFF7FD8B8), Color(0xFFC5A8F0),
                                    )[index],
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(letter, fontSize = 42.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
                Spacer(Modifier.height(28.dp))
                Text(
                    stringResource(R.string.learning_not_configured_title),
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = Palette.Ink,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.learning_not_configured_body),
                    fontSize = 16.sp,
                    color = Palette.InkSoft,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(26.dp))
                Button(
                    onClick = onHome,
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.CallEnd),
                    modifier = Modifier.height(56.dp),
                ) {
                    Text(stringResource(R.string.learning_go_back), fontSize = 17.sp)
                }
            }
        }
    }

    @Composable
    private fun YouTubeHelpPanel(onHome: () -> Unit) {
        val context = LocalContext.current
        FallbackPanel(
            icon = TileKind.VIDEO,
            heading = stringResource(R.string.youtube_missing_title),
            body = stringResource(R.string.youtube_missing_body),
            primaryLabel = stringResource(R.string.youtube_open_store),
            onPrimary = {
                if (!DeviceActions.openPlayStore(context) && !DeviceActions.playServicesAvailable(context)) {
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.youtube_missing_body),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            },
            secondaryLabel = stringResource(R.string.a11y_home),
            onSecondary = onHome,
        )
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_TITLE = "title"
        const val MODE_LEARNING = "learning"
        const val MODE_PLAY = "play"
        const val MODE_YOUTUBE_INFO = "youtube_info"
    }
}
