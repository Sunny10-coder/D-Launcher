package com.mylittleworld.child.service

import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.mylittleworld.child.ChildApp
import com.mylittleworld.child.core.DeviceActions
import com.mylittleworld.child.location.LocationReporter
import com.mylittleworld.child.ui.CallActivity
import com.mylittleworld.shared.model.CommandEnvelope
import com.mylittleworld.shared.model.StatusReport
import com.mylittleworld.shared.net.ApiException
import com.mylittleworld.shared.net.ApiErrorKind
import com.mylittleworld.shared.util.AppLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

/**
 * Long-lived foreground service that keeps the child phone reachable.
 *
 * Uses authenticated HTTPS polling against the family service (works without
 * Google Play services and without FCM credentials). The poll interval backs off
 * when the network or backend is unavailable, so an offline phone costs almost
 * no battery.
 */
class FamilyConnectService : LifecycleService() {

    private val container get() = (application as ChildApp).container
    private val location by lazy { LocationReporter(this) }
    private var loopJob: Job? = null
    private var commandCursor: String? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        startInForeground()
        commandCursor = runCatching { kotlinx.coroutines.runBlocking { container.settings.commandCursor() } }.getOrNull()
        loopJob = lifecycleScope.launch { pollLoop() }
        lifecycleScope.launch { periodicStatus() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> stopSelfSafely()
            ACTION_REFRESH -> lifecycleScope.launch { syncOnce() }
            ACTION_LOCATE -> lifecycleScope.launch { reportLocationNow() }
        }
        return START_STICKY
    }

    private fun startInForeground() {
        val parent = runCatching {
            kotlinx.coroutines.runBlocking { container.secure.parentName }
        }.getOrNull() ?: "Daddy"
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        // Android 14 throws if the location type is passed without the runtime
        // permission; only advertise it when it is actually usable.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && location.hasForegroundPermission()) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        val notification = Notifications.serviceNotification(this, parent)
        try {
            ServiceCompat.startForeground(this, Notifications.ID_SERVICE, notification, type)
        } catch (e: Exception) {
            AppLog.w("FamilyService", "Foreground start refused, retrying without location type", e)
            runCatching {
                ServiceCompat.startForeground(
                    this,
                    Notifications.ID_SERVICE,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            }
        }
    }

    private suspend fun pollLoop() {
        var backoff = 15_000L
        while (lifecycleScope.isActive) {
            val paired = runCatching { container.settings.paired.first() }.getOrDefault(false)
            if (!paired) {
                delay(60_000)
                continue
            }
            try {
                syncOnce()
                backoff = 15_000L
                delay(POLL_INTERVAL_MS)
            } catch (e: ApiException) {
                val retryable = e.kind in setOf(
                    ApiErrorKind.NETWORK, ApiErrorKind.TIMEOUT, ApiErrorKind.SERVER,
                    ApiErrorKind.RATE_LIMITED,
                )
                AppLog.w("FamilyService", "Poll failed (${e.kind})")
                delay(if (retryable) backoff else 120_000L)
                backoff = (backoff * 2).coerceAtMost(300_000L)
            } catch (e: Exception) {
                AppLog.w("FamilyService", "Unexpected poll error", e)
                delay(60_000)
            }
        }
    }

    private suspend fun syncOnce() {
        val api = container.childApi()
        val snapshot = api.snapshot()
        if (snapshot.config != container.config.value) {
            container.settings.saveConfig(snapshot.config)
        }
        // Always request unacknowledged commands. A timestamp cursor could skip a
        // second command created in the same millisecond, so acknowledgement is
        // the source of truth and the cursor is advisory only.
        val response = api.pollCommands(null)
        for (command in response.commands) {
            val handled = handleCommand(command)
            runCatching { api.acknowledge(command.id, if (handled) "ok" else "ignored") }
            commandCursor = command.createdAt
            runCatching { container.settings.saveCommandCursor(command.createdAt) }
        }
        if (response.commands.isNotEmpty()) Notifications.cancelIncomingCall(this)
    }

    private suspend fun handleCommand(command: CommandEnvelope): Boolean {
        val payload = runCatching { Json.parseToJsonElement(command.payloadJson).jsonObject }.getOrNull()
        val parentName = container.secure.parentName ?: container.config.value.parentName
        return when (command.type) {
            "CALL" -> {
                val callId = payload?.get("callId")?.jsonPrimitive?.content ?: return false
                val media = payload["media"]?.jsonPrimitive?.content ?: "audio"
                val config = container.settings.currentConfig()
                // Acceptance is a device-local decision (adult notice accepted on
                // this phone), never something a remote toggle can grant.
                val accepted = container.settings.autoAnswerAccepted.first()
                val autoAnswer = config.autoAnswerVoice && accepted &&
                    media == "audio" && canAutoAnswer()
                showIncomingCall(callId, media, parentName, autoAnswer)
                true
            }
            "LOCATE" -> {
                reportLocationNow()
                true
            }
            "FIND_PHONE" -> {
                DeviceActions.ringLoudly(this)
                Notifications.showFindPhone(this, parentName)
                true
            }
            "REFRESH_CONFIG" -> {
                container.refreshFromServer()
                true
            }
            "STOP_RINGING" -> {
                Notifications.cancelIncomingCall(this)
                true
            }
            else -> {
                AppLog.w("FamilyService", "Unknown command type from server")
                false
            }
        }
    }

    /**
     * Auto-answer is only attempted when the screen is on and the device is
     * interactive; Android blocks background activity launches otherwise and we
     * fall back to the normal incoming-call notification.
     */
    private fun canAutoAnswer(): Boolean {
        val power = getSystemService(POWER_SERVICE) as? PowerManager ?: return false
        return power.isInteractive && !power.isDeviceIdleMode
    }

    private fun showIncomingCall(callId: String, media: String, parentName: String, autoAnswer: Boolean) {
        Notifications.showIncomingCall(this, callId, media, parentName)
        runCatching {
            startActivity(
                CallActivity.incomingIntent(this, callId, media, parentName, autoAnswer)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        }.onFailure {
            // Android 10+ may refuse this; the notification's Answer action is the
            // supported path and remains visible either way.
            AppLog.w("FamilyService", "Incoming call activity could not be started")
        }
    }

    suspend fun reportLocationNow() {
        if (!location.hasForegroundPermission()) {
            AppLog.w("FamilyService", "Location permission missing, not reporting a fix")
            return
        }
        if (!DeviceActions.locationEnabled(this)) {
            runCatching {
                container.childApi().reportEvent(
                    com.mylittleworld.shared.model.EventReport(type = "location_disabled"),
                )
            }
            return
        }
        val sample = location.currentLocation() ?: return
        runCatching { container.childApi().reportLocation(sample) }
            .onFailure { AppLog.w("FamilyService", "Location upload failed", it) }
    }

    private suspend fun periodicStatus() {
        while (lifecycleScope.isActive) {
            if (runCatching { container.settings.paired.first() }.getOrDefault(false)) {
                runCatching { reportStatus() }
                    .onFailure { AppLog.w("FamilyService", "Status report failed", it) }
            }
            delay(STATUS_INTERVAL_MS)
        }
    }

    private suspend fun reportStatus() {
        val webView = runCatching { android.webkit.WebView.getCurrentWebViewPackage()?.versionName }
            .getOrNull() ?: "unavailable"
        val battery = DeviceActions.batteryPercent(this)
        container.childApi().reportStatus(
            StatusReport(
                batteryPercent = battery.coerceAtLeast(0),
                charging = DeviceActions.isCharging(this),
                appVersion = container.appVersion(),
                webViewVersion = webView,
                androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                deviceModel = Build.MODEL ?: "unknown",
                autoAnswerSupported = canAutoAnswer() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O,
            ),
        )
    }

    private fun stopSelfSafely() {
        loopJob?.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        loopJob?.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep the family connection alive when the task is swiped away.
        super.onTaskRemoved(rootIntent)
    }

    companion object {
        const val ACTION_START = "com.mylittleworld.child.START"
        const val ACTION_STOP = "com.mylittleworld.child.STOP"
        const val ACTION_REFRESH = "com.mylittleworld.child.REFRESH"
        const val ACTION_LOCATE = "com.mylittleworld.child.LOCATE"
        private const val POLL_INTERVAL_MS = 15_000L
        private const val STATUS_INTERVAL_MS = 90_000L

        fun start(context: android.content.Context) {
            Notifications.ensureChannels(context)
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                AppLog.w("FamilyService", "Notifications disabled; calls fall back to in-app UI")
            }
            runCatching {
                androidx.core.content.ContextCompat.startForegroundService(
                    context,
                    Intent(context, FamilyConnectService::class.java).setAction(ACTION_START),
                )
            }
        }

        fun locateNow(context: android.content.Context) {
            runCatching {
                androidx.core.content.ContextCompat.startForegroundService(
                    context,
                    Intent(context, FamilyConnectService::class.java).setAction(ACTION_LOCATE),
                )
            }
        }
    }
}
