package com.mylittleworld.parent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.mylittleworld.parent.ParentApp
import com.mylittleworld.parent.R
import com.mylittleworld.parent.ui.ParentCallActivity
import com.mylittleworld.shared.net.ApiErrorKind
import com.mylittleworld.shared.net.ApiException
import com.mylittleworld.shared.util.AppLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the parent reachable when the child presses "Call Daddy".
 *
 * Polls the authenticated family service for ringing calls; no FCM credential is
 * required. When a call is waiting the parent gets a high-priority notification
 * with a full-screen intent and an Answer action that joins the same WebRTC room.
 */
class ParentCallService : LifecycleService() {

    private val container get() = (application as ParentApp).container
    private var lastNotified: String? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannels(this)
        ServiceCompat.startForeground(
            this,
            ID_SERVICE,
            serviceNotification(this),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
        lifecycleScope.launch { pollLoop() }
    }

    private suspend fun pollLoop() {
        var backoff = 15_000L
        while (lifecycleScope.isActive) {
            if (!container.signedIn.value) {
                delay(60_000)
                continue
            }
            try {
                val calls = container.api().pendingCalls().filter { it.status == "ringing" }
                val first = calls.firstOrNull()
                if (first != null && first.callId != lastNotified) {
                    lastNotified = first.callId
                    notifyIncoming(first.callId, first.media)
                    container.prefs.lastDeviceId?.let { }
                } else if (first == null) {
                    lastNotified = null
                    NotificationManagerCompat.from(this).cancel(ID_INCOMING)
                }
                backoff = 15_000L
                delay(12_000)
            } catch (e: ApiException) {
                AppLog.w("ParentCallService", "Poll failed: ${e.kind}")
                if (e.kind == ApiErrorKind.UNAUTHORIZED) {
                    delay(120_000)
                } else {
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(180_000L)
                }
            } catch (e: Exception) {
                AppLog.w("ParentCallService", "Unexpected poll error", e)
                delay(30_000)
            }
        }
    }

    private fun notifyIncoming(callId: String, media: String) {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        val answer = PendingIntent.getActivity(
            this,
            callId.hashCode(),
            ParentCallActivity.answeringIntent(this, callId, media),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val decline = PendingIntent.getBroadcast(
            this,
            callId.hashCode() + 1,
            Intent(this, DeclineReceiver::class.java).putExtra("call_id", callId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val name = container.secure.parentName ?: "your child"
        val notification = NotificationCompat.Builder(this, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle(getString(R.string.notif_incoming_title, "Little Star"))
            .setContentText(getString(R.string.notif_incoming_body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setFullScreenIntent(answer, true)
            .setContentIntent(answer)
            .addAction(0, getString(R.string.call_answer), answer)
            .addAction(0, getString(R.string.call_end), decline)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(ID_INCOMING, notification) }
        @Suppress("UNUSED_VARIABLE")
        val ignore = name
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    class DeclineReceiver : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val callId = intent.getStringExtra("call_id") ?: return
            val app = context.applicationContext as? ParentApp ?: return
            val pending = goAsync()
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                runCatching { app.container.api().declineCall(callId) }
                NotificationManagerCompat.from(context).cancel(ID_INCOMING)
                pending.finish()
            }
        }
    }

    companion object {
        const val CHANNEL_SERVICE = "parent_service"
        const val CHANNEL_CALLS = "parent_calls"
        const val ID_SERVICE = 2001
        const val ID_INCOMING = 2002
        private const val ACTION_START = "com.mylittleworld.parent.START"
        private const val ACTION_STOP = "com.mylittleworld.parent.STOP"

        fun ensureChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_SERVICE,
                    context.getString(R.string.notif_channel_service),
                    NotificationManager.IMPORTANCE_MIN,
                ),
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_CALLS,
                    context.getString(R.string.notif_channel_calls),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    enableVibration(true)
                },
            )
        }

        private fun serviceNotification(context: Context): Notification {
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, com.mylittleworld.parent.ui.DashboardActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(context, CHANNEL_SERVICE)
                .setSmallIcon(R.drawable.ic_stat_shield)
                .setContentTitle(context.getString(R.string.notif_service_title))
                .setContentText(context.getString(R.string.notif_service_body))
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setContentIntent(open)
                .build()
        }

        fun start(context: Context) {
            ensureChannels(context)
            runCatching {
                androidx.core.content.ContextCompat.startForegroundService(
                    context,
                    Intent(context, ParentCallService::class.java).setAction(ACTION_START),
                )
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, ParentCallService::class.java))
            }
        }
    }
}
