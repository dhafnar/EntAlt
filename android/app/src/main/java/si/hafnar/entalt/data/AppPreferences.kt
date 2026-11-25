package si.hafnar.entalt.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import si.hafnar.entalt.domain.LightIcon

private val Context.entaltPreferences by preferencesDataStore("entalt")

class AppPreferences(context: Context) : PreferenceStore {
    private val store = context.entaltPreferences
    private val layout = stringPreferencesKey("layout")
    private val theme = stringPreferencesKey("theme")
    private val dynamic = booleanPreferencesKey("dynamic")
    private val hideCooling = booleanPreferencesKey("hide_cooling")
    private val showHeatingRooms = booleanPreferencesKey("show_heating_rooms")
    private val childLock = booleanPreferencesKey("child_lock")
    private val lastRoom = longPreferencesKey("last_room")
    private val startupRoom = longPreferencesKey("startup_room")
    private val speeds = stringPreferencesKey("speeds")
    private val accountId = stringPreferencesKey("account_owner")
    private val displayRoom = longPreferencesKey("display_room")
    private val disclaimerAccepted = booleanPreferencesKey("disclaimer_accepted")
    // Restore the clock-first display and its original local focus preference.
    private val displayFocus = stringPreferencesKey("display_focus")
    private val deviceNames = stringPreferencesKey("device_names")
    private val hiddenDevices = stringSetPreferencesKey("hidden_devices")
    private val lightIcons = stringPreferencesKey("light_icons")
    private val blindPresets = stringPreferencesKey("blind_presets")
    private val hueEnabled = booleanPreferencesKey("hue_enabled")
    private val hueBindings = stringPreferencesKey("hue_bindings")
    private val standaloneHueLights = stringPreferencesKey("standalone_hue_lights")
    private val language = stringPreferencesKey("language")
    private fun decodeMap(value: String?): Map<Long, String> = runCatching {
        (Json.parseToJsonElement(value ?: "{}") as? JsonObject).orEmpty().mapNotNull { (key, value) ->
            val id = key.toLongOrNull()
            val text = (value as? JsonPrimitive)?.contentOrNull
            if (id != null && text != null) id to text else null
        }.toMap()
    }.getOrDefault(emptyMap())
    private fun encodeMap(value: Map<Long, String>) = buildJsonObject {
        value.forEach { (id, text) -> put(id.toString(), text) }
    }.toString()
    private fun decodeBlindSlots(value: String?): Map<Long, BlindSlots> =
        decodeMap(value).mapNotNull { (id, encoded) ->
            val parts = encoded.split(',')
            val s1 = parts.getOrNull(0)?.toIntOrNull()?.takeIf { it in 0..100 }
            val s2 = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..100 }
            if (s1 != null || s2 != null) id to BlindSlots(s1, s2) else null
        }.toMap()
    private fun decodeHueTargets(array: JsonArray?): List<HueTargetRef> =
        array.orEmpty().mapNotNull { el ->
            val tObj = el as? JsonObject ?: return@mapNotNull null
            val tid = (tObj["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val ttype = (tObj["type"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it == "light" || it == "grouped_light" } ?: "light"
            val tname = (tObj["name"] as? JsonPrimitive)?.contentOrNull ?: ""
            HueTargetRef(tid, ttype, tname)
        }
    private fun JsonObjectBuilder.putHueTargets(targets: List<HueTargetRef>) {
        putJsonArray("targets") {
            targets.forEach { ref ->
                addJsonObject {
                    put("id", ref.id)
                    put("type", ref.type)
                    put("name", ref.name)
                }
            }
        }
    }
    private fun decodeHueBindings(value: String?): Map<Long, HueBinding> = runCatching {
        (Json.parseToJsonElement(value ?: "{}") as? JsonObject).orEmpty().mapNotNull { (key, item) ->
            val id = key.toLongOrNull() ?: return@mapNotNull null
            val obj = item as? JsonObject ?: return@mapNotNull null
            val targetsArray = obj["targets"] as? JsonArray
            val targets = if (targetsArray != null) {
                decodeHueTargets(targetsArray)
            } else {
                val targetId = (obj["targetId"] as? JsonPrimitive)?.contentOrNull ?: ""
                val targetType = (obj["targetType"] as? JsonPrimitive)?.contentOrNull
                    ?.takeIf { it == "light" || it == "grouped_light" } ?: "light"
                val targetName = (obj["targetName"] as? JsonPrimitive)?.contentOrNull ?: ""
                if (targetId.isBlank()) emptyList() else listOf(HueTargetRef(targetId, targetType, targetName))
            }
            id to HueBinding(targets)
        }.toMap()
    }.getOrDefault(emptyMap())
    private fun encodeHueBindings(value: Map<Long, HueBinding>) = buildJsonObject {
        value.forEach { (id, binding) ->
            putJsonObject(id.toString()) {
                put("targetId", binding.targetId)
                put("targetType", binding.targetType)
                put("targetName", binding.targetName)
                putHueTargets(binding.targets)
            }
        }
    }.toString()
    private fun decodeStandaloneHueLights(value: String?): List<StandaloneHueLight> = runCatching {
        (Json.parseToJsonElement(value ?: "[]") as? JsonArray).orEmpty().mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val id = (obj["id"] as? JsonPrimitive)?.longOrNull?.takeIf { it < 0 } ?: return@mapNotNull null
            val roomId = (obj["roomId"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            val name = (obj["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val iconName = (obj["icon"] as? JsonPrimitive)?.contentOrNull
            val icon = LightIcon.entries.firstOrNull { it.name == iconName } ?: LightIcon.Bulb
            val targets = decodeHueTargets(obj["targets"] as? JsonArray)
            val entiaDeviceId = (obj["entiaDeviceId"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
            StandaloneHueLight(
                id = id,
                roomId = roomId,
                name = name,
                icon = icon,
                targets = targets,
                entiaDeviceId = entiaDeviceId,
            )
        }
    }.getOrDefault(emptyList())
    private fun encodeStandaloneHueLights(value: List<StandaloneHueLight>) = buildJsonArray {
        value.forEach { light ->
            addJsonObject {
                put("id", light.id)
                put("roomId", light.roomId)
                put("name", light.name)
                put("icon", light.icon.name)
                putHueTargets(light.targets)
                light.entiaDeviceId?.let { put("entiaDeviceId", it) }
            }
        }
    }.toString()
    override suspend fun load(): Preferences {
        val p = store.data.first()
        return Preferences(p[layout] ?: "auto", p[theme] ?: "dark", p[dynamic] ?: true, p[hideCooling] ?: false,
            p[showHeatingRooms] ?: true, p[childLock] ?: false,
            p[lastRoom], p[startupRoom], p[speeds].orEmpty().split(';').mapNotNull { entry ->
                val pair = entry.split(':')
                val id = pair.firstOrNull()?.toLongOrNull()
                val value = pair.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..3 }
                if (id != null && value != null) id to value else null
            }.toMap(), p[accountId], p[displayRoom], p[disclaimerAccepted] ?: false,
            p[displayFocus]?.takeIf { it in setOf("clock", "temperature") } ?: "clock",
            decodeMap(p[deviceNames]), p[hiddenDevices].orEmpty().mapNotNull { it.toLongOrNull() }.toSet(),
            decodeMap(p[lightIcons]).mapNotNull { (id, value) -> LightIcon.entries.firstOrNull { it.name == value }?.let { id to it } }.toMap(),
            decodeBlindSlots(p[blindPresets]),
            p[hueEnabled] ?: false,
            decodeHueBindings(p[hueBindings]),
            decodeStandaloneHueLights(p[standaloneHueLights]),
            p[language]?.takeIf { it in setOf("system", "en", "sl") } ?: "system")
    }
    override suspend fun save(preferences: Preferences) {
        store.edit { p ->
            p[layout] = preferences.layout; p[theme] = preferences.theme; p[dynamic] = preferences.dynamicColor
            p[hideCooling] = preferences.hideCooling
            p[showHeatingRooms] = preferences.showHeatingRooms
            p[childLock] = preferences.childLock
            preferences.lastRoom?.let { p[lastRoom] = it } ?: p.remove(lastRoom)
            preferences.startupRoom?.let { p[startupRoom] = it } ?: p.remove(startupRoom)
            p[speeds] = preferences.speeds.entries.joinToString(";") { "${it.key}:${it.value}" }
            preferences.accountId?.let { p[accountId] = it } ?: p.remove(accountId)
            preferences.displayRoom?.let { p[displayRoom] = it } ?: p.remove(displayRoom)
            p[disclaimerAccepted] = preferences.disclaimerAccepted
            p[displayFocus] = preferences.displayFocus
            p[deviceNames] = encodeMap(preferences.deviceNames)
            p[hiddenDevices] = preferences.hiddenDevices.map { it.toString() }.toSet()
            p[lightIcons] = encodeMap(preferences.lightIcons.mapValues { it.value.name })
            p[blindPresets] = encodeMap(preferences.blindPresets.mapValues { (_, v) -> "${v.slot1 ?: ""},${v.slot2 ?: ""}" })
            p[hueEnabled] = preferences.hueEnabled
            p[hueBindings] = encodeHueBindings(preferences.hueBindings)
            p[standaloneHueLights] = encodeStandaloneHueLights(preferences.standaloneHueLights)
            p[language] = preferences.language
        }
    }
    override suspend fun clearAccount() { store.edit {
        it.remove(lastRoom); it.remove(startupRoom); it.remove(displayRoom); it.remove(speeds); it.remove(accountId)
        it.remove(deviceNames); it.remove(hiddenDevices); it.remove(lightIcons); it.remove(blindPresets)
        it.remove(hueBindings); it.remove(standaloneHueLights)
    } }
}
