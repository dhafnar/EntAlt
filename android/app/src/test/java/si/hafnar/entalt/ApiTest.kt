package si.hafnar.entalt

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import si.hafnar.entalt.data.*
import si.hafnar.entalt.data.Credentials

class ApiTest {
    private fun client(handler: (Request) -> Pair<Int, String>) = EntiaApi.defaultClient().newBuilder()
        .addInterceptor { chain ->
            val (status, body) = handler(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("Mock").body(body.toResponseBody()).build()
        }.build()

    @Test fun loginAndWriteFollowReferenceWithoutApiKey() = runBlocking {
        val seen = mutableListOf<Request>()
        val api = EntiaApi(Credentials("alice", " spaced password "), client { request ->
            seen += request
            when (request.url.encodedPath) {
                "/login" -> 200 to """{"token":"test-token"}"""
                "/flat" -> 200 to """{"flat":{"floors":[]}}"""
                else -> 204 to ""
            }
        })
        try {
            api.validate()
            api.set(10, 1105, 43)
            val login = Json.parseToJsonElement(Buffer().also { seen[0].body!!.writeTo(it) }.readUtf8()).jsonObject
            assertEquals(" spaced password ", login["password"]!!.jsonPrimitive.content)
            assertEquals("home_assistant", login["platform"]!!.jsonPrimitive.content)
            assertEquals("home_assistant", login["device_model"]!!.jsonPrimitive.content)
            assertEquals("0000", login["device_serial"]!!.jsonPrimitive.content)
            assertEquals(setOf("username", "password", "platform", "device_model", "device_serial"), login.keys)
            val write = seen.last()
            assertEquals("PUT", write.method)
            assertEquals("/flat/device/10/attribute/1105/rt", write.url.encodedPath)
            assertEquals("Bearer test-token", write.header("Authorization"))
            assertEquals("""{"value":43}""", Buffer().also { write.body!!.writeTo(it) }.readUtf8())
        } finally { api.close() }
    }

    @Test fun concurrent401ResponsesShareOneRenewal() = runBlocking {
        val logins = AtomicInteger()
        val rejected = AtomicInteger()
        val barrier = CountDownLatch(5)
        val api = EntiaApi(Credentials("alice", "secret"), client { request ->
            when {
                request.url.encodedPath == "/login" -> 200 to """{"token":"token-${logins.incrementAndGet()}"}"""
                request.header("Authorization") == "Bearer token-1" -> {
                    rejected.incrementAndGet(); barrier.countDown()
                    check(barrier.await(3, TimeUnit.SECONDS))
                    401 to ""
                }
                else -> 200 to """{"flat":{"floors":[]}}"""
            }
        })
        try {
            api.token()
            withTimeout(10_000) { coroutineScope { (1..12).map { async { api.flat() } }.awaitAll() } }
            assertEquals(2, logins.get())
            assertTrue(rejected.get() >= 5)
        } finally { api.close() }
    }

    @Test fun repeated401StopsAfterOneRetry() = runBlocking {
        val login = AtomicInteger(); val calls = AtomicInteger()
        val api = EntiaApi(Credentials("alice", "secret"), client { request ->
            if (request.url.encodedPath == "/login") 200 to """{"token":"t${login.incrementAndGet()}"}"""
            else { calls.incrementAndGet(); 401 to "" }
        })
        try {
            assertTrue(runCatching { api.flat() }.exceptionOrNull() is AuthenticationException)
            assertEquals(2, login.get()); assertEquals(2, calls.get())
        } finally { api.close() }
    }

    @Test fun ambiguousTransportFailureDoesNotReplayPutOrExposeSecrets() = runBlocking {
        val writes = AtomicInteger()
        val api = EntiaApi(Credentials("alice", "secret"), client { request ->
            if (request.url.encodedPath == "/login") 200 to """{"token":"private-token"}"""
            else { writes.incrementAndGet(); throw IOException("URL includes private-token") }
        })
        try {
            val error = runCatching { api.set(1, 401, 0) }.exceptionOrNull()!!
            assertEquals(1, writes.get())
            assertFalse(error.toString().contains("private-token"))
            assertFalse(error.toString().contains("secret"))
        } finally { api.close() }
    }

    @Test fun status403TriggersRenewalAndRetriesOnce() = runBlocking {
        val logins = AtomicInteger()
        val api = EntiaApi(Credentials("alice", "secret"), client { request ->
            when {
                request.url.encodedPath == "/login" -> 200 to """{"token":"token-${logins.incrementAndGet()}"}"""
                request.header("Authorization") == "Bearer token-1" -> 403 to ""
                else -> 200 to """{"flat":{"floors":[]}}"""
            }
        })
        try {
            val flat = api.flat()
            assertTrue(flat["flat"] is JsonObject)
            assertEquals(2, logins.get())
        } finally { api.close() }
    }

    @Test fun repeated403ThrowsApiExceptionNotAuthenticationException() = runBlocking {
        val logins = AtomicInteger(); val calls = AtomicInteger()
        val api = EntiaApi(Credentials("alice", "secret"), client { request ->
            if (request.url.encodedPath == "/login") 200 to """{"token":"t${logins.incrementAndGet()}"}"""
            else { calls.incrementAndGet(); 403 to "" }
        })
        try {
            val error = runCatching { api.flat() }.exceptionOrNull()
            assertTrue(error is ApiException)
            assertEquals(403, (error as ApiException).status)
            assertEquals(2, logins.get()); assertEquals(2, calls.get())
        } finally { api.close() }
    }

    @Test fun clearTokenForcesFreshLoginOnNextCall() = runBlocking {
        val logins = AtomicInteger()
        val seenAuth = mutableListOf<String?>()
        val api = EntiaApi(Credentials("alice", "secret"), client { request ->
            if (request.url.encodedPath == "/login") {
                200 to """{"token":"t${logins.incrementAndGet()}"}"""
            } else {
                seenAuth += request.header("Authorization")
                200 to """{"flat":{"floors":[]}}"""
            }
        })
        try {
            api.flat()
            api.clearToken()
            api.flat()
            assertEquals(2, logins.get())
            assertEquals(listOf("Bearer t1", "Bearer t2"), seenAuth)
        } finally { api.close() }
    }
}
