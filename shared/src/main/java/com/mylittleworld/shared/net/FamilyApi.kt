package com.mylittleworld.shared.net

import com.mylittleworld.shared.model.AuthSession
import com.mylittleworld.shared.model.CallRequest
import com.mylittleworld.shared.model.CallTicket
import com.mylittleworld.shared.model.ClaimRequest
import com.mylittleworld.shared.model.ClaimResponse
import com.mylittleworld.shared.model.CommandsResponse
import com.mylittleworld.shared.model.DeviceConfig
import com.mylittleworld.shared.model.DeviceSnapshot
import com.mylittleworld.shared.model.EventReport
import com.mylittleworld.shared.model.LocationSample
import com.mylittleworld.shared.model.LoginRequest
import com.mylittleworld.shared.model.OutgoingCall
import com.mylittleworld.shared.model.PendingCall
import com.mylittleworld.shared.model.PairedDevice
import com.mylittleworld.shared.model.PairingCode
import com.mylittleworld.shared.model.RegisterRequest
import com.mylittleworld.shared.model.SafeZone
import com.mylittleworld.shared.model.StatusReport
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

internal fun encodeUrl(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

/** Child-device endpoints, authorised by the long-lived device token. */
class ChildApi(private val api: ApiClient) {

    suspend fun claim(request: ClaimRequest): ClaimResponse =
        api.post(
            "api/v1/pair/claim", request, ClaimRequest.serializer(),
            ClaimResponse.serializer(), authenticated = false,
        )

    suspend fun snapshot(): DeviceSnapshot =
        api.get("api/v1/device/me", DeviceSnapshot.serializer())

    suspend fun syncConfig(): DeviceConfig =
        api.get("api/v1/device/config", DeviceConfig.serializer())

    /**
     * Device-side edits (the adult settings area) are written back through the
     * authenticated device endpoint. The server is authoritative and returns the
     * merged document, which the caller stores immediately.
     */
    suspend fun updateConfig(config: DeviceConfig): DeviceConfig =
        api.patch(
            "api/v1/device/config", ConfigPatch(config),
            ConfigPatch.serializer(), DeviceConfig.serializer(),
        )

    suspend fun reportStatus(status: StatusReport) {
        api.postRaw("api/v1/device/status", ApiJson.json.encodeToString(StatusReport.serializer(), status))
    }

    suspend fun pollCommands(since: String?): CommandsResponse {
        val suffix = since?.let { "?since=${encodeUrl(it)}" } ?: ""
        return api.get("api/v1/device/commands$suffix", CommandsResponse.serializer())
    }

    suspend fun acknowledge(commandId: String, result: String) {
        api.postRaw(
            "api/v1/device/commands/${encodeUrl(commandId)}/ack",
            ApiJson.json.encodeToString(AcknowledgeBody.serializer(), AcknowledgeBody(result)),
        )
    }

    suspend fun reportEvent(event: EventReport) {
        api.postRaw("api/v1/device/events", ApiJson.json.encodeToString(EventReport.serializer(), event))
    }

    suspend fun reportLocation(sample: LocationSample) {
        api.postRaw("api/v1/device/location", ApiJson.json.encodeToString(LocationSample.serializer(), sample))
    }

    /** Child starts a call to the paired parent. */
    suspend fun startOutgoingCall(media: String): OutgoingCall =
        api.post("api/v1/device/call", CallRequest(media), CallRequest.serializer(), OutgoingCall.serializer())

    /** Child joins a ringing call (incoming from parent, or its own outgoing call). */
    suspend fun joinCall(callId: String): CallTicket =
        api.post(
            "api/v1/device/call/${encodeUrl(callId)}/join", EmptyBody(),
            EmptyBody.serializer(), CallTicket.serializer(),
        )

    suspend fun declineCall(callId: String) {
        api.post(
            "api/v1/device/call/${encodeUrl(callId)}/decline", EmptyBody(),
            EmptyBody.serializer(), Unit.serializer(),
        )
    }
}

@kotlinx.serialization.Serializable
data class AcknowledgeBody(val result: String)

@kotlinx.serialization.Serializable
data class SafeZoneDraft(
    val name: String,
    val lat: Double,
    val lng: Double,
    val radiusMeters: Int,
)

@kotlinx.serialization.Serializable
data class ConfigPatch(val config: DeviceConfig)

@kotlinx.serialization.Serializable
data class CommandBody(val type: String, val payloadJson: String = "{}")

@kotlinx.serialization.Serializable
data class EmptyBody(val ignored: Boolean = true)

/** Parent endpoints, authorised by a short-lived session token. */
class ParentApi(private val api: ApiClient) {

    suspend fun login(email: String, password: String): AuthSession =
        api.post(
            "api/v1/auth/login", LoginRequest(email, password), LoginRequest.serializer(),
            AuthSession.serializer(), authenticated = false,
        )

    suspend fun register(email: String, password: String, name: String): AuthSession =
        api.post(
            "api/v1/auth/register", RegisterRequest(email, password, name),
            RegisterRequest.serializer(), AuthSession.serializer(), authenticated = false,
        )

    suspend fun createPairingCode(): PairingCode =
        api.post(
            "api/v1/pair/code", EmptyBody(), EmptyBody.serializer(), PairingCode.serializer(),
        )

    suspend fun devices(): List<PairedDevice> =
        api.get("api/v1/devices", ListSerializer(PairedDevice.serializer()))

    suspend fun device(deviceId: String): DeviceSnapshot =
        api.get("api/v1/devices/${encodeUrl(deviceId)}", DeviceSnapshot.serializer())

    suspend fun updateConfig(deviceId: String, config: DeviceConfig): DeviceConfig =
        api.patch(
            "api/v1/devices/${encodeUrl(deviceId)}/config", ConfigPatch(config),
            ConfigPatch.serializer(), DeviceConfig.serializer(),
        )

    suspend fun sendCommand(deviceId: String, type: String, payloadJson: String = "{}") {
        val body = CommandBody(type, payloadJson)
        api.postRaw(
            "api/v1/devices/${encodeUrl(deviceId)}/commands",
            ApiJson.json.encodeToString(CommandBody.serializer(), body),
        )
    }

    suspend fun locations(deviceId: String, limit: Int = 20): List<LocationSample> =
        api.get(
            "api/v1/devices/${encodeUrl(deviceId)}/locations?limit=$limit",
            ListSerializer(LocationSample.serializer()),
        )

    suspend fun safeZones(deviceId: String): List<SafeZone> =
        api.get(
            "api/v1/devices/${encodeUrl(deviceId)}/safezones",
            ListSerializer(SafeZone.serializer()),
        )

    suspend fun addSafeZone(deviceId: String, draft: SafeZoneDraft): SafeZone =
        api.post(
            "api/v1/devices/${encodeUrl(deviceId)}/safezones", draft,
            SafeZoneDraft.serializer(), SafeZone.serializer(),
        )

    suspend fun removeSafeZone(deviceId: String, zoneId: String) {
        api.delete("api/v1/devices/${encodeUrl(deviceId)}/safezones/${encodeUrl(zoneId)}")
    }

    /** Parent starts a call; the child receives a CALL command. */
    suspend fun startCall(deviceId: String, media: String): OutgoingCall =
        api.post(
            "api/v1/devices/${encodeUrl(deviceId)}/call", CallRequest(media),
            CallRequest.serializer(), OutgoingCall.serializer(),
        )

    suspend fun pendingCalls(): List<PendingCall> =
        api.get("api/v1/parent/calls", ListSerializer(PendingCall.serializer()))

    suspend fun joinCall(callId: String): CallTicket =
        api.post(
            "api/v1/parent/calls/${encodeUrl(callId)}/join", EmptyBody(),
            EmptyBody.serializer(), CallTicket.serializer(),
        )

    suspend fun declineCall(callId: String) {
        api.post(
            "api/v1/parent/calls/${encodeUrl(callId)}/decline", EmptyBody(),
            EmptyBody.serializer(), Unit.serializer(),
        )
    }
}
