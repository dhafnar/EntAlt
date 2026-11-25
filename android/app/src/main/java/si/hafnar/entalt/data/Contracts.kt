package si.hafnar.entalt.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import si.hafnar.entalt.domain.LightIcon

interface AccountStore {
    fun save(credentials: Credentials)
    fun load(): Credentials?
    fun clear()
}

data class HueCredentials(
    val bridgeIp: String,
    val appKey: String,
    val clientKey: String? = null,
    val bridgeId: String? = null,
) {
    override fun toString() = "HueCredentials(bridgeIp=$bridgeIp, appKey=[redacted])"
}

interface HueStore {
    fun saveHue(credentials: HueCredentials)
    fun loadHue(): HueCredentials?
    fun clearHue()
}

data class HueTargetRef(
    val id: String,
    val type: String = "light",
    val name: String = "",
)

data class HueBinding(
    val targets: List<HueTargetRef> = emptyList(),
) {
    constructor(targetId: String, targetType: String = "light", targetName: String = "") : this(
        if (targetId.isBlank()) emptyList() else listOf(HueTargetRef(targetId, targetType, targetName))
    )
    val targetId: String get() = targets.firstOrNull()?.id.orEmpty()
    val targetType: String get() = targets.firstOrNull()?.type ?: "light"
    val targetName: String get() = targets.joinToString(", ") { it.name.ifBlank { it.id } }

    fun toggleTarget(id: String, type: String, name: String): HueBinding {
        val exists = targets.any { it.id == id && it.type == type }
        val updated = if (exists) {
            targets.filterNot { it.id == id && it.type == type }
        } else {
            targets + HueTargetRef(id, type, name)
        }
        return copy(targets = updated)
    }

    fun hasTarget(id: String, type: String): Boolean =
        targets.any { it.id == id && it.type == type }
}

enum class HueTargetKind { Light, Room, Zone }

data class HueTarget(
    val id: String,
    val type: String,
    val name: String,
    val kind: HueTargetKind = if (type == "grouped_light") HueTargetKind.Room else HueTargetKind.Light,
    val on: Boolean = false,
    val brightness: Int? = null,
    val supportsDimming: Boolean = false,
    val mirek: Int? = null,
    val mirekMin: Int = 153,
    val mirekMax: Int = 500,
    val supportsColorTemp: Boolean = false,
    val xy: Pair<Double, Double>? = null,
    val supportsColor: Boolean = false,
    val ownerDeviceId: String? = null,
)

data class DiscoveredBridge(
    val ip: String,
    val id: String? = null,
    val source: String = "",
)

sealed interface HuePairResult {
    data class Success(val appKey: String, val clientKey: String? = null) : HuePairResult
    data object LinkButtonNotPressed : HuePairResult
    data class Error(val message: String) : HuePairResult
}

interface HueGateway {
    suspend fun fetchTargets(): List<HueTarget>
    suspend fun setPower(targetType: String, id: String, on: Boolean)
    suspend fun setBrightness(targetType: String, id: String, brightness: Int)
    suspend fun setColorTemp(targetType: String, id: String, mirek: Int)
    suspend fun setColorXy(targetType: String, id: String, x: Double, y: Double)
    fun close()
}

data class BlindSlots(val slot1: Int? = null, val slot2: Int? = null)

data class StandaloneHueLight(
    val id: Long,
    val roomId: Long,
    val name: String,
    val icon: LightIcon = LightIcon.Bulb,
    val targets: List<HueTargetRef> = emptyList(),
    val entiaDeviceId: Long? = null,
)

data class Preferences(
    val layout: String = "auto",
    val theme: String = "dark",
    val dynamicColor: Boolean = true,
    val hideCooling: Boolean = false,
    val showHeatingRooms: Boolean = true,
    val childLock: Boolean = false,
    val lastRoom: Long? = null,
    val startupRoom: Long? = null,
    val speeds: Map<Long, Int> = emptyMap(),
    val accountId: String? = null,
    val displayRoom: Long? = null,
    val disclaimerAccepted: Boolean = false,
    val displayFocus: String = "clock",
    val deviceNames: Map<Long, String> = emptyMap(),
    val hiddenDevices: Set<Long> = emptySet(),
    val lightIcons: Map<Long, LightIcon> = emptyMap(),
    val blindPresets: Map<Long, BlindSlots> = emptyMap(),
    val hueEnabled: Boolean = false,
    val hueBindings: Map<Long, HueBinding> = emptyMap(),
    val standaloneHueLights: List<StandaloneHueLight> = emptyList(),
    val language: String = "system",
) {
    fun hueBindingFor(deviceId: Long): HueBinding? =
        hueBindings[deviceId] ?: standaloneHueLights.firstOrNull { it.id == deviceId }?.let { HueBinding(it.targets) }

    fun linkedEntiaSwitchId(deviceId: Long): Long? {
        if (deviceId >= 0) return if (deviceId in hueBindings) deviceId else null
        val standalone = standaloneHueLights.firstOrNull { it.id == deviceId } ?: return null
        if (standalone.entiaDeviceId != null) return standalone.entiaDeviceId
        val targetKeys = standalone.targets.asSequence().map { "${it.type}:${it.id}" }.toSet()
        if (targetKeys.isEmpty()) return null
        return hueBindings.entries.firstOrNull { (_, binding) ->
            binding.targets.any { "${it.type}:${it.id}" in targetKeys }
        }?.key
    }

    fun allHueTargetsOnEntiaSwitch(entiaId: Long): List<HueTargetRef> {
        val direct = hueBindings[entiaId]?.targets.orEmpty()
        val directKeys = direct.asSequence().map { "${it.type}:${it.id}" }.toSet()
        val fromStandalone = standaloneHueLights
            .filter { light ->
                light.entiaDeviceId == entiaId ||
                    (light.entiaDeviceId == null && light.targets.any { "${it.type}:${it.id}" in directKeys })
            }
            .flatMap { it.targets }
        return (direct + fromStandalone).distinctBy { "${it.type}:${it.id}" }
    }
}

/** Persist only an opaque owner tag, never a username/password in preferences. */
fun Preferences.forAccount(username: String): Preferences {
    val owner = java.security.MessageDigest.getInstance("SHA-256")
        .digest(username.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    return if (accountId == owner) this else copy(accountId = owner,
        lastRoom = null, startupRoom = null, displayRoom = null, speeds = emptyMap(),
        deviceNames = emptyMap(), hiddenDevices = emptySet(), lightIcons = emptyMap(),
        blindPresets = emptyMap(), hueBindings = emptyMap(), standaloneHueLights = emptyList())
}

fun Preferences.forDemo(): Preferences = if (accountId == "demo") this else copy(accountId = "demo",
    lastRoom = null, startupRoom = null, displayRoom = null, speeds = emptyMap(),
    deviceNames = emptyMap(), hiddenDevices = emptySet(), lightIcons = emptyMap(),
    blindPresets = emptyMap(), hueBindings = emptyMap(), standaloneHueLights = emptyList())

interface PreferenceStore {
    suspend fun load(): Preferences
    suspend fun save(preferences: Preferences)
    suspend fun clearAccount()
}

interface EntiaGateway {
    suspend fun token(): String
    suspend fun renew(rejected: String?): String
    fun clearToken()
    suspend fun validate(): JsonObject
    suspend fun flat(): JsonObject
    suspend fun devices(): JsonElement
    suspend fun set(device: Long, attribute: Int, value: Int)
    /** Suspends for one connection; calls onOpen once and never sends a subscription. */
    suspend fun listen(onOpen: () -> Unit, onEvent: (String) -> Unit)
    fun close()
}
