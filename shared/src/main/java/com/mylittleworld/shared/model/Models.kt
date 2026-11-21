package com.mylittleworld.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Configuration owned by the parent and mirrored onto the child device.
 * Every field has a safe default so a freshly paired device still renders a
 * complete launcher even if the server returns a partial document.
 */
@Serializable
data class DeviceConfig(
    val nickname: String = "Little Star",
    val parentName: String = "Daddy",
    val learningUrl: String = "",
    val playUrl: String = "",
    val theme: String = "sunrise",
    val enabledTiles: List<String> = listOf("learning", "call", "youtube", "camera", "play"),
    val autoAnswerVoice: Boolean = false,
    val autoAnswerNoticeAccepted: Boolean = false,
    val muteDuringQuietHours: Boolean = false,
    val scheduleStart: String = "",
    val scheduleEnd: String = "",
    val remoteCameraEnabled: Boolean = false,
    val contactPhone: String = "",
    val updatedAt: String = "",
) {
    fun tileEnabled(id: String): Boolean = enabledTiles.contains(id)
}

@Serializable
data class ParentProfile(
    val id: String,
    val name: String,
    val email: String,
)

@Serializable
data class AuthSession(
    val token: String,
    val expiresAt: String,
    val parent: ParentProfile,
)

@Serializable
data class PairedDevice(
    val deviceId: String,
    val name: String,
    val online: Boolean = false,
    val lastSeen: String? = null,
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    val appVersion: String? = null,
    val pairedAt: String? = null,
)

@Serializable
data class LocationSample(
    val lat: Double,
    val lng: Double,
    val accuracyMeters: Double,
    val capturedAt: String,
    val provider: String = "fused",
    val batteryPercent: Int? = null,
    val isMock: Boolean = false,
)

@Serializable
data class SafeZone(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val radiusMeters: Int,
    val notifyOnEntry: Boolean = true,
    val notifyOnExit: Boolean = true,
    val createdAt: String? = null,
)

@Serializable
data class DeviceSnapshot(
    val deviceId: String,
    val name: String,
    val config: DeviceConfig = DeviceConfig(),
    val online: Boolean = false,
    val lastSeen: String? = null,
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    val appVersion: String? = null,
    val webViewVersion: String? = null,
    val androidVersion: String? = null,
    val deviceModel: String? = null,
    val lastLocation: LocationSample? = null,
    val safeZones: List<SafeZone> = emptyList(),
    val autoAnswerSupported: Boolean = false,
)

@Serializable
data class CommandEnvelope(
    val id: String,
    val type: String,
    val payloadJson: String = "{}",
    val createdAt: String,
)

@Serializable
data class CommandsResponse(
    val commands: List<CommandEnvelope> = emptyList(),
)

@Serializable
data class PairingCode(
    val code: String,
    val expiresAt: String,
)

@Serializable
data class ClaimRequest(
    val code: String,
    val deviceName: String,
    val platform: String,
    val appVersion: String,
)

@Serializable
data class ClaimResponse(
    val deviceId: String,
    val deviceToken: String,
    val parentName: String,
    val config: DeviceConfig,
)

@Serializable
data class StatusReport(
    val batteryPercent: Int,
    val charging: Boolean,
    val appVersion: String,
    val webViewVersion: String,
    val androidVersion: String,
    val deviceModel: String,
    val autoAnswerSupported: Boolean,
)

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class RegisterRequest(val email: String, val password: String, val name: String)

@Serializable
data class CallRequest(val media: String)

@Serializable
data class CallTicket(
    val callId: String,
    val room: String,
    val websocketUrl: String,
    val token: String = "",
    val role: String = "",
    val parentName: String = "",
    val media: String = "audio",
    val expiresAt: String = "",
    val mode: String = "live",
    val iceServers: List<IceServer> = emptyList(),
)

@Serializable
data class IceServer(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
)

@Serializable
data class PendingCall(
    val callId: String,
    val media: String,
    val status: String,
    val createdAt: String,
)

@Serializable
data class OutgoingCall(val callId: String, val media: String)

@Serializable
data class EventReport(
    val type: String,
    @SerialName("payload") val payload: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
)

@Serializable
data class ApiErrorBody(val error: String = "", val message: String = "")
