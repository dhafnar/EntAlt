package si.hafnar.entalt.data

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class HueAuthException : IOException("Hue Bridge rejected the application key. Pair the bridge again.")

class HueApiException(val status: Int) : IOException("Hue Bridge request failed (HTTP $status).")

enum class HueTempPreset(val mirek: Int, val kelvin: Int) {
    Warm(454, 2200),
    Soft(370, 2700),
    Neutral(285, 3500),
    Daylight(200, 5000),
    Cool(153, 6500),
}

enum class HueColorPreset(val x: Double, val y: Double, val argb: Long) {
    WarmAmber(0.561, 0.404, 0xFFFFB347L),
    Coral(0.585, 0.340, 0xFFFF6F61L),
    Sunset(0.640, 0.330, 0xFFFF4E50L),
    Lavender(0.285, 0.205, 0xFFB388FFL),
    OceanBlue(0.167, 0.140, 0xFF448AFFL),
    Emerald(0.215, 0.650, 0xFF2ECC71L),
}

fun mirekToKelvin(mirek: Int): Int {
    val safe = mirek.coerceIn(153, 500)
    return ((1_000_000.0 / safe) / 100.0).roundToInt() * 100
}

fun xyToArgb(x: Double, y: Double): Long {
    val safeY = y.coerceAtLeast(0.0001)
    val z = (1.0 - x - safeY).coerceAtLeast(0.0)
    val bigY = 1.0
    val bigX = (bigY / safeY) * x
    val bigZ = (bigY / safeY) * z
    var r = bigX * 1.656492 - bigY * 0.354851 - bigZ * 0.255038
    var g = -bigX * 0.707196 + bigY * 1.655397 + bigZ * 0.036152
    var b = bigX * 0.051713 - bigY * 0.121364 + bigZ * 1.011530
    val maxChannel = maxOf(r, g, b, 1e-6)
    if (maxChannel > 1.0) {
        r /= maxChannel
        g /= maxChannel
        b /= maxChannel
    }
    fun gamma(c: Double): Int {
        val clamped = c.coerceIn(0.0, 1.0)
        val corrected = if (clamped <= 0.0031308) 12.92 * clamped else 1.055 * clamped.pow(1.0 / 2.4) - 0.055
        return (corrected * 255.0).roundToInt().coerceIn(0, 255)
    }
    val ri = gamma(r)
    val gi = gamma(g)
    val bi = gamma(b)
    return 0xFF000000L or (ri.toLong() shl 16) or (gi.toLong() shl 8) or bi.toLong()
}

fun normalizeBridgeAddress(raw: String): String? {
    val cleaned = raw.trim()
        .removePrefix("https://")
        .removePrefix("http://")
        .substringBefore('/')
        .trim()
    if (cleaned.isBlank()) return null
    val parsed = "https://$cleaned".toHttpUrlOrNull() ?: return null
    val host = if (':' in parsed.host) "[${parsed.host}]" else parsed.host
    return if (parsed.port == 443) host else "$host:${parsed.port}"
}

class HueApi(
    bridgeAddress: String,
    private val appKey: String,
    client: OkHttpClient? = null,
) : HueGateway {
    private val parsedUrl = parseBridgeUrl(bridgeAddress)
    private val client: OkHttpClient = client ?: localBridgeClient(parsedUrl.host)
    private val json = Json { ignoreUnknownKeys = true }
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val lifecycleLock = Any()
    @Volatile private var closed = false

    override suspend fun fetchTargets(): List<HueTarget> = coroutineScope {
        val lightsDeferred = async { getResource("light") }
        val roomsDeferred = async { runCatchingNonAuth { getResource("room") } }
        val zonesDeferred = async { runCatchingNonAuth { getResource("zone") } }
        val groupedDeferred = async { runCatchingNonAuth { getResource("grouped_light") } }

        val lightItems = lightsDeferred.await()
        val roomItems = roomsDeferred.await().orEmpty()
        val zoneItems = zonesDeferred.await().orEmpty()
        val groupedItems = groupedDeferred.await().orEmpty()

        val lights = lightItems.mapNotNull(::parseLightTarget)
        val lightsById = lights.associateBy { it.id }
        val lightsByOwner = lights.filter { it.ownerDeviceId != null }.groupBy { it.ownerDeviceId!! }
        val groupedById = groupedItems.mapNotNull(::parseGroupedLightState).associateBy { it.id }

        val roomTargets = roomItems.mapNotNull { item ->
            parseGroupTarget(item, HueTargetKind.Room, groupedById, lightsById, lightsByOwner)
        }
        val zoneTargets = zoneItems.mapNotNull { item ->
            parseGroupTarget(item, HueTargetKind.Zone, groupedById, lightsById, lightsByOwner)
        }

        lights.sortedBy { it.name.lowercase() } +
            roomTargets.sortedBy { it.name.lowercase() } +
            zoneTargets.sortedBy { it.name.lowercase() }
    }

    override suspend fun setPower(targetType: String, id: String, on: Boolean) {
        putResource(targetType, id, buildJsonObject {
            putJsonObject("on") { put("on", on) }
        })
    }

    override suspend fun setBrightness(targetType: String, id: String, brightness: Int) {
        val clamped = brightness.coerceIn(1, 100)
        putResource(targetType, id, buildJsonObject {
            putJsonObject("on") { put("on", true) }
            putJsonObject("dimming") { put("brightness", clamped) }
        })
    }

    override suspend fun setColorTemp(targetType: String, id: String, mirek: Int) {
        val clamped = mirek.coerceIn(153, 500)
        putResource(targetType, id, buildJsonObject {
            putJsonObject("on") { put("on", true) }
            putJsonObject("color_temperature") { put("mirek", clamped) }
        })
    }

    override suspend fun setColorXy(targetType: String, id: String, x: Double, y: Double) {
        val safeX = x.coerceIn(0.0, 1.0)
        val safeY = y.coerceIn(0.0, 1.0)
        putResource(targetType, id, buildJsonObject {
            putJsonObject("on") { put("on", true) }
            putJsonObject("color") {
                putJsonObject("xy") {
                    put("x", safeX)
                    put("y", safeY)
                }
            }
        })
    }

    override fun close() = synchronized(lifecycleLock) {
        closed = true
        calls.forEach { it.cancel() }
        calls.clear()
    }

    private suspend inline fun <T> runCatchingNonAuth(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: HueAuthException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private suspend fun getResource(resource: String): List<JsonObject> {
        val url = parsedUrl.newBuilder().addPathSegments("clip/v2/resource/$resource").build()
        val request = Request.Builder()
            .url(url)
            .header("hue-application-key", appKey)
            .get()
            .build()
        val (status, body) = execute(client, calls, lifecycleLock, { closed }, request)
        if (status == 401 || status == 403) throw HueAuthException()
        if (status !in 200..299) throw HueApiException(status)
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: throw IOException("Hue Bridge returned an unreadable response.")
        val data = root["data"] as? JsonArray ?: return emptyList()
        return data.mapNotNull { it as? JsonObject }
    }

    private suspend fun putResource(targetType: String, id: String, payload: JsonObject) {
        require(targetType == "light" || targetType == "grouped_light") { "Unsupported Hue resource type: $targetType" }
        val safeId = id.trim()
        require(safeId.isNotEmpty() && '/' !in safeId && ".." !in safeId) { "Invalid Hue resource ID." }
        val url = parsedUrl.newBuilder().addPathSegments("clip/v2/resource/$targetType/$safeId").build()
        val request = Request.Builder()
            .url(url)
            .header("hue-application-key", appKey)
            .put(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val (status, body) = execute(client, calls, lifecycleLock, { closed }, request)
        if (status == 401 || status == 403) throw HueAuthException()
        if (status !in 200..299) throw HueApiException(status)
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        val data = root?.get("data") as? JsonArray
        val errors = root?.get("errors") as? JsonArray
        if ((data == null || data.isEmpty()) && !errors.isNullOrEmpty()) {
            val message = (errors.firstOrNull() as? JsonObject)
                ?.get("description")?.let { (it as? JsonPrimitive)?.contentOrNull }
                ?: "Hue Bridge could not apply the change."
            throw IOException(message)
        }
    }

    private data class GroupedLightState(
        val id: String,
        val on: Boolean,
        val brightness: Int?,
        val supportsDimming: Boolean,
        val mirek: Int?,
        val mirekMin: Int,
        val mirekMax: Int,
        val supportsColorTemp: Boolean,
        val xy: Pair<Double, Double>?,
        val supportsColor: Boolean,
    )

    private fun parseLightTarget(obj: JsonObject): HueTarget? {
        val id = (obj["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val metadata = obj["metadata"] as? JsonObject
        val name = (metadata?.get("name") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: id
        val on = ((obj["on"] as? JsonObject)?.get("on") as? JsonPrimitive)?.booleanOrNull ?: false
        val dimming = obj["dimming"] as? JsonObject
        val brightness = (dimming?.get("brightness") as? JsonPrimitive)?.doubleOrNull?.roundToInt()?.coerceIn(0, 100)
        val supportsDimming = dimming != null
        val ctObj = obj["color_temperature"] as? JsonObject
        val mirek = (ctObj?.get("mirek") as? JsonPrimitive)?.intOrNull
        val schema = ctObj?.get("mirek_schema") as? JsonObject
        val mirekMin = (schema?.get("mirek_minimum") as? JsonPrimitive)?.intOrNull ?: 153
        val mirekMax = (schema?.get("mirek_maximum") as? JsonPrimitive)?.intOrNull ?: 500
        val supportsColorTemp = ctObj != null
        val colorObj = obj["color"] as? JsonObject
        val xyObj = colorObj?.get("xy") as? JsonObject
        val x = (xyObj?.get("x") as? JsonPrimitive)?.doubleOrNull
        val y = (xyObj?.get("y") as? JsonPrimitive)?.doubleOrNull
        val xy = if (x != null && y != null) x to y else null
        val supportsColor = colorObj != null
        val owner = obj["owner"] as? JsonObject
        val ownerDeviceId = if ((owner?.get("rtype") as? JsonPrimitive)?.contentOrNull == "device") {
            (owner["rid"] as? JsonPrimitive)?.contentOrNull
        } else null
        return HueTarget(
            id = id,
            type = "light",
            name = name,
            kind = HueTargetKind.Light,
            on = on,
            brightness = brightness,
            supportsDimming = supportsDimming,
            mirek = mirek?.coerceIn(mirekMin, mirekMax),
            mirekMin = mirekMin,
            mirekMax = mirekMax,
            supportsColorTemp = supportsColorTemp,
            xy = xy,
            supportsColor = supportsColor,
            ownerDeviceId = ownerDeviceId,
        )
    }

    private fun parseGroupedLightState(obj: JsonObject): GroupedLightState? {
        val id = (obj["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val on = ((obj["on"] as? JsonObject)?.get("on") as? JsonPrimitive)?.booleanOrNull ?: false
        val dimming = obj["dimming"] as? JsonObject
        val brightness = (dimming?.get("brightness") as? JsonPrimitive)?.doubleOrNull?.roundToInt()?.coerceIn(0, 100)
        val ctObj = obj["color_temperature"] as? JsonObject
        val mirek = (ctObj?.get("mirek") as? JsonPrimitive)?.intOrNull
        val schema = ctObj?.get("mirek_schema") as? JsonObject
        val mirekMin = (schema?.get("mirek_minimum") as? JsonPrimitive)?.intOrNull ?: 153
        val mirekMax = (schema?.get("mirek_maximum") as? JsonPrimitive)?.intOrNull ?: 500
        val colorObj = obj["color"] as? JsonObject
        val xyObj = colorObj?.get("xy") as? JsonObject
        val x = (xyObj?.get("x") as? JsonPrimitive)?.doubleOrNull
        val y = (xyObj?.get("y") as? JsonPrimitive)?.doubleOrNull
        return GroupedLightState(
            id = id,
            on = on,
            brightness = brightness,
            supportsDimming = dimming != null,
            mirek = mirek?.coerceIn(mirekMin, mirekMax),
            mirekMin = mirekMin,
            mirekMax = mirekMax,
            supportsColorTemp = ctObj != null,
            xy = if (x != null && y != null) x to y else null,
            supportsColor = colorObj != null,
        )
    }

    private fun parseGroupTarget(
        obj: JsonObject,
        kind: HueTargetKind,
        groupedById: Map<String, GroupedLightState>,
        lightsById: Map<String, HueTarget>,
        lightsByOwner: Map<String, List<HueTarget>>,
    ): HueTarget? {
        val services = obj["services"] as? JsonArray ?: return null
        val groupedId = services.mapNotNull { it as? JsonObject }
            .firstOrNull { (it["rtype"] as? JsonPrimitive)?.contentOrNull == "grouped_light" }
            ?.get("rid")?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?: return null
        val grouped = groupedById[groupedId] ?: return null
        val metadata = obj["metadata"] as? JsonObject
        val name = (metadata?.get("name") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: groupedId
        val children = (obj["children"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val memberLights = children.flatMap { child ->
            val rid = (child["rid"] as? JsonPrimitive)?.contentOrNull ?: return@flatMap emptyList()
            when ((child["rtype"] as? JsonPrimitive)?.contentOrNull) {
                "light" -> listOfNotNull(lightsById[rid])
                "device" -> lightsByOwner[rid].orEmpty()
                else -> emptyList()
            }
        }
        val supportsDimming = grouped.supportsDimming || memberLights.any { it.supportsDimming }
        val supportsColorTemp = grouped.supportsColorTemp || memberLights.any { it.supportsColorTemp }
        val supportsColor = grouped.supportsColor || memberLights.any { it.supportsColor }
        val mirekMin = memberLights.filter { it.supportsColorTemp }.minOfOrNull { it.mirekMin } ?: grouped.mirekMin
        val mirekMax = memberLights.filter { it.supportsColorTemp }.maxOfOrNull { it.mirekMax } ?: grouped.mirekMax
        return HueTarget(
            id = groupedId,
            type = "grouped_light",
            name = name,
            kind = kind,
            on = grouped.on,
            brightness = grouped.brightness ?: memberLights.firstOrNull { it.brightness != null }?.brightness,
            supportsDimming = supportsDimming,
            mirek = grouped.mirek ?: memberLights.firstOrNull { it.mirek != null }?.mirek,
            mirekMin = mirekMin,
            mirekMax = mirekMax,
            supportsColorTemp = supportsColorTemp,
            xy = grouped.xy ?: memberLights.firstOrNull { it.xy != null }?.xy,
            supportsColor = supportsColor,
        )
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun parseBridgeUrl(bridgeAddress: String): HttpUrl {
            val normalized = normalizeBridgeAddress(bridgeAddress)
                ?: throw IllegalArgumentException("Invalid Hue Bridge address.")
            return "https://$normalized".toHttpUrlOrNull()
                ?: throw IllegalArgumentException("Invalid Hue Bridge URL.")
        }

        private val hueTrustManager: X509TrustManager by lazy { createHueTrustManager() }

        @SuppressLint("CustomX509TrustManager")
        private fun createHueTrustManager(): X509TrustManager {
            val defaultTrustManager = runCatching {
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                    init(null as KeyStore?)
                }.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            }.getOrNull()

            return object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    throw CertificateException("Client certificates are not supported for Hue Bridge.")
                }

                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    if (chain.isNullOrEmpty()) throw CertificateException("Hue Bridge presented no certificate.")
                    if (authType.isNullOrBlank()) throw CertificateException("Missing TLS authType.")
                    val defaultAccepted = runCatching {
                        defaultTrustManager?.checkServerTrusted(chain, authType)
                    }.isSuccess
                    if (defaultAccepted) return

                    // Verify X.509 structural validity and internal chain signatures for local Hue Bridge.
                    for (i in chain.indices) {
                        chain[i].checkValidity()
                        if (i < chain.lastIndex) {
                            chain[i].verify(chain[i + 1].publicKey)
                        }
                    }
                    val last = chain.last()
                    if (last.subjectX500Principal == last.issuerX500Principal) {
                        last.verify(last.publicKey)
                    } else {
                        // Leaf issued by Hue Root CA when the bridge omits the root certificate in the chain.
                        val issuer = last.issuerX500Principal.name
                        val isHueCa = issuer.contains("Philips Hue", ignoreCase = true) ||
                            issuer.contains("Signify", ignoreCase = true) ||
                            issuer.contains("root-bridge", ignoreCase = true)
                        if (!isHueCa && chain.size == 1) {
                            throw CertificateException("Untrusted non-Hue intermediate certificate: $issuer")
                        }
                    }
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> =
                    defaultTrustManager?.acceptedIssuers ?: emptyArray()
            }
        }

        private val hueSslContext: SSLContext by lazy {
            SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(hueTrustManager), SecureRandom())
            }
        }

        private val baseHueClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .sslSocketFactory(hueSslContext.socketFactory, hueTrustManager)
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .callTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        }

        /**
         * Creates an isolated OkHttpClient specifically for communicating with a local Philips Hue Bridge
         * over HTTPS by LAN IP. Hue Bridges use a self-signed / Philips Hue Root CA certificate whose CN
         * is the Bridge ID rather than its DHCP IP. This client never affects EntiaApi's standard TLS client.
         */
        fun localBridgeClient(expectedHost: String): OkHttpClient {
            val normalizedExpected = expectedHost.removePrefix("[").removeSuffix("]")
            return baseHueClient.newBuilder()
                .hostnameVerifier(HostnameVerifier { hostname, session ->
                    hostname.removePrefix("[").removeSuffix("]")
                        .equals(normalizedExpected, ignoreCase = true) &&
                        runCatching { session.peerCertificates.isNotEmpty() }.getOrDefault(false)
                })
                .build()
        }

        suspend fun pair(
            bridgeAddress: String,
            deviceType: String = "entalt#android",
            client: OkHttpClient? = null,
        ): HuePairResult {
            val url = parseBridgeUrl(bridgeAddress).newBuilder().addPathSegment("api").build()
            val bridgeClient = client ?: localBridgeClient(url.host)
            val payload = buildJsonObject {
                put("devicetype", deviceType)
                put("generateclientkey", true)
            }
            val request = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            val calls = ConcurrentHashMap.newKeySet<Call>()
            val lock = Any()
            val (status, body) = execute(bridgeClient, calls, lock, { false }, request)
            if (status !in 200..299) {
                return HuePairResult.Error("Hue Bridge returned HTTP $status.")
            }
            val array = runCatching { Json.parseToJsonElement(body) as? JsonArray }.getOrNull()
                ?: return HuePairResult.Error("Hue Bridge returned an unexpected response.")
            for (element in array) {
                val obj = element as? JsonObject ?: continue
                val success = obj["success"] as? JsonObject
                if (success != null) {
                    val username = (success["username"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                        ?: continue
                    val clientKey = (success["clientkey"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                    return HuePairResult.Success(appKey = username, clientKey = clientKey)
                }
                val error = obj["error"] as? JsonObject
                if (error != null) {
                    val type = (error["type"] as? JsonPrimitive)?.intOrNull
                    if (type == 101) return HuePairResult.LinkButtonNotPressed
                    val desc = (error["description"] as? JsonPrimitive)?.contentOrNull ?: "Pairing failed."
                    return HuePairResult.Error(desc)
                }
            }
            return HuePairResult.Error("Hue Bridge pairing did not return a key.")
        }

        private suspend fun execute(
            client: OkHttpClient,
            calls: MutableSet<Call>,
            lock: Any,
            isClosed: () -> Boolean,
            request: Request,
        ): Pair<Int, String> = suspendCancellableCoroutine { continuation ->
            synchronized(lock) {
                if (isClosed()) {
                    continuation.resumeWithException(IOException("Hue session closed."))
                    return@suspendCancellableCoroutine
                }
                val call = client.newCall(request)
                calls += call
                continuation.invokeOnCancellation {
                    call.cancel()
                    calls -= call
                }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        calls -= call
                        if (continuation.isActive) {
                            continuation.resumeWithException(IOException("Cannot reach Hue Bridge on the local network."))
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            response.use {
                                val result = it.code to (it.body?.string().orEmpty())
                                if (continuation.isActive) continuation.resume(result)
                            }
                        } catch (_: IOException) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(IOException("Hue Bridge connection ended unexpectedly."))
                            }
                        } finally {
                            calls -= call
                        }
                    }
                })
            }
        }
    }
}

object HueDiscovery {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun discoverBridges(context: Context, timeoutMillis: Long = 2500L): List<DiscoveredBridge> = coroutineScope {
        val mdnsJob = async { discoverNsd(context, timeoutMillis) }
        val cloudJob = async { runCatching { discoverCloud() }.getOrDefault(emptyList()) }
        val combined = mdnsJob.await() + cloudJob.await()
        combined.groupBy { it.ip }.map { (ip, entries) ->
            DiscoveredBridge(
                ip = ip,
                id = entries.firstNotNullOfOrNull { it.id },
                source = entries.joinToString("+") { it.source }.ifBlank { "local" },
            )
        }
    }

    suspend fun discoverCloud(
        client: OkHttpClient = EntiaApi.defaultClient(),
        url: String = "https://discovery.meethue.com",
    ): List<DiscoveredBridge> = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(url).get().build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resume(emptyList())
            }

            override fun onResponse(call: Call, response: Response) {
                val bridges = try {
                    response.use {
                        if (!it.isSuccessful) emptyList()
                        else parseCloudDiscovery(it.body?.string().orEmpty())
                    }
                } catch (_: Exception) {
                    emptyList()
                }
                if (continuation.isActive) continuation.resume(bridges)
            }
        })
    }

    internal fun parseCloudDiscovery(body: String): List<DiscoveredBridge> {
        val array = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val rawIp = (obj["internalipaddress"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val ip = normalizeBridgeAddress(rawIp) ?: return@mapNotNull null
            val id = (obj["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            DiscoveredBridge(ip = ip, id = id, source = "cloud")
        }
    }

    suspend fun discoverNsd(context: Context, timeoutMillis: Long = 2500L): List<DiscoveredBridge> =
        withContext(Dispatchers.Main) {
            val manager = runCatching { context.getSystemService(NsdManager::class.java) }.getOrNull()
                ?: return@withContext emptyList()
            val found = ConcurrentHashMap<String, DiscoveredBridge>()
            val activeCallbacks = java.util.concurrent.CopyOnWriteArrayList<Any>()
            var discoveryListener: NsdManager.DiscoveryListener? = null

            withTimeoutOrNull(timeoutMillis) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    val listener = object : NsdManager.DiscoveryListener {
                        override fun onDiscoveryStarted(regType: String) = Unit
                        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                            resolveServiceInfo(manager, serviceInfo, activeCallbacks) { bridge ->
                                found[bridge.ip] = bridge
                            }
                        }
                        override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
                        override fun onDiscoveryStopped(serviceType: String) = Unit
                        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                            if (continuation.isActive) continuation.resume(Unit)
                        }
                        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                    }
                    discoveryListener = listener
                    val started = runCatching {
                        manager.discoverServices("_hue._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
                    }.isSuccess
                    if (!started) {
                        if (continuation.isActive) continuation.resume(Unit)
                        return@suspendCancellableCoroutine
                    }
                    continuation.invokeOnCancellation {
                        stopNsdDiscovery(manager, listener, activeCallbacks)
                    }
                }
            }
            discoveryListener?.let { stopNsdDiscovery(manager, it, activeCallbacks) }
            found.values.toList()
        }

    private fun stopNsdDiscovery(
        manager: NsdManager,
        listener: NsdManager.DiscoveryListener,
        callbacks: MutableList<Any>,
    ) {
        runCatching { manager.stopServiceDiscovery(listener) }
        if (Build.VERSION.SDK_INT >= 34) {
            callbacks.filterIsInstance<NsdManager.ServiceInfoCallback>().forEach { cb ->
                runCatching { manager.unregisterServiceInfoCallback(cb) }
            }
        }
        callbacks.clear()
    }

    private fun resolveServiceInfo(
        manager: NsdManager,
        serviceInfo: NsdServiceInfo,
        callbacks: MutableList<Any>,
        onFound: (DiscoveredBridge) -> Unit,
    ) {
        if (Build.VERSION.SDK_INT >= 34) {
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = Unit
                override fun onServiceUpdated(info: NsdServiceInfo) {
                    val ip = pickAddress(info.hostAddresses) ?: return
                    val bridgeId = info.attributes["bridgeid"]?.decodeToString()?.trim()?.takeIf { it.isNotEmpty() }
                    onFound(DiscoveredBridge(ip = ip, id = bridgeId, source = "mdns"))
                }
                override fun onServiceLost() = Unit
                override fun onServiceInfoCallbackUnregistered() = Unit
            }
            callbacks += callback
            runCatching {
                manager.registerServiceInfoCallback(serviceInfo, { it.run() }, callback)
            }
        } else {
            @Suppress("DEPRECATION")
            manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                override fun onServiceResolved(info: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    val host = info.host ?: return
                    val ip = pickAddress(listOf(host)) ?: return
                    val bridgeId = info.attributes["bridgeid"]?.decodeToString()?.trim()?.takeIf { it.isNotEmpty() }
                    onFound(DiscoveredBridge(ip = ip, id = bridgeId, source = "mdns"))
                }
            })
        }
    }

    private fun pickAddress(addresses: List<InetAddress>): String? {
        val preferred = addresses.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?: addresses.firstOrNull { !it.isLoopbackAddress }
            ?: return null
        return preferred.hostAddress?.substringBefore('%')?.let(::normalizeBridgeAddress)
    }
}
