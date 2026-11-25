package si.hafnar.entalt.widget

import android.content.Context
import kotlinx.serialization.json.*
import si.hafnar.entalt.data.HueState
import si.hafnar.entalt.data.Preferences
import si.hafnar.entalt.data.resolveLightState
import si.hafnar.entalt.domain.Attr
import si.hafnar.entalt.domain.Home
import si.hafnar.entalt.domain.LightIcon

enum class WidgetActionType {
    LightToggle,
    BlindSet,
    ClimateMode,
    ClimateTemp,
    VentilationSpeed,
}

data class WidgetSlot(
    val deviceId: Long,
    val label: String,
    val action: WidgetActionType,
    val value: Int = 0,
)

data class WidgetConfig(
    val appWidgetId: Int,
    val items: List<WidgetSlot> = emptyList(),
)

data class CachedDeviceState(
    val name: String,
    val lightOn: Boolean? = null,
    val mainsOn: Boolean? = null,
    val hueOn: Boolean? = null,
    val position: Int? = null,
    val mode: Int? = null,
    val target: Int? = null,
    val hrvSpeed: Int? = null,
    val lightIcon: LightIcon = LightIcon.Bulb,
)

class WidgetStore(context: Context) {
    private val prefs = context.getSharedPreferences("entalt_widgets", Context.MODE_PRIVATE)

    fun loadConfig(appWidgetId: Int): WidgetConfig {
        val raw = prefs.getString(configKey(appWidgetId), null) ?: return WidgetConfig(appWidgetId)
        return runCatching {
            val obj = Json.parseToJsonElement(raw) as? JsonObject ?: return WidgetConfig(appWidgetId)
            val itemsArray = obj["items"] as? JsonArray
            val items = if (itemsArray != null) {
                itemsArray.mapNotNull { parseSlot(it as? JsonObject) }
            } else {
                // Migrate legacy grid-based "slots" map into ordered list.
                val slotsObj = obj["slots"] as? JsonObject ?: JsonObject(emptyMap())
                slotsObj.entries.mapNotNull { (k, v) ->
                    val index = k.toIntOrNull() ?: return@mapNotNull null
                    val slot = parseSlot(v as? JsonObject) ?: return@mapNotNull null
                    index to slot
                }.sortedBy { it.first }.map { it.second }
            }
            WidgetConfig(appWidgetId, items)
        }.getOrDefault(WidgetConfig(appWidgetId))
    }

    private fun parseSlot(item: JsonObject?): WidgetSlot? {
        if (item == null) return null
        val deviceId = (item["deviceId"] as? JsonPrimitive)?.longOrNull ?: return null
        val label = (item["label"] as? JsonPrimitive)?.contentOrNull ?: return null
        val actionName = (item["action"] as? JsonPrimitive)?.contentOrNull ?: return null
        val action = WidgetActionType.entries.firstOrNull { it.name == actionName } ?: return null
        val value = (item["value"] as? JsonPrimitive)?.intOrNull ?: 0
        return WidgetSlot(deviceId, label, action, value)
    }

    fun saveConfig(config: WidgetConfig) {
        val json = buildJsonObject {
            putJsonArray("items") {
                config.items.forEach { slot ->
                    addJsonObject {
                        put("deviceId", slot.deviceId)
                        put("label", slot.label)
                        put("action", slot.action.name)
                        put("value", slot.value)
                    }
                }
            }
        }.toString()
        prefs.edit().putString(configKey(config.appWidgetId), json).apply()
    }

    fun deleteConfig(appWidgetId: Int) {
        prefs.edit().remove(configKey(appWidgetId)).apply()
    }

    fun loadCache(): Map<Long, CachedDeviceState> = synchronized(cacheLock) {
        val raw = prefs.getString(CACHE_KEY, null) ?: return@synchronized emptyMap()
        return@synchronized runCatching {
            val obj = Json.parseToJsonElement(raw) as? JsonObject ?: return@synchronized emptyMap()
            obj.entries.mapNotNull { (k, v) ->
                val id = k.toLongOrNull() ?: return@mapNotNull null
                val item = v as? JsonObject ?: return@mapNotNull null
                val name = (item["name"] as? JsonPrimitive)?.contentOrNull ?: ""
                val light = (item["light"] as? JsonPrimitive)?.booleanOrNull
                val mains = (item["mains"] as? JsonPrimitive)?.booleanOrNull
                val hue = (item["hue"] as? JsonPrimitive)?.booleanOrNull
                val pos = (item["pos"] as? JsonPrimitive)?.intOrNull
                val mode = (item["mode"] as? JsonPrimitive)?.intOrNull
                val target = (item["target"] as? JsonPrimitive)?.intOrNull
                val speed = (item["speed"] as? JsonPrimitive)?.intOrNull
                val iconName = (item["icon"] as? JsonPrimitive)?.contentOrNull
                val icon = LightIcon.entries.firstOrNull { it.name == iconName } ?: LightIcon.Bulb
                id to CachedDeviceState(name, light, mains, hue, pos, mode, target, speed, icon)
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    fun saveCacheFromHome(
        home: Home,
        preferences: Preferences = Preferences(),
        hue: HueState = HueState(),
    ) = synchronized(cacheLock) {
        if (home.devices.isEmpty()) return@synchronized
        val existing = loadCache().toMutableMap()
        home.devices.values.forEach { device ->
            val resolved = resolveLightState(home, preferences, hue, device)
            val mains = if (device.isLight) resolved.mainsOn else null
            val binding = resolved.binding
            val target = resolved.target
            val hueOn = if (binding != null) target?.on ?: existing[device.id]?.hueOn else null
            val effectiveLight = when {
                device.isStandaloneHue -> (mains == true) && (hueOn == true)
                binding != null && hueOn != null -> mains == true && hueOn
                else -> mains
            }
            existing[device.id] = CachedDeviceState(
                name = device.name,
                lightOn = effectiveLight,
                mainsOn = mains,
                hueOn = hueOn,
                position = device.value(Attr.POSITION)?.takeIf { it in 0..100 },
                mode = device.value(Attr.MODE)?.takeIf { it in 0..2 },
                target = device.value(Attr.TARGET)?.takeIf { it in 20..60 },
                hrvSpeed = device.value(Attr.HRV_SPEED)?.takeIf { it in 0..3 },
                lightIcon = device.lightIcon,
            )
        }
        writeCache(existing)
    }

    fun loadLanguage(): String = prefs.getString(LANGUAGE_KEY, "system") ?: "system"

    fun saveLanguage(language: String) {
        if (loadLanguage() != language) {
            prefs.edit().putString(LANGUAGE_KEY, language).apply()
        }
    }

    fun updateCachedDevice(deviceId: Long, transform: (CachedDeviceState) -> CachedDeviceState) = synchronized(cacheLock) {
        val existing = loadCache().toMutableMap()
        val current = existing[deviceId] ?: CachedDeviceState("")
        existing[deviceId] = transform(current)
        writeCache(existing)
    }

    fun updateCachedDevices(transform: (MutableMap<Long, CachedDeviceState>) -> Unit) = synchronized(cacheLock) {
        val existing = loadCache().toMutableMap()
        transform(existing)
        writeCache(existing)
    }

    fun clearCache() = synchronized(cacheLock) {
        prefs.edit().remove(CACHE_KEY).apply()
    }

    private fun writeCache(cache: Map<Long, CachedDeviceState>) {
        val json = buildJsonObject {
            cache.forEach { (id, state) ->
                putJsonObject(id.toString()) {
                    put("name", state.name)
                    state.lightOn?.let { put("light", it) }
                    state.mainsOn?.let { put("mains", it) }
                    state.hueOn?.let { put("hue", it) }
                    state.position?.let { put("pos", it) }
                    state.mode?.let { put("mode", it) }
                    state.target?.let { put("target", it) }
                    state.hrvSpeed?.let { put("speed", it) }
                    put("icon", state.lightIcon.name)
                }
            }
        }.toString()
        prefs.edit().putString(CACHE_KEY, json).apply()
    }

    private fun configKey(appWidgetId: Int) = "widget_$appWidgetId"

    companion object {
        private const val CACHE_KEY = "device_cache"
        private const val LANGUAGE_KEY = "language"
        private val cacheLock = Any()
    }
}
