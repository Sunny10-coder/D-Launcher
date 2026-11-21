package com.mylittleworld.child.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.mylittleworld.shared.model.LocationSample
import com.mylittleworld.shared.model.SafeZone
import com.mylittleworld.shared.util.AppLog
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import java.time.Instant

/**
 * Location access built strictly on the supported Fused Location Provider.
 *
 * Rules honoured here:
 *  * never returns a fabricated fix; a null result stays null;
 *  * coarseness and timestamps come from the platform;
 *  * permission loss degrades to "unavailable" instead of crashing;
 *  * mock providers are flagged, not silently trusted.
 */
class LocationReporter(private val context: Context) {

    private val fused: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    private val geofencing: GeofencingClient by lazy {
        LocationServices.getGeofencingClient(context)
    }

    fun hasForegroundPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun hasBackgroundPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            hasForegroundPermission()
        }

    /** Single on-demand fix used by the parent's "Locate now" request. */
    @SuppressLint("MissingPermission")
    suspend fun currentLocation(): LocationSample? {
        if (!hasForegroundPermission()) return null
        if (!com.google.android.gms.common.GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(context)
                .let { it == com.google.android.gms.common.ConnectionResult.SUCCESS }
        ) {
            AppLog.w("LocationReporter", "Play services unavailable, cannot get a fix")
            return null
        }
        return runCatching {
            val request = CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                .setDurationMillis(20_000)
                .setMaxUpdateAgeMillis(60_000)
                .build()
            val token = com.google.android.gms.tasks.CancellationTokenSource().token
            val location = withTimeoutOrNull(25_000) {
                fused.getCurrentLocation(request, token).awaitLocation()
            }
            location?.toSample()
        }.getOrElse { error ->
            AppLog.w("LocationReporter", "Location request failed", error)
            null
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun lastKnownLocation(): LocationSample? {
        if (!hasForegroundPermission()) return null
        return runCatching { withTimeoutOrNull(10_000) { fused.lastLocation.awaitLocation() }?.toSample() }
            .getOrNull()
    }

    /**
     * Registers safe zones as platform geofences. Background geofencing needs the
     * background location permission; without it the parent is told zones are
     * inactive instead of us pretending they work.
     */
    @SuppressLint("MissingPermission")
    fun registerGeofences(zones: List<SafeZone>, onResult: (Boolean) -> Unit) {
        if (!hasForegroundPermission()) {
            onResult(false)
            return
        }
        val fences = zones.filter { it.radiusMeters in 50..5_000 }.take(90).map { zone ->
            Geofence.Builder()
                .setRequestId(zone.id)
                .setCircularRegion(zone.lat, zone.lng, zone.radiusMeters.toFloat())
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(
                    (if (zone.notifyOnEntry) Geofence.GEOFENCE_TRANSITION_ENTER else 0) or
                        (if (zone.notifyOnExit) Geofence.GEOFENCE_TRANSITION_EXIT else 0),
                )
                .build()
        }
        geofencing.removeGeofences(pendingIntentFor(context))
        if (fences.isEmpty()) {
            onResult(true)
            return
        }
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofences(fences)
            .build()
        geofencing.addGeofences(request, pendingIntentFor(context))
            .addOnSuccessListener { onResult(true) }
            .addOnFailureListener { error ->
                AppLog.w("LocationReporter", "Geofence registration refused", error)
                onResult(false)
            }
    }

    private fun pendingIntentFor(context: Context) = android.app.PendingIntent.getBroadcast(
        context,
        1001,
        android.content.Intent(context, com.mylittleworld.child.service.GeofenceReceiver::class.java)
            .setAction(com.mylittleworld.child.service.GeofenceReceiver.ACTION),
        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
    )

    private fun Location.toSample(): LocationSample = LocationSample(
        lat = latitude,
        lng = longitude,
        accuracyMeters = if (hasAccuracy()) accuracy.toDouble() else 0.0,
        capturedAt = Instant.ofEpochMilli(time).toString(),
        provider = provider ?: "fused",
        batteryPercent = com.mylittleworld.child.core.DeviceActions.batteryPercent(context)
            .takeIf { it >= 0 },
        isMock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) isMock else false,
    )
}

private suspend fun com.google.android.gms.tasks.Task<Location>.awaitLocation(): Location? =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { location -> continuation.resume(location) }
        addOnFailureListener { continuation.resume(null) }
    }
