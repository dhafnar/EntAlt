package si.hafnar.entalt.domain

import kotlinx.serialization.json.*
import si.hafnar.entalt.data.StandaloneHueLight
import kotlin.math.round

object Attr {
    const val LIGHT = 401
    const val POSITION = 601
    const val MOVING = 603
    const val TEMPERATURE = 801
    const val INSIDE = 1103
    const val OUTSIDE = 1104
    const val TARGET = 1105
    const val MODE = 1107
    const val VALVE = 2101
    const val HRV_MODE = 3701
    const val HRV_SPEED = 3702
}

data class Reading(val raw: JsonElement, val timestamp: Long? = null) {
    val value: Int? get() = try {
        (raw as? JsonPrimitive)?.contentOrNull?.toBigDecimalOrNull()?.intValueExact()
    } catch (_: ArithmeticException) { null }
}
enum class LightIcon {
    Bulb, Ceiling, Pendant, TableLamp, FloorLamp, WallLamp, Chandelier, Spotlight, Strip, Downlight, Nightlight, Outdoor
}

data class Device(
    val id: Long,
    val name: String,
    val roomId: Long?,
    val attributes: Map<Int, Reading> = emptyMap(),
    val available: Boolean = true,
    val telemetryRoomId: Long? = null,
    val serverName: String = name,
    val hidden: Boolean = false,
    val lightIcon: LightIcon = LightIcon.Bulb,
    val isStandaloneHue: Boolean = false,
) {
    fun value(attribute: Int): Int? = attributes[attribute]?.value
    val isLight get() = Attr.LIGHT in attributes
    val isBlind get() = Attr.POSITION in attributes
    val isClimate get() = Attr.INSIDE in attributes && Attr.TARGET in attributes
    val isVentilation get() = Attr.HRV_SPEED in attributes
    val isSensor get() = Attr.TEMPERATURE in attributes
    val isOutsideSensor get() = isOutsideOnlyAttributes(attributes)
    val supported get() = isLight || isBlind || isClimate || isVentilation || isSensor || Attr.INSIDE in attributes || Attr.OUTSIDE in attributes
    fun temperature(attribute: Int): Double? = value(attribute)?.div(2.0)

    /** Only cards that can display a supported reading or offer a supported action. */
    val visibleControlKinds: List<Int> get() {
        if (!available || hidden) return emptyList()
        return buildList {
            if (isClimate && (temperature(Attr.INSIDE) != null || value(Attr.TARGET) in 20..60 || value(Attr.MODE) in 0..2)) add(Attr.TARGET)
            if (isLight && value(Attr.LIGHT) in 0..1) add(Attr.LIGHT)
            if (isBlind && (value(Attr.POSITION) in 0..100 || value(Attr.MOVING) == 1)) add(Attr.POSITION)
            if (isVentilation && (value(Attr.HRV_SPEED) in 0..3 || value(Attr.HRV_MODE) in 0..1)) add(Attr.HRV_SPEED)
            if (isSensor && temperature(Attr.TEMPERATURE) != null) add(Attr.TEMPERATURE)
            if (!isClimate && temperature(Attr.INSIDE) != null) add(Attr.INSIDE)
            if (!isClimate && temperature(Attr.OUTSIDE) != null) add(Attr.OUTSIDE)
        }
    }
    val roomControlKinds get() = visibleControlKinds.filter { it != Attr.HRV_SPEED }
}
data class Room(val id: Long, val name: String, val floor: String, val deviceIds: List<Long>)
data class Home(
    val rooms: List<Room> = emptyList(),
    val devices: Map<Long, Device> = emptyMap(),
    val controllerConnected: Boolean? = null,
    val timezone: String? = null,
    val flatId: Long? = null,
    val syncedAt: Long = 0,
) {
    fun devicesIn(roomId: Long): List<Device> = devices.values.filter { it.roomId == roomId }
    fun visibleDevicesIn(roomId: Long): List<Device> = devicesIn(roomId).filter { it.roomControlKinds.isNotEmpty() }
    val ventilationUnits get() = devices.values.filter { Attr.HRV_SPEED in it.visibleControlKinds }
    val visibleRooms: List<Room> get() {
        val roomIds = devices.values.filter { it.roomControlKinds.isNotEmpty() }.mapNotNull { it.roomId }.toSet()
        return rooms.filter { it.id in roomIds }
    }
    fun visibleRoomId(id: Long?): Long? = id?.takeIf { candidate -> visibleRooms.any { it.id == candidate } }
    fun startupRoom(preferred: Long?, last: Long?): Long? = visibleRoomId(preferred) ?: visibleRoomId(last)
    fun withLocalOverrides(
        names: Map<Long, String>,
        hidden: Set<Long>,
        icons: Map<Long, LightIcon>,
        standaloneHue: List<StandaloneHueLight> = emptyList(),
        hueEnabled: Boolean = false,
    ): Home {
        val realDevices = devices.filterValues { !it.isStandaloneHue }
        val updatedRealDevices = realDevices.mapValues { (id, device) ->
            device.copy(
                name = names[id] ?: device.serverName,
                hidden = id in hidden,
                lightIcon = icons[id] ?: LightIcon.Bulb,
            )
        }
        val realIds = realDevices.keys
        val validRoomIds = rooms.asSequence().map { it.id }.filter { it != Long.MIN_VALUE }.toSet()
        val activeStandalone = if (hueEnabled) {
            standaloneHue.filter { it.id < 0 && it.id !in realIds && it.roomId in validRoomIds }
        } else {
            emptyList()
        }
        val standaloneDevices = activeStandalone.associate { item ->
            item.id to Device(
                id = item.id,
                name = names[item.id] ?: item.name,
                roomId = item.roomId,
                attributes = mapOf(Attr.LIGHT to Reading(JsonPrimitive(1))),
                available = true,
                telemetryRoomId = item.roomId,
                serverName = item.name,
                hidden = item.id in hidden,
                lightIcon = icons[item.id] ?: item.icon,
                isStandaloneHue = true,
            )
        }
        val standaloneIdsByRoom = activeStandalone.groupBy({ it.roomId }, { it.id })
        val updatedRooms = rooms.map { room ->
            val baseIds = room.deviceIds.filter { it in realIds }
            val extraIds = standaloneIdsByRoom[room.id].orEmpty()
            room.copy(deviceIds = baseIds + extraIds)
        }
        return copy(rooms = updatedRooms, devices = updatedRealDevices + standaloneDevices)
    }
    fun indoorTemperature(roomId: Long): Double? {
        val devices = devicesIn(roomId).filter { it.available && !it.hidden }
        return devices.firstNotNullOfOrNull { it.temperature(Attr.INSIDE) }
            ?: devices.firstNotNullOfOrNull { it.temperature(Attr.TEMPERATURE) }
    }
    val displayRooms get() = visibleRooms.filter { indoorTemperature(it.id) != null }
    val heatingIndicatorRooms: List<Room>
        get() = visibleRooms.filter { room ->
            devicesIn(room.id).any { it.available && !it.hidden && (it.isClimate || Attr.VALVE in it.attributes) }
        }
    fun isRoomHeating(roomId: Long): Boolean {
        val roomDevices = devicesIn(roomId).filter { it.available && !it.hidden }
        val climateDevices = roomDevices.filter { it.isClimate }
        if (climateDevices.isNotEmpty()) {
            return climateDevices.any { activity(it).startsWith("Heating") }
        }
        return roomDevices.any { Attr.VALVE in it.attributes && it.value(Attr.VALVE) == 1 }
    }
    fun displayRoom(preferred: Long?): Room? = displayRooms.firstOrNull { it.id == preferred }
        ?: displayRooms.firstOrNull {
            val name = it.name.lowercase(java.util.Locale.ROOT)
            "living room" in name || "dnevna" in name
        } ?: displayRooms.firstOrNull()
    fun outdoorTemperature(roomId: Long?): Double? {
        val available = devices.values.filter { it.available && !it.hidden }
        if (roomId != null) available.firstNotNullOfOrNull {
            if (it.roomId == roomId) it.temperature(Attr.OUTSIDE) else null
        }?.let { return it }
        return available.mapNotNull { it.temperature(Attr.OUTSIDE) }.distinct().singleOrNull()
    }
    fun activity(device: Device): String {
        val mode = device.value(Attr.MODE) ?: return "Unknown"
        if (mode == 0) return "Off"
        if (mode !in 1..2) return "Unknown"
        val valves = devices.values.filter {
            device.telemetryRoomId != null && it.telemetryRoomId == device.telemetryRoomId && Attr.VALVE in it.attributes
        }
        if (valves.size == 1) return when (valves.single().value(Attr.VALVE)) {
            0 -> "Idle"
            1 -> if (mode == 2) "Heating" else "Cooling"
            else -> "Unknown"
        }
        if (valves.size > 1) return "Unknown"
        val inside = device.value(Attr.INSIDE) ?: return "Unknown"
        val target = device.value(Attr.TARGET) ?: return "Unknown"
        val active = if (mode == 2) inside < target else inside > target
        return if (active) "${if (mode == 2) "Heating" else "Cooling"} · estimated" else "Idle · estimated"
    }
}

private fun JsonElement?.objectOrNull() = this as? JsonObject
private fun JsonElement?.arrayOrEmpty() = this as? JsonArray ?: JsonArray(emptyList())
private fun JsonElement?.text() = (this as? JsonPrimitive)?.contentOrNull
private fun JsonElement?.id(): Long? = try {
    text()?.toBigDecimalOrNull()?.longValueExact()
} catch (_: ArithmeticException) { null }

/** Wire contract is based on the working integration. Additional fields are ignored. */
fun parseHome(flat: JsonObject, state: JsonElement, text: TextCatalog = EnglishText, decode: (String) -> String = { it }): Home {
    val definition = flat["flat"].objectOrNull() ?: error("The home definition is missing.")
    val rooms = mutableListOf<Room>()
    val labels = mutableMapOf<Long, String>()
    val membership = mutableMapOf<Long, Long>()
    val conflicts = mutableSetOf<Long>()
    definition["floors"].arrayOrEmpty().forEachIndexed { floorIndex, floorElement ->
        val floor = floorElement.objectOrNull() ?: return@forEachIndexed
        val floorLabel = decode(floor["label"].text().orEmpty()).ifBlank { text(TextKey.FloorFallback, (floorIndex + 1).toLong()) }
        for (roomElement in floor["rooms"].arrayOrEmpty()) {
            val room = roomElement.objectOrNull() ?: continue
            val roomId = room["id"].id() ?: continue
            val ids = mutableListOf<Long>()
            for (deviceElement in room["devices"].arrayOrEmpty()) {
                    val device = deviceElement.objectOrNull() ?: continue
                    val deviceId = device["id"].id() ?: continue
                    ids += deviceId
                    decode(device["label"].text().orEmpty()).trim().takeIf { it.isNotEmpty() }?.let { labels[deviceId] = it }
                    if (membership[deviceId]?.let { it != roomId } == true) conflicts += deviceId
                    membership[deviceId] = roomId
                }
            rooms += Room(roomId, decode(room["label"].text().orEmpty()).ifBlank { text(TextKey.RoomFallback, roomId) }, floorLabel, ids)
        }
    }
    val envelope = state.objectOrNull()
    val states = when (state) {
        is JsonArray -> state
        is JsonObject -> state["devices"] as? JsonArray ?: error("The device response is missing its devices list.")
        else -> error("The device response is invalid.")
    }
    val devices = linkedMapOf<Long, Device>()
    for (element in states) {
        val dto = element.objectOrNull() ?: continue
        val id = dto["id"].id() ?: continue
        val telemetryRoom = dto["room_id"].id()
        val room = if (id in conflicts) null else membership[id]
            ?: telemetryRoom?.takeIf { candidate -> rooms.any { it.id == candidate } }
        val attributes = buildMap {
            for (attribute in dto["attributes"].arrayOrEmpty()) {
                val item = attribute.objectOrNull() ?: continue
                val attributeId = item["id"].id()?.takeIf { it in 0..Int.MAX_VALUE }?.toInt() ?: continue
                put(attributeId, Reading(item["value"] ?: JsonNull, item["timestamp"].id()))
            }
        }
        devices[id] = Device(id, defaultDeviceName(id, labels[id], attributes, text), room, attributes, telemetryRoomId = telemetryRoom)
    }
    for ((id, roomId) in membership) {
        if (id !in devices) devices[id] = Device(id, labels[id] ?: text(TextKey.DeviceFallback, id), roomId.takeUnless { id in conflicts }, available = false)
    }
    if (devices.values.any { it.roomId == null }) {
        val unassignedId = Long.MIN_VALUE
        rooms += Room(unassignedId, text(TextKey.UnassignedDevices), text(TextKey.Home), devices.values.filter { it.roomId == null }.map { it.id })
        devices.replaceAll { _, device -> if (device.roomId == null) device.copy(roomId = unassignedId) else device }
    }
    return Home(
        rooms, devices,
        envelope?.get("connected")?.text()?.let { when (it) { "1", "true" -> true; "0", "false" -> false; else -> null } },
        envelope?.get("timezone").text(),
        definition["id"].id() ?: envelope?.get("id").id(),
        System.currentTimeMillis(),
    )
}

private fun isOutsideOnlyAttributes(attributes: Map<Int, Reading>): Boolean =
    Attr.OUTSIDE in attributes &&
        Attr.TARGET !in attributes &&
        Attr.INSIDE !in attributes &&
        Attr.TEMPERATURE !in attributes &&
        Attr.LIGHT !in attributes &&
        Attr.POSITION !in attributes &&
        Attr.HRV_SPEED !in attributes

private val explicitOutsideLabels = setOf(
    "outside", "outdoors", "outdoor", "outside sensor", "outdoor sensor",
    "outside temperature", "outdoor temperature", "outside temp", "outdoor temp",
    "zunaj", "zunanja", "zunanji", "zunanje", "zunanji senzor", "zunanje tipalo",
    "zunanja temperatura", "zunanja temp", "tipalo zunaj", "senzor zunaj",
    "temperatura zunaj"
)

private val genericOutsideDeviceLabels = explicitOutsideLabels + setOf(
    "sensor", "temperature", "temp", "tipalo", "senzor", "temperatura", "1104", "hmi"
)

private val deviceFallbackPattern = Regex("^(device|naprava)\\s+\\d+$")

private fun normalizeLabel(label: String): String =
    label.trim().replace(Regex("\\s+"), " ").lowercase(java.util.Locale.ROOT)

internal fun isExplicitOutsideLabel(label: String): Boolean =
    normalizeLabel(label) in explicitOutsideLabels

internal fun isGenericOutsideLabel(label: String): Boolean {
    val normalized = normalizeLabel(label)
    return normalized.isEmpty() || normalized in genericOutsideDeviceLabels || deviceFallbackPattern.matches(normalized)
}

private fun defaultDeviceName(id: Long, serverLabel: String?, attributes: Map<Int, Reading>, text: TextCatalog): String {
    if (isOutsideOnlyAttributes(attributes) && (serverLabel == null || isGenericOutsideLabel(serverLabel))) {
        return text(TextKey.OutsideSensor)
    }
    if (Attr.TEMPERATURE in attributes && Attr.OUTSIDE !in attributes && serverLabel != null && isExplicitOutsideLabel(serverLabel)) {
        return text(TextKey.OutsideSensor)
    }
    return serverLabel ?: text(TextKey.DeviceFallback, id)
}

fun rawTarget(celsius: Double): Int {
    require(celsius in 10.0..30.0 && celsius * 2 == round(celsius * 2)) { "Choose 10–30 °C in half-degree steps." }
    return (celsius * 2).toInt()
}
fun rawPosition(percentOpen: Int): Int {
    require(percentOpen in 0..100)
    return 100 - percentOpen
}

/** Tilt is derived from position; Entia has no independent slat-angle attribute. */
data class BlindTiltState(
    val base: Int,
    val lastDown: Boolean = true,
    val tilt: Int? = null,
    val directionKnown: Boolean = false,
    val pending: Int? = null,
) {
    fun position(raw: Int) = copy(base = raw, lastDown = if (raw == base) lastDown else raw > base,
        tilt = 0, directionKnown = true, pending = raw)
    fun tilting(percent: Int) = copy(tilt = percent, pending = tiltValue(percent))
    fun tiltValue(percent: Int): Int {
        require(directionKnown) { "Set a blind position first to establish its direction." }
        require(percent in 0..100)
        return (base + (if (lastDown) -1 else 1) * round(percent / 100.0 * 3).toInt()).coerceIn(0, 100)
    }
    fun observed(raw: Int, moving: Boolean): BlindTiltState {
        if (moving) return this
        if (raw == pending) return copy(pending = null)
        val delta = (raw - base) * if (lastDown) -1 else 1
        return if (directionKnown && delta in 0..3) copy(tilt = round(delta / 3.0 * 100).toInt())
        else copy(base = raw, lastDown = raw > base, tilt = null, directionKnown = false, pending = null)
    }
}
