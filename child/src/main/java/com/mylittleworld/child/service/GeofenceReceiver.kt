package com.mylittleworld.child.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.mylittleworld.shared.model.EventReport
import com.mylittleworld.shared.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Reports safe-zone entry/exit transitions to the family service. */
class GeofenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            AppLog.w("GeofenceReceiver", "Geofence error ${event.errorCode}")
            return
        }
        val transition = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> "enter"
            Geofence.GEOFENCE_TRANSITION_EXIT -> "exit"
            else -> return
        }
        val zoneIds = event.triggeringGeofences?.map { it.requestId } ?: return
        val app = context.applicationContext as? com.mylittleworld.child.ChildApp ?: return
        val container = app.container
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                container.childApi().reportEvent(
                    EventReport(
                        type = "geofence",
                        payload = buildJsonObject {
                            put("zoneIds", zoneIds.joinToString(","))
                            put("transition", transition)
                        },
                    ),
                )
                if (container.settings.currentConfig().let { it.autoAnswerVoice }) {
                    AppLog.d("GeofenceReceiver", "Safe zone transition recorded")
                }
            }.onFailure { AppLog.w("GeofenceReceiver", "Unable to report geofence event", it) }
        }
    }

    companion object {
        const val ACTION = "com.mylittleworld.child.GEOFENCE"
    }
}
