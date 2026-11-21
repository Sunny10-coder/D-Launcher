package com.mylittleworld.child.core

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import com.mylittleworld.shared.util.AppLog

/**
 * Thin wrappers around platform capabilities. Every call is defensive: a missing
 * package, disabled component or restricted API must never crash the launcher.
 */
object DeviceActions {

    const val YOUTUBE_KIDS = "com.google.android.apps.youtube.kids"
    private const val YOUTUBE_KIDS_MARKET = "market://details?id=$YOUTUBE_KIDS"
    private const val YOUTUBE_KIDS_WEB = "https://play.google.com/store/apps/details?id=$YOUTUBE_KIDS"

    fun isInstalled(context: Context, packageName: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)

    fun launchPackage(context: Context, packageName: String): Boolean = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrElse { error ->
        AppLog.w("DeviceActions", "Unable to launch $packageName", error)
        false
    }

    fun openPlayStore(context: Context, packageName: String = YOUTUBE_KIDS): Boolean {
        val marketUri = "market://details?id=$packageName"
        val webUri = "https://play.google.com/store/apps/details?id=$packageName"
        return openUri(context, marketUri) || openUri(context, webUri) ||
            openUri(context, if (packageName == YOUTUBE_KIDS) YOUTUBE_KIDS_WEB else YOUTUBE_KIDS_MARKET)
    }

    fun openUri(context: Context, uri: String): Boolean = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrElse { e ->
        if (e !is ActivityNotFoundException) AppLog.w("DeviceActions", "URI failed", e)
        false
    }

    /** Launches the system camera application for a still photo, if one exists. */
    fun launchSystemCamera(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .let { intent ->
                    // Some OEM camera apps require a clip data grant even when no
                    // output URI is requested; nothing is shared implicitly.
                    intent
                },
        )
        true
    }.getOrElse { false }

    fun hasSystemCamera(context: Context): Boolean = runCatching {
        context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY)
    }.getOrDefault(false)

    fun batteryPercent(context: Context): Int {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
        return manager?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    }

    fun isCharging(context: Context): Boolean {
        val intent = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
            status == android.os.BatteryManager.BATTERY_STATUS_FULL
    }

    fun locationEnabled(context: Context): Boolean = runCatching {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
            ?: return false
        lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
            lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
    }.getOrDefault(false)

    fun webViewAvailable(context: Context): Boolean = runCatching {
        val provider = android.webkit.WebView.getCurrentWebViewPackage()
        provider != null
    }.getOrDefault(true)

    fun playServicesAvailable(context: Context): Boolean = runCatching {
        com.google.android.gms.common.GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == com.google.android.gms.common.ConnectionResult.SUCCESS
    }.getOrDefault(false)

    fun vibration(context: Context): Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }.getOrNull()

    /**
     * Find-phone: plays the alarm/ringtone stream at maximum volume for a short
     * burst. Only ever triggered by an authenticated parent command.
     */
    fun ringLoudly(context: Context, seconds: Int = 12) {
        runCatching {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val previousVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audio.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
            if (audio.ringerMode == AudioManager.RINGER_MODE_SILENT) {
                audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
            }
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val ringtone = RingtoneManager.getRingtone(context.applicationContext, uri)
            ringtone.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            ringtone.play()

            val vibrator = vibration(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0))
            }

            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                runCatching { ringtone.stop() }
                runCatching { vibrator?.cancel() }
                runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, previousVolume, 0) }
            }, seconds * 1000L)
        }.onFailure { AppLog.w("DeviceActions", "Find phone failed", it) }
    }

    fun notificationSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun locationSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun batteryOptimisationIntent(context: Context): Intent? = runCatching {
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }.getOrNull()

    fun toneConfirm() {
        runCatching {
            ToneGenerator(AudioManager.STREAM_MUSIC, 40).startTone(ToneGenerator.TONE_PROP_ACK, 120)
        }
    }
}
