package si.hafnar.entalt.domain

import kotlinx.serialization.json.*

data class AttributeEvent(val device: Long, val attribute: Int, val reading: Reading)

fun parseEvent(text: String): AttributeEvent? = runCatching {
    val event = Json.parseToJsonElement(text) as? JsonObject ?: return null
    if (event["event"]?.jsonPrimitive?.contentOrNull != "attribute_event") return null
    val data = event["data"] as? JsonObject ?: return null
    fun id(key: String) = (data[key] as? JsonPrimitive)?.contentOrNull?.toBigDecimalOrNull()?.longValueExact()
    val device = id("device_id") ?: return null
    val attribute = id("attribute_id")?.takeIf { it in 0..Int.MAX_VALUE }?.toInt() ?: return null
    AttributeEvent(device, attribute, Reading(data["value"] ?: return null, id("timestamp")))
}.getOrNull()

/** Equal/missing timestamps prefer the event received during a snapshot request. */
fun Home.applyEvent(event: AttributeEvent): Home {
    val device = devices[event.device] ?: return this
    val old = device.attributes[event.attribute]
    if (old?.timestamp != null && event.reading.timestamp != null && old.timestamp > event.reading.timestamp) return this
    return copy(devices = devices + (device.id to device.copy(
        attributes = device.attributes + (event.attribute to event.reading), available = true,
    )))
}

fun reconcileSnapshot(snapshot: Home, previous: Home, duringFetch: List<AttributeEvent>): Home {
    var result = snapshot
    // A REST replica may lag a previously observed event with a newer server timestamp.
    previous.devices.values.forEach { device ->
        device.attributes.forEach { (attribute, reading) ->
            val fresh = result.devices[device.id]?.attributes?.get(attribute)
            if (fresh?.timestamp != null && reading.timestamp != null && reading.timestamp > fresh.timestamp) {
                result = result.applyEvent(AttributeEvent(device.id, attribute, reading))
            }
        }
    }
    duringFetch.forEach { result = result.applyEvent(it) }
    return result
}

fun validateCommand(device: Device, attribute: Int, value: Int) {
    require(device.available && !device.hidden && attribute in device.attributes) { "This control is unavailable." }
    require(when (attribute) {
        Attr.LIGHT -> value in 0..1
        Attr.POSITION -> value in 0..100
        Attr.TARGET -> device.isClimate && value in 20..60
        Attr.MODE -> device.isClimate && value in 0..2
        Attr.HRV_SPEED -> value in 0..3
        Attr.HRV_MODE -> device.isVentilation && value in 0..1
        else -> false
    }) { "Unsupported control value." }
}
