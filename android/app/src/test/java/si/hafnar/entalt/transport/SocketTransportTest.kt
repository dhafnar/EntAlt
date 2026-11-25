package si.hafnar.entalt.transport

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import si.hafnar.entalt.data.AuthenticationException
import si.hafnar.entalt.data.Credentials
import si.hafnar.entalt.data.EntiaApi

/** Real OkHttp handshakes and frames against localhost; no Entia account is used. */
class SocketTransportTest {
    private suspend fun scenario(block: suspend CoroutineScope.(MockWebServer, EntiaApi) -> Unit) = coroutineScope {
        val server = MockWebServer().apply { start() }
        val client = EntiaApi.defaultClient()
        val api = EntiaApi(Credentials("test-user", "test-password"), client,
            baseUrl = server.url("/").toString(), socketUrl = server.url("/"))
        try { withTimeout(10_000) { block(server, api) } }
        finally {
            api.close()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            server.shutdown()
        }
    }

    private fun login(token: String) = MockResponse().setBody("""{"token":"$token"}""")

    @Test fun queryTokenRawFramesNoSubscriptionAndCancellation() = runBlocking {
        scenario { server, api ->
            val token = "test-token+/=?"
            val closed = CompletableDeferred<Unit>()
            val fromClient = AtomicInteger()
            server.enqueue(login(token))
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("raw frame") }
                override fun onMessage(webSocket: WebSocket, text: String) { fromClient.incrementAndGet() }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed.complete(Unit) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete(Unit) }
            }))
            val opened = CompletableDeferred<Unit>()
            val frame = CompletableDeferred<String>()
            val listening = launch { api.listen({ opened.complete(Unit) }, { frame.complete(it) }) }
            try {
                opened.await()
                assertEquals("raw frame", frame.await())
                assertEquals("/login", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
                val handshake = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals(token, handshake.requestUrl!!.queryParameter("token"))
                assertEquals("/", handshake.requestUrl!!.encodedPath)
                assertNull(handshake.getHeader("Authorization"))
                assertEquals(0, fromClient.get())
                assertEquals(30_000, api.client.pingIntervalMillis)
            } finally { listening.cancelAndJoin() }
            closed.await()
        }
    }

    @Test fun handshakeRejectionRenewsOnceWithTheNewToken() = runBlocking {
        scenario { server, api ->
            server.enqueue(login("old-token"))
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(login("new-token"))
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
            val opened = CompletableDeferred<Unit>()
            val listening = launch { api.listen({ opened.complete(Unit) }, {}) }
            try {
                opened.await()
                val requests = (1..4).map { server.takeRequest(2, TimeUnit.SECONDS)!! }
                assertEquals(listOf("/login", "/", "/login", "/"), requests.map { it.requestUrl!!.encodedPath })
                assertEquals("old-token", requests[1].requestUrl!!.queryParameter("token"))
                assertEquals("new-token", requests[3].requestUrl!!.queryParameter("token"))
            } finally { listening.cancelAndJoin() }
        }
    }

    @Test fun repeatedHandshakeRejectionStopsWithAuthenticationError() = runBlocking {
        scenario { server, api ->
            server.enqueue(login("old-token"))
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(login("new-token"))
            server.enqueue(MockResponse().setResponseCode(403))
            val error = runCatching { api.listen({}, {}) }.exceptionOrNull()
            assertTrue(error is AuthenticationException)
            assertEquals(4, server.requestCount)
            assertFalse(error.toString().contains("new-token"))
        }
    }

    @Test fun ordinaryHandshakeFailureDoesNotRenewCredentials() = runBlocking {
        scenario { server, api ->
            server.enqueue(login("private-test-token"))
            server.enqueue(MockResponse().setResponseCode(503))
            val error = runCatching { api.listen({}, {}) }.exceptionOrNull()
            assertTrue(error is IOException)
            assertFalse(error is AuthenticationException)
            assertEquals(2, server.requestCount)
            assertFalse(error.toString().contains("private-test-token"))
        }
    }

    @Test fun fullEventBufferClosesTransportAndRequiresResync() = runBlocking {
        scenario { server, api ->
            val socket = CompletableDeferred<WebSocket>()
            val disconnected = CompletableDeferred<Unit>()
            server.enqueue(login("test-token"))
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { socket.complete(webSocket) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { disconnected.complete(Unit) }
            }))
            val opened = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val listening = async(Dispatchers.Default) {
                runCatching { api.listen({ opened.complete(Unit); check(release.await(5, TimeUnit.SECONDS)) }, {}) }
            }
            try {
                opened.await()
                val remote = socket.await()
                repeat(512) { remote.send("event-$it") }
                disconnected.await()
                release.countDown()
                val error = listening.await().exceptionOrNull()
                assertTrue(error is IOException)
                assertEquals("Live updates need to reconnect.", error!!.message)
            } finally { release.countDown(); listening.cancelAndJoin() }
        }
    }
}
