package com.mylittleworld.child.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.mylittleworld.shared.util.AppLog

/**
 * Restarts the family connection after a reboot or an app update so the phone
 * keeps working as a launcher without a parent unlocking it first.
 *
 * Android 15+ restricts starting some foreground service types from
 * BOOT_COMPLETED; the service itself degrades gracefully when it may not run.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }
        AppLog.i("BootReceiver", "Restarting family service after $action")
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, FamilyConnectService::class.java)
                    .setAction(FamilyConnectService.ACTION_START),
            )
        }.onFailure { error ->
            // Expected on Android 15+ when the OS disallows the start; the user
            // simply opens the launcher and the service starts in the foreground.
            AppLog.w("BootReceiver", "Foreground service start deferred", error)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            AppLog.d("BootReceiver", "Background activity starts and background location are restricted by the OS")
        }
    }
}
