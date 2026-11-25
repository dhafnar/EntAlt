package si.hafnar.entalt.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class Credentials(val username: String, val password: String) {
    override fun toString() = "Credentials([redacted])"
}
class AuthenticationException : IOException("Your account needs to sign in again.")
class ApiException(val status: Int) : IOException(when (status) {
    403 -> "This account does not have permission for that action."
    429 -> "Entia is receiving too many requests. Please wait and retry."
    in 500..599 -> "Entia is temporarily unavailable. Please try again."
    else -> "Entia could not complete the request (HTTP $status)."
})

class EntiaApi(
    private val credentials: Credentials,
    val client: OkHttpClient = defaultClient(),
    private val baseUrl: String = "https://api.entia.si/",
    private val socketUrl: HttpUrl = HttpUrl.Builder().scheme("https").host("ws.entia.si").build(),
) : EntiaGateway {
    private val json = Json { ignoreUnknownKeys = true }
    private val loginLock = Mutex()
    private val lifecycleLock = Any()
    @Volatile private var bearer: String? = null
    private val calls = java.util.concurrent.ConcurrentHashMap.newKeySet<Call>()
    private val sockets = java.util.concurrent.ConcurrentHashMap.newKeySet<WebSocket>()
    @Volatile private var closed = false

    override suspend fun token(): String = bearer ?: renew(null)

    override fun clearToken() {
        synchronized(lifecycleLock) {
            bearer = null
        }
    }

    override suspend fun renew(rejected: String?): String = loginLock.withLock {
        check(!closed) { "Session ended." }
        bearer?.takeIf { it != rejected }?.let { return@withLock it }
        val payload = buildJsonObject {
            put("username", credentials.username); put("password", credentials.password)
            put("platform", "home_assistant"); put("device_model", "home_assistant"); put("device_serial", "0000")
        }
        val response = execute(Request.Builder().url(baseUrl + "login").post(body(payload)).build())
        if (response.first in listOf(401, 403)) throw AuthenticationException()
        checkStatus(response.first)
        val result = runCatching { json.parseToJsonElement(response.second) as? JsonObject }.getOrNull()
        val value = (result?.get("token") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw IOException("Entia returned no session token.")
        synchronized(lifecycleLock) {
            check(!closed) { "Session ended." }
            bearer = value
        }
        value
    }

    override suspend fun validate(): JsonObject { token(); return flat() }
    override suspend fun flat() = request("GET", "flat") as? JsonObject ?: throw IOException("Entia returned an invalid home definition.")
    override suspend fun devices() = request("GET", "flat/device")
    override suspend fun set(device: Long, attribute: Int, value: Int) {
        request("PUT", "flat/device/$device/attribute/$attribute/rt", buildJsonObject { put("value", value) }, allowEmpty = true)
    }

    private suspend fun request(method: String, path: String, payload: JsonObject? = null, allowEmpty: Boolean = false): JsonElement {
        val sentToken = token()
        suspend fun send(token: String) = execute(Request.Builder().url(baseUrl + path)
            .header("Authorization", "Bearer $token").method(method, payload?.let(::body)).build())
        var result = send(sentToken)
        if (result.first == 401 || result.first == 403) result = send(renew(sentToken))
        if (result.first == 401) throw AuthenticationException()
        checkStatus(result.first)
        if (allowEmpty) return JsonNull // /rt may return status only; snapshots establish actual state.
        if (result.second.isBlank()) throw IOException("Entia returned an empty response.")
        return try { json.parseToJsonElement(result.second) } catch (_: Exception) {
            throw IOException("Entia returned an unreadable response.")
        }
    }

    private suspend fun execute(request: Request): Pair<Int, String> = suspendCancellableCoroutine { continuation ->
        synchronized(lifecycleLock) {
        if (closed) { continuation.resumeWithException(IOException("Session ended.")); return@suspendCancellableCoroutine }
        val call = client.newCall(request)
        calls += call
        continuation.invokeOnCancellation { call.cancel(); calls -= call }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                calls -= call
                // Never include OkHttp's original URL: the socket URL may contain a token.
                if (continuation.isActive) continuation.resumeWithException(IOException("Cannot connect to Entia. Check your connection and retry."))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        val result = it.code to (it.body?.string().orEmpty())
                        if (continuation.isActive) continuation.resume(result)
                    }
                } catch (_: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("The connection ended before the response completed."))
                } finally { calls -= call }
            }
        })
        }
    }

    private class SocketRejected : IOException()
    private sealed interface SocketMessage {
        data object Open : SocketMessage
        data class Text(val text: String) : SocketMessage
    }

    override suspend fun listen(onOpen: () -> Unit, onEvent: (String) -> Unit) {
        val sent = token()
        try { listenOnce(sent, onOpen, onEvent) } catch (_: SocketRejected) {
            val renewed = renew(sent)
            try { listenOnce(renewed, onOpen, onEvent) } catch (_: SocketRejected) { throw AuthenticationException() }
        }
    }

    private suspend fun listenOnce(token: String, onOpen: () -> Unit, onEvent: (String) -> Unit) {
        check(!closed) { "Session ended." }
        val messages = Channel<SocketMessage>(64)
        val url = socketUrl.newBuilder().addQueryParameter("token", token).build()
        val socket = synchronized(lifecycleLock) {
            check(!closed) { "Session ended." }
            client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { messages.trySend(SocketMessage.Open) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (messages.trySend(SocketMessage.Text(text)).isFailure) {
                    messages.close(IOException("Live updates need to reconnect.")); webSocket.cancel()
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { messages.close() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val rejected = response?.code == 401 || response?.code == 403
                response?.close()
                messages.close(if (rejected) SocketRejected() else IOException("Live updates disconnected."))
            }
            }).also { sockets += it }
        }
        try {
            if (closed) throw IOException("Session ended.")
            for (message in messages) when (message) {
                SocketMessage.Open -> onOpen()
                is SocketMessage.Text -> onEvent(message.text)
            }
        } finally { socket.cancel(); sockets -= socket; messages.cancel() }
    }

    override fun close() = synchronized(lifecycleLock) {
        closed = true; bearer = null
        calls.forEach { it.cancel() }; calls.clear()
        sockets.forEach { it.cancel() }; sockets.clear()
    }
    private fun checkStatus(status: Int) { if (status !in 200..299) throw ApiException(status) }
    private fun body(value: JsonObject) = value.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

    companion object {
        private val widgetSharedClient: OkHttpClient by lazy { defaultClient() }

        fun sharedClient(): OkHttpClient = widgetSharedClient

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS).retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false).pingInterval(30, TimeUnit.SECONDS).build()
    }
}
