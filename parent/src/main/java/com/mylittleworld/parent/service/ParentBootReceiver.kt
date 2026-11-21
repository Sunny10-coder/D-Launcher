package com.mylittleworld.parent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mylittleworld.parent.ParentApp
import com.mylittleworld.shared.util.AppLog

class ParentBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val app = context.applicationContext as? ParentApp ?: return
        if (!app.container.signedIn.value) return
        AppLog.i("ParentBootReceiver", "Restarting call listening")
        ParentCallService.start(context)
    }
}
