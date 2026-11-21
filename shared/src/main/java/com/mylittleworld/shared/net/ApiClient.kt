package com.mylittleworld.shared.net

import com.mylittleworld.shared.model.ApiErrorBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

object ApiJson {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = false
    }
}

enum class ApiErrorKind {
    NETWORK, TIMEOUT, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, CONFLICT,
    RATE_LIMITED, SERVER, BAD_REQUEST, CONFIG, UNKNOWN,
}

class ApiException(
    val kind: ApiErrorKind,
    override val message: String,
    val httpCode: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** Copy shown to a parent; never contains tokens or child data. */
    val friendly: String
        get() = when (kind) {
            ApiErrorKind.NETWORK -> "No connection. Check Wi-Fi or mobile data and try again."
            ApiErrorKind.TIMEOUT -> "The server took too long to answer. Try again."
            ApiErrorKind.UNAUTHORIZED -> "Your session expired. Please sign in again."
            ApiErrorKind.FORBIDDEN -> "This action is not allowed for this account."
            ApiErrorKind.NOT_FOUND -> "That item no longer exists."
            ApiErrorKind.CONFLICT -> message
            ApiErrorKind.RATE_LIMITED -> "Too many attempts. Please wait a moment."
            ApiErrorKind.SERVER -> "The family service is having trouble. Try again shortly."
            ApiErrorKind.BAD_REQUEST -> message
            ApiErrorKind.CONFIG -> message
            ApiErrorKind.UNKNOWN -> "Something went wrong. Try again."
        }
}

/**
 * Small OkHttp wrapper. All calls are bearer-authenticated with a token supplied
 * lazily so credentials can rotate without rebuilding the client.
 */
class ApiClient(
    baseUrl: String,
    private val tokenProvider: () -> String? = { null },
    private val client: OkHttpClient = defaultHttpClient(),
) {
    private val base = baseUrl.trim().trimEnd('/')

    fun isValid(): Boolean = base.isNotEmpty() && "$base/".toHttpUrlOrNull() != null

    suspend fun <T> get(
        path: String,
        deserializer: DeserializationStrategy<T>,
        authenticated: Boolean = true,
    ): T = withContext(Dispatchers.IO) {
        decode(send { Request.Builder().url(url(path)).get() }, deserializer, authenticated)
    }

    suspend fun <B : Any, T> post(
        path: String,
        body: B,
        serializer: SerializationStrategy<B>,
        deserializer: DeserializationStrategy<T>,
        authenticated: Boolean = true,
    ): T = withContext(Dispatchers.IO) {
        val json = ApiJson.json.encodeToString(serializer, body)
        decode(send { Request.Builder().url(url(path)).post(json.toRequestBody(JSON_MEDIA)) }, deserializer, authenticated)
    }

    suspend fun <B : Any, T> patch(
        path: String,
        body: B,
        serializer: SerializationStrategy<B>,
        deserializer: DeserializationStrategy<T>,
        authenticated: Boolean = true,
    ): T = withContext(Dispatchers.IO) {
        val json = ApiJson.json.encodeToString(serializer, body)
        decode(send { Request.Builder().url(url(path)).patch(json.toRequestBody(JSON_MEDIA)) }, deserializer, authenticated)
    }

    suspend fun postRaw(path: String, json: String, authenticated: Boolean = true) {
        withContext(Dispatchers.IO) {
            send { Request.Builder().url(url(path)).post(json.toRequestBody(JSON_MEDIA)) }
                .use { ensureSuccess(it) }
        }
    }

    suspend fun delete(path: String, authenticated: Boolean = true) {
        withContext(Dispatchers.IO) {
            send { Request.Builder().url(url(path)).delete() }.use { ensureSuccess(it) }
        }
    }

    private fun url(path: String): String =
        if (path.startsWith("http")) path else "$base${if (path.startsWith("/")) path else "/$path"}"

    private fun send(builderBlock: () -> Request.Builder): Response {
        if (!isValid()) {
            throw ApiException(ApiErrorKind.CONFIG, "Family service address is not configured.")
        }
        val builder = try {
            builderBlock()
        } catch (e: IllegalArgumentException) {
            throw ApiException(ApiErrorKind.CONFIG, "Family service address is invalid.", cause = e)
        }.header("Accept", "application/json")

        // Authentication is resolved by the caller-specific wrapper below.
        val token = tokenProvider()
        if (token != null) builder.header("Authorization", "Bearer $token")
        return try {
            client.newCall(builder.build()).execute()
        } catch (e: SocketTimeoutException) {
            throw ApiException(ApiErrorKind.TIMEOUT, "Request timed out.", cause = e)
        } catch (e: UnknownHostException) {
            throw ApiException(ApiErrorKind.NETWORK, "Host unreachable.", cause = e)
        } catch (e: IOException) {
            throw ApiException(ApiErrorKind.NETWORK, "Network error.", cause = e)
        }
    }

    private fun <T> decode(
        response: Response,
        deserializer: DeserializationStrategy<T>,
        authenticated: Boolean,
    ): T = response.use { resp ->
        ensureSuccess(resp, authenticated)
        val body = resp.body?.string().orEmpty()
        if (body.isBlank()) {
            throw ApiException(ApiErrorKind.SERVER, "Empty response from server.", resp.code)
        }
        runCatching { ApiJson.json.decodeFromString(deserializer, body) }
            .getOrElse { throw ApiException(ApiErrorKind.SERVER, "Unexpected server response.", resp.code, it) }
    }

    private fun ensureSuccess(resp: Response, authenticated: Boolean = true) {
        if (resp.code in 200..299) return
        if (authenticated && resp.code == 401 && tokenProvider() == null) {
            throw ApiException(ApiErrorKind.UNAUTHORIZED, "Not signed in.", 401)
        }
        val raw = resp.body?.string().orEmpty()
        val parsed = runCatching { ApiJson.json.decodeFromString(ApiErrorBody.serializer(), raw) }.getOrNull()
        val message = parsed?.message?.takeIf { it.isNotBlank() }
            ?: parsed?.error?.takeIf { it.isNotBlank() }
            ?: "Request failed (${resp.code})."
        val kind = when (resp.code) {
            400, 422 -> ApiErrorKind.BAD_REQUEST
            401 -> ApiErrorKind.UNAUTHORIZED
            403 -> ApiErrorKind.FORBIDDEN
            404 -> ApiErrorKind.NOT_FOUND
            409 -> ApiErrorKind.CONFLICT
            429 -> ApiErrorKind.RATE_LIMITED
            in 500..599 -> ApiErrorKind.SERVER
            else -> ApiErrorKind.UNKNOWN
        }
        throw ApiException(kind, message, resp.code)
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
