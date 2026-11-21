package com.mylittleworld.child.call

import com.mylittleworld.shared.model.CallTicket
import com.mylittleworld.shared.net.ApiJson
import kotlinx.serialization.Serializable

/**
 * Payload handed to the bundled WebRTC page. Only the details needed to join the
 * room are included: no device token, no parent credentials.
 */
@Serializable
data class CallBootstrap(
    val websocketUrl: String,
    val callId: String,
    val role: String,
    val media: String,
    val parentName: String,
    val mode: String,
    val iceServers: List<IceConfig> = emptyList(),
    val startsMuted: Boolean = true,
)

@Serializable
data class IceConfig(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
)

object CallTicketJson {

    fun toBootstrap(ticket: CallTicket, startsMuted: Boolean): String {
        val payload = CallBootstrap(
            websocketUrl = ticket.websocketUrl,
            callId = ticket.callId,
            role = ticket.role,
            media = ticket.media,
            parentName = ticket.parentName,
            mode = ticket.mode,
            iceServers = ticket.iceServers.map { IceConfig(it.urls, it.username, it.credential) },
            startsMuted = startsMuted,
        )
        return ApiJson.json.encodeToString(CallBootstrap.serializer(), payload)
    }
}
