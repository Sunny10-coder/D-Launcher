package com.mylittleworld.child.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.mylittleworld.child.R
import com.mylittleworld.child.ui.CallActivity

object Notifications {

    const val CHANNEL_SERVICE = "family_service"
    const val CHANNEL_CALLS = "family_calls"
    const val ID_SERVICE = 1001
    const val ID_INCOMING_CALL = 1002
    const val ID_RING = 1003

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                context.getString(R.string.notif_channel_service),
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = context.getString(R.string.notif_service_body, "Daddy")
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CALLS,
                context.getString(R.string.notif_channel_calls),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.notif_incoming_body)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableVibration(true)
            },
        )
    }

    fun serviceNotification(context: Context, parentName: String): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, com.mylittleworld.child.ui.LauncherActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_heart)
            .setContentTitle(context.getString(R.string.notif_service_title))
            .setContentText(context.getString(R.string.notif_service_body, parentName))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /**
     * Incoming call notification with a full-screen intent. Android may present it
     * as a heads-up banner instead when full-screen intents are restricted; the
     * Answer action always remains available.
     */
    fun incomingCall(context: Context, callId: String, media: String, parentName: String): Notification {
        val answer = PendingIntent.getActivity(
            context,
            callId.hashCode(),
            CallActivity.incomingIntent(context, callId, media, parentName, autoAnswer = false),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val fullScreen = PendingIntent.getActivity(
            context,
            callId.hashCode() + 7,
            CallActivity.incomingIntent(context, callId, media, parentName, autoAnswer = false),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val decline = PendingIntent.getActivity(
            context,
            callId.hashCode() + 3,
            Intent(context, CallActivity::class.java)
                .setAction(CallActivity.ACTION_DECLINE_FROM_NOTIFICATION)
                .putExtra(CallActivity.EXTRA_CALL_ID, callId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_stat_heart)
            .setContentTitle(context.getString(R.string.notif_incoming_title, parentName))
            .setContentText(context.getString(R.string.notif_incoming_body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(answer)
            .addAction(0, context.getString(R.string.call_answer), answer)
            .addAction(0, context.getString(R.string.call_end), decline)
            .build()
    }

    fun showIncomingCall(context: Context, callId: String, media: String, parentName: String) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        ensureChannels(context)
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(ID_INCOMING_CALL, incomingCall(context, callId, media, parentName))
        }
    }

    fun cancelIncomingCall(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(ID_INCOMING_CALL) }
    }

    fun showFindPhone(context: Context, parentName: String) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, com.mylittleworld.child.ui.LauncherActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_stat_heart)
            .setContentTitle(context.getString(R.string.notif_ring_title, parentName))
            .setContentText(context.getString(R.string.notif_ring_body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ID_RING, notification) }
    }
}
