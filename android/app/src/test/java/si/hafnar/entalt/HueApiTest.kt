package si.hafnar.entalt

import java.io.File
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import si.hafnar.entalt.data.*

class HueApiTest {
    @get:Rule val tempFolder = TemporaryFolder()

    private fun startSelfSignedHueServer(): MockWebServer {
        val keystoreFile = File(tempFolder.root, "hue-bridge.p12")
        val process = ProcessBuilder(
            "keytool", "-genkeypair",
            "-alias", "hue",
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-dname", "CN=001788FFFE123456, O=Philips Hue, C=NL",
            "-validity", "1",
            "-keystore", keystoreFile.absolutePath,
            "-storetype", "PKCS12",
            "-storepass", "changeit",
            "-keypass", "changeit",
        ).redirectErrorStream(true).start()
        val exited = process.waitFor(15, TimeUnit.SECONDS)
        assertTrue("keytool timed out", exited)
        assertEquals("keytool failed: ${process.inputStream.bufferedReader().readText()}", 0, process.exitValue())

        val ks = KeyStore.getInstance("PKCS12").apply {
            keystoreFile.inputStream().use { load(it, "changeit".toCharArray()) }
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(ks, "changeit".toCharArray())
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, null, null)
        }
        return MockWebServer().apply {
            useHttps(sslContext.socketFactory, false)
            start()
        }
    }

    @Test fun selfSignedTlsPairingFetchAndCommandsFollowClipV2() = runBlocking {
        val server = startSelfSignedHueServer()
        var pairCalls = 0
        val putBodies = mutableListOf<Pair<String, JsonObject>>()
        val seenKeys = mutableListOf<String?>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/api" && request.method == "POST") {
                    pairCalls++
                    val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    assertEquals("entalt#android", body["devicetype"]!!.jsonPrimitive.content)
                    assertEquals(true, body["generateclientkey"]!!.jsonPrimitive.boolean)
                    return if (pairCalls == 1) {
                        MockResponse().setBody("""[{"error":{"type":101,"address":"","description":"link button not pressed"}}]""")
                    } else {
                        MockResponse().setBody("""[{"success":{"username":"test-app-key","clientkey":"CLIENTKEY123"}}]""")
                    }
                }
                seenKeys += request.getHeader("hue-application-key")
                return when {
                    request.path == "/clip/v2/resource/light" && request.method == "GET" -> MockResponse().setBody(
                        """{
                            "errors": [],
                            "data": [
                                {
                                    "id": "light-1",
                                    "type": "light",
                                    "owner": {"rid": "dev-1", "rtype": "device"},
                                    "metadata": {"name": "Ceiling Spot"},
                                    "on": {"on": true},
                                    "dimming": {"brightness": 80.4},
                                    "color_temperature": {"mirek": 366, "mirek_schema": {"mirek_minimum": 153, "mirek_maximum": 500}},
                                    "color": {"xy": {"x": 0.45, "y": 0.41}}
                                },
                                {
                                    "id": "light-2",
                                    "type": "light",
                                    "owner": {"rid": "dev-2", "rtype": "device"},
                                    "metadata": {"name": "Hallway Bulb"},
                                    "on": {"on": false},
                                    "dimming": {"brightness": 40.0}
                                }
                            ]
                        }"""
                    )
                    request.path == "/clip/v2/resource/room" && request.method == "GET" -> MockResponse().setBody(
                        """{
                            "errors": [],
                            "data": [
                                {
                                    "id": "room-1",
                                    "type": "room",
                                    "metadata": {"name": "Living room"},
                                    "children": [{"rid": "dev-1", "rtype": "device"}],
                                    "services": [{"rid": "gl-room-1", "rtype": "grouped_light"}]
                                }
                            ]
                        }"""
                    )
                    request.path == "/clip/v2/resource/zone" && request.method == "GET" -> MockResponse().setBody(
                        """{
                            "errors": [],
                            "data": [
                                {
                                    "id": "zone-1",
                                    "type": "zone",
                                    "metadata": {"name": "Downstairs"},
                                    "children": [{"rid": "dev-1", "rtype": "device"}, {"rid": "dev-2", "rtype": "device"}],
                                    "services": [{"rid": "gl-zone-1", "rtype": "grouped_light"}]
                                }
                            ]
                        }"""
                    )
                    request.path == "/clip/v2/resource/grouped_light" && request.method == "GET" -> MockResponse().setBody(
                        """{
                            "errors": [],
                            "data": [
                                {"id": "gl-room-1", "type": "grouped_light", "on": {"on": true}, "dimming": {"brightness": 75.0}},
                                {"id": "gl-zone-1", "type": "grouped_light", "on": {"on": false}, "dimming": {"brightness": 50.0}}
                            ]
                        }"""
                    )
                    request.method == "PUT" -> {
                        val payload = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                        putBodies += (request.path ?: "") to payload
                        MockResponse().setBody("""{"data":[{"rid":"light-1","rtype":"light"}],"errors":[]}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        try {
            val bridgeAddress = "${server.hostName}:${server.port}"
            // 1. Pairing: first attempt returns LinkButtonNotPressed, second returns Success
            assertEquals(HuePairResult.LinkButtonNotPressed, HueApi.pair(bridgeAddress))
            val paired = HueApi.pair(bridgeAddress)
            assertEquals(HuePairResult.Success("test-app-key", "CLIENTKEY123"), paired)

            // 2. Fetch lights, rooms, and zones using isolated self-signed Hue client
            val api = HueApi(bridgeAddress, "test-app-key")
            try {
                val targets = api.fetchTargets()
                assertTrue(seenKeys.all { it == "test-app-key" })
                assertEquals(4, targets.size)

                val light1 = targets.first { it.id == "light-1" }
                assertEquals("light", light1.type)
                assertEquals(HueTargetKind.Light, light1.kind)
                assertTrue(light1.on)
                assertEquals(80, light1.brightness)
                assertTrue(light1.supportsDimming)
                assertTrue(light1.supportsColorTemp)
                assertEquals(366, light1.mirek)
                assertTrue(light1.supportsColor)
                assertEquals(0.45, light1.xy!!.first, 0.001)
                assertEquals(0.41, light1.xy!!.second, 0.001)

                val light2 = targets.first { it.id == "light-2" }
                assertFalse(light2.on)
                assertEquals(40, light2.brightness)
                assertTrue(light2.supportsDimming)
                assertFalse(light2.supportsColorTemp)
                assertFalse(light2.supportsColor)

                val room = targets.first { it.id == "gl-room-1" }
                assertEquals("grouped_light", room.type)
                assertEquals(HueTargetKind.Room, room.kind)
                assertEquals("Living room", room.name)
                assertTrue(room.on)
                assertEquals(75, room.brightness)
                // Inherited color & CT capability from member light-1
                assertTrue(room.supportsColorTemp)
                assertTrue(room.supportsColor)

                val zone = targets.first { it.id == "gl-zone-1" }
                assertEquals(HueTargetKind.Zone, zone.kind)
                assertEquals("Downstairs", zone.name)
                assertFalse(zone.on)

                // 3. PUT commands
                api.setPower("light", "light-1", false)
                api.setBrightness("grouped_light", "gl-room-1", 65)
                api.setColorTemp("light", "light-1", 285)
                api.setColorXy("light", "light-1", 0.285, 0.205)

                assertEquals(4, putBodies.size)
                assertEquals("/clip/v2/resource/light/light-1", putBodies[0].first)
                assertEquals(false, putBodies[0].second["on"]!!.jsonObject["on"]!!.jsonPrimitive.boolean)

                assertEquals("/clip/v2/resource/grouped_light/gl-room-1", putBodies[1].first)
                assertEquals(true, putBodies[1].second["on"]!!.jsonObject["on"]!!.jsonPrimitive.boolean)
                assertEquals(65, putBodies[1].second["dimming"]!!.jsonObject["brightness"]!!.jsonPrimitive.int)

                assertEquals("/clip/v2/resource/light/light-1", putBodies[2].first)
                assertEquals(285, putBodies[2].second["color_temperature"]!!.jsonObject["mirek"]!!.jsonPrimitive.int)

                assertEquals("/clip/v2/resource/light/light-1", putBodies[3].first)
                val xy = putBodies[3].second["color"]!!.jsonObject["xy"]!!.jsonObject
                assertEquals(0.285, xy["x"]!!.jsonPrimitive.double, 0.0001)
                assertEquals(0.205, xy["y"]!!.jsonPrimitive.double, 0.0001)
            } finally {
                api.close()
            }
        } finally {
            server.shutdown()
        }
    }

    @Test fun unauthorizedKeyThrowsHueAuthException() = runBlocking {
        val tlsServer = startSelfSignedHueServer()
        tlsServer.enqueue(MockResponse().setResponseCode(403))
        try {
            val api = HueApi("${tlsServer.hostName}:${tlsServer.port}", "revoked-key")
            try {
                var threwAuth = false
                try {
                    api.fetchTargets()
                } catch (_: HueAuthException) {
                    threwAuth = true
                }
                assertTrue(threwAuth)
            } finally {
                api.close()
            }
        } finally {
            tlsServer.shutdown()
        }
    }

    @Test fun cloudDiscoveryAddressNormalizationAndPresetsWork() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""[{"id":"001788fffe000001","internalipaddress":"192.168.1.50","port":443}]"""))
        server.start()
        try {
            val bridges = HueDiscovery.discoverCloud(
                client = EntiaApi.defaultClient(),
                url = server.url("/").toString(),
            )
            assertEquals(1, bridges.size)
            assertEquals("192.168.1.50", bridges[0].ip)
            assertEquals("001788fffe000001", bridges[0].id)
        } finally {
            server.shutdown()
        }

        assertEquals("192.168.1.50", normalizeBridgeAddress(" https://192.168.1.50/ "))
        assertEquals("192.168.1.50:8443", normalizeBridgeAddress("192.168.1.50:8443"))
        assertNull(normalizeBridgeAddress("   "))

        assertEquals(2200, mirekToKelvin(HueTempPreset.Warm.mirek))
        assertEquals(2700, mirekToKelvin(HueTempPreset.Soft.mirek))
        assertEquals(3500, mirekToKelvin(HueTempPreset.Neutral.mirek))
        assertEquals(5000, mirekToKelvin(HueTempPreset.Daylight.mirek))
        assertEquals(6500, mirekToKelvin(HueTempPreset.Cool.mirek))
        assertEquals(0xFFFFB347L, HueColorPreset.WarmAmber.argb)
    }
}
