package si.hafnar.entalt

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import si.hafnar.entalt.data.*
import si.hafnar.entalt.domain.*

class DomainTest {
    @Test fun smallFourByThreePanelsDefaultToRoomDisplayWithoutAffectingPhonesOrLargeTablets() {
        listOf(800 to 600, 600 to 800, 1024 to 768, 320 to 240, 240 to 180, 1280 to 960, 480 to 480, 320 to 320).forEach {
            assertTrue(defaultsToRoomDisplay(it.first, it.second))
        }
        listOf(1080 to 2400, 1280 to 800, 2048 to 1536, 0 to 0).forEach {
            assertFalse(defaultsToRoomDisplay(it.first, it.second))
        }
    }

    @Test fun fallbackAndDemoLabelsUseTheSuppliedLanguageWithoutChangingServerLabels() {
        val text = TextCatalog { key, number -> "${key.name}:${number ?: ""}" }
        val home = parseHome(json("""{"flat":{"floors":[{"rooms":[{"id":1,"devices":[{"id":5}]},{"id":2,"label":"Server room","devices":[]}]}]}}""").jsonObject,
            json("""{"devices":[{"id":9,"attributes":[]}]}"""), text = text)
        assertEquals("RoomFallback:1", home.rooms[0].name)
        assertEquals("FloorFallback:1", home.rooms[0].floor)
        assertEquals("Server room", home.rooms[1].name)
        assertEquals("DeviceFallback:5", home.devices.getValue(5).name)
        assertEquals("UnassignedDevices:", home.rooms.last().name)
        val demo = si.hafnar.entalt.data.demoHome(text)
        assertEquals("LivingRoom:", demo.rooms.first().name)
        assertEquals("PendantLights:", demo.devices.getValue(101).name)
    }

    private fun reading(value: Int, time: Long? = null) = Reading(JsonPrimitive(value), time)
    private fun json(text: String) = Json.parseToJsonElement(text)

    @Test fun numericReadingsAcceptIntegralWireVariantsOnly() {
        listOf("43", "43.0", "\"43\"").forEach { assertEquals(43, Reading(json(it)).value) }
        listOf("43.5", "null", "true", "{}", "99999999999999999999").forEach { assertNull(Reading(json(it)).value) }
        assertEquals(43, rawTarget(21.5))
        assertEquals(75, rawPosition(25))
        assertThrows(IllegalArgumentException::class.java) { rawTarget(21.25) }
    }

    @Test fun hierarchyPreservesEmptyUnavailableAndConflictingDevices() {
        val flat = json("""{"flat":{"floors":[{"rooms":[
            {"id":1,"label":"Kitchen &amp; dining","devices":[{"id":5},{"id":6}]},
            {"id":2,"devices":[{"id":5}]},{"id":3,"devices":[]}]}]}}""").jsonObject
        val home = parseHome(flat, json("""{"connected":1,"devices":[
            {"id":5,"room_id":1,"attributes":[{"id":401,"value":1}]},
            {"id":9,"room_id":2,"attributes":[{"id":801,"value":43}]}]}""")) { it.replace("&amp;", "&") }
        assertEquals("Kitchen & dining", home.rooms[0].name)
        assertTrue(home.rooms.any { it.id == 3L && it.deviceIds.isEmpty() })
        assertFalse(home.devices.getValue(6).available)
        assertEquals(Long.MIN_VALUE, home.devices.getValue(5).roomId)
        assertEquals(2L, home.devices.getValue(9).roomId)
        assertEquals(true, home.controllerConnected)
        assertEquals(21.5, home.devices.getValue(9).temperature(Attr.TEMPERATURE)!!, 0.0)
    }

    @Test fun valveMatchingUsesTelemetryAndRejectsAmbiguity() {
        val climate = Device(1, "Climate", 1, mapOf(Attr.INSIDE to reading(40), Attr.TARGET to reading(44),
            Attr.MODE to reading(2)), telemetryRoomId = 5)
        val valve = Device(2, "Valve", 1, mapOf(Attr.VALVE to reading(0)), telemetryRoomId = 5)
        assertEquals("Idle", Home(devices = mapOf(1L to climate, 2L to valve)).activity(climate))
        assertEquals("Unknown", Home(devices = mapOf(1L to climate, 2L to valve, 3L to valve.copy(id = 3))).activity(climate))
        assertEquals("Heating · estimated", Home(devices = mapOf(1L to climate, 2L to valve.copy(telemetryRoomId = 9))).activity(climate))
    }

    @Test fun newerSocketReadingSurvivesLaggingSnapshot() {
        val original = Home(devices = mapOf(1L to Device(1, "Light", 1, mapOf(Attr.LIGHT to reading(0, 20)))))
        val event = parseEvent("""{"event":"attribute_event","data":{"device_id":1,"attribute_id":401,"value":"1","timestamp":22}}""")!!
        val current = original.applyEvent(event)
        assertEquals(1, reconcileSnapshot(original, current, emptyList()).devices.getValue(1).value(Attr.LIGHT))
        assertEquals(1, reconcileSnapshot(original, original, listOf(event)).devices.getValue(1).value(Attr.LIGHT))
        assertEquals(current, current.applyEvent(event.copy(reading = reading(0, 21))))
        assertNull(parseEvent("""{"event":"heartbeat"}"""))
        assertNull(parseEvent("""{"event":"attribute_event","data":{"device_id":{},"value":1}}"""))
    }

    @Test fun blindTiltUsesHalfEvenAndDefersMovingObservations() {
        val cold = BlindTiltState(30)
        assertThrows(IllegalArgumentException::class.java) { cold.tiltValue(50) }
        val positioned = cold.position(60)
        assertEquals(58, positioned.tiltValue(50))
        val pending = positioned.tilting(50)
        assertEquals(pending, pending.observed(55, moving = true))
        assertEquals(60, pending.observed(58, moving = false).base)
        assertNull(pending.observed(58, moving = false).pending)
        assertFalse(pending.observed(10, moving = false).directionKnown)
        assertEquals(22, BlindTiltState(60).position(20).tiltValue(50))
        assertEquals(0, BlindTiltState(0).position(1).tiltValue(100))
        assertEquals(100, BlindTiltState(100).position(99).tiltValue(100))
    }

    @Test fun unsafeOrUnsupportedWritesAreRejected() {
        val device = Device(1, "Light", 1, mapOf(Attr.LIGHT to reading(1)))
        assertThrows(IllegalArgumentException::class.java) { validateCommand(device, Attr.LIGHT, 2) }
        assertThrows(IllegalArgumentException::class.java) { validateCommand(device, Attr.POSITION, 50) }
        assertThrows(IllegalArgumentException::class.java) { validateCommand(device.copy(available = false), Attr.LIGHT, 0) }
        assertFalse(Credentials("alice", "secret").toString().contains("secret"))
    }

    @Test fun accountIdentityRejectsOtherAccountsRoomAndSpeedPreferences() {
        val alice = Preferences(lastRoom = 1, startupRoom = 2, speeds = mapOf(8L to 3)).forAccount("alice")
            .copy(lastRoom = 1, startupRoom = 2, speeds = mapOf(8L to 3))
        assertEquals(alice, alice.forAccount("alice"))
        val bob = alice.forAccount("bob")
        assertNull(bob.lastRoom)
        assertNull(bob.startupRoom)
        assertTrue(bob.speeds.isEmpty())
        assertNotEquals(alice.accountId, bob.accountId)
        assertEquals("dark", bob.theme)
    }

    @Test fun onlyCardsWithSupportedStatusOrActionsAreVisible() {
        fun device(vararg values: Pair<Int, Reading>) = Device(1, "Device", 1, values.toMap())
        val unknown = Reading(JsonNull)
        assertTrue(device(9999 to reading(1)).visibleControlKinds.isEmpty())
        assertTrue(device(Attr.VALVE to reading(1)).visibleControlKinds.isEmpty())
        assertTrue(device(Attr.LIGHT to unknown).visibleControlKinds.isEmpty())
        assertTrue(device(Attr.LIGHT to reading(2)).visibleControlKinds.isEmpty())
        assertTrue(device(Attr.POSITION to reading(101)).visibleControlKinds.isEmpty())
        assertTrue(device(Attr.TEMPERATURE to unknown).visibleControlKinds.isEmpty())
        assertTrue(device(Attr.LIGHT to reading(1)).copy(available = false).visibleControlKinds.isEmpty())
        assertEquals(listOf(Attr.TEMPERATURE), device(Attr.LIGHT to unknown, Attr.TEMPERATURE to reading(43)).visibleControlKinds)
        assertEquals(listOf(Attr.INSIDE), device(Attr.INSIDE to reading(43)).visibleControlKinds)
        assertEquals(listOf(Attr.HRV_SPEED), device(Attr.HRV_SPEED to unknown, Attr.HRV_MODE to reading(1)).visibleControlKinds)
        assertEquals(listOf(Attr.POSITION), device(Attr.POSITION to unknown, Attr.MOVING to reading(1)).visibleControlKinds)
        assertEquals(listOf(Attr.TARGET), device(Attr.INSIDE to reading(43), Attr.TARGET to unknown).visibleControlKinds)
        assertTrue(device(Attr.INSIDE to unknown, Attr.TARGET to unknown).visibleControlKinds.isEmpty())
    }

    @Test fun roomVisibilityAndStartupFollowUsableDevicesInServerOrder() {
        val rooms = listOf(9L, 2L, 5L, 8L, 7L).map { Room(it, "Room $it", "Floor", emptyList()) }
        val devices = listOf(
            Device(1, "Temperature", 9, mapOf(Attr.TEMPERATURE to reading(43))),
            Device(2, "Unknown", 2, mapOf(9999 to reading(1))),
            Device(3, "Light", 5, mapOf(Attr.LIGHT to reading(0))),
            Device(4, "Unavailable", 8, mapOf(Attr.LIGHT to reading(1)), available = false),
        )
        val home = Home(rooms, devices.associateBy { it.id })
        assertEquals(listOf(9L, 5L), home.visibleRooms.map { it.id })
        assertEquals(5L, home.startupRoom(preferred = 2, last = 5))
        assertEquals(9L, home.startupRoom(preferred = 9, last = 5))
        assertNull(home.startupRoom(preferred = 7, last = 8))
        assertNull(home.visibleRoomId(2))
        assertEquals(5, home.rooms.size)
        assertEquals(4, home.devices.size)
    }

    @Test fun hiddenValveStillContributesToClimateActivity() {
        val climate = Device(1, "Climate", 1, mapOf(Attr.INSIDE to reading(40), Attr.TARGET to reading(44),
            Attr.MODE to reading(2)), telemetryRoomId = 1)
        val valve = Device(2, "Valve", 1, mapOf(Attr.VALVE to reading(0)), telemetryRoomId = 1)
        val home = Home(listOf(Room(1, "Room", "Floor", listOf(1, 2))), mapOf(1L to climate, 2L to valve))
        assertEquals(listOf(1L), home.visibleDevicesIn(1).map { it.id })
        assertEquals("Idle", home.activity(climate))
    }

    @Test fun ventilationUnitsAreHomeControlsAndRemainDistinct() {
        val first = Device(1, "Ventilation", 10, mapOf(Attr.HRV_SPEED to reading(2), Attr.HRV_MODE to reading(1)))
        val second = first.copy(id = 2, roomId = 20)
        val home = Home(listOf(Room(10, "Plant room", "Floor", listOf(1)), Room(20, "Plant room 2", "Floor", listOf(2))),
            mapOf(1L to first, 2L to second))
        assertEquals(listOf(1L, 2L), home.ventilationUnits.map { it.id })
        assertTrue(home.visibleRooms.isEmpty())
        assertTrue(first.roomControlKinds.isEmpty())
        assertEquals(10L, home.devices.getValue(1).roomId)
    }

    @Test fun roomDisplayPrefersLivingRoomAndUsesMeasuredIndoorAndOutdoorReadings() {
        val kitchen = Device(1, "Kitchen", 2, mapOf(Attr.TEMPERATURE to reading(46)))
        val living = Device(2, "Living", 1, mapOf(Attr.INSIDE to reading(43), Attr.TARGET to reading(50), Attr.OUTSIDE to reading(8)))
        val home = Home(listOf(Room(2, "Kitchen", "Floor", listOf(1)), Room(1, "Dnevna soba", "Floor", listOf(2))),
            mapOf(1L to kitchen, 2L to living))
        assertEquals(1L, home.displayRoom(null)?.id)
        assertEquals(2L, home.displayRoom(2)?.id)
        assertEquals(21.5, home.indoorTemperature(1)!!, 0.0)
        assertEquals(4.0, home.outdoorTemperature(1)!!, 0.0)
        assertEquals(4.0, home.outdoorTemperature(2)!!, 0.0)
        assertNull(Home().displayRoom(null))
        assertNull(Home().outdoorTemperature(null))
    }

    @Test fun conflictingOutsideReadingsAreNotArbitrarilyCombinedAndAccountDisplayIsIsolated() {
        val home = Home(devices = mapOf(
            1L to Device(1, "One", 1, mapOf(Attr.OUTSIDE to reading(8))),
            2L to Device(2, "Two", 2, mapOf(Attr.OUTSIDE to reading(12)))))
        assertNull(home.outdoorTemperature(3))
        assertEquals(4.0, home.outdoorTemperature(1)!!, 0.0)
        val alice = Preferences(disclaimerAccepted = true).forAccount("alice").copy(displayRoom = 1)
        val bob = alice.forAccount("bob")
        assertNull(bob.displayRoom)
        assertTrue(bob.disclaimerAccepted)
    }

    @Test fun localOverridesHideOnlyPresentationAndKeepServerNamesAndTelemetry() {
        val device = Device(1, "Server light", 1, mapOf(Attr.LIGHT to reading(1)))
        val home = Home(listOf(Room(1, "Room", "Floor", listOf(1))), mapOf(1L to device))
        val local = home.withLocalOverrides(mapOf(1L to "Reading lamp"), setOf(1L), mapOf(1L to LightIcon.TableLamp))
        assertEquals("Reading lamp", local.devices.getValue(1).name)
        assertEquals("Server light", local.devices.getValue(1).serverName)
        assertEquals(LightIcon.TableLamp, local.devices.getValue(1).lightIcon)
        assertTrue(local.visibleRooms.isEmpty())
        assertEquals(1, local.devices.getValue(1).value(Attr.LIGHT))
        assertThrows(IllegalArgumentException::class.java) { validateCommand(local.devices.getValue(1), Attr.LIGHT, 0) }
        val reset = local.withLocalOverrides(emptyMap(), emptySet(), emptyMap())
        assertEquals("Server light", reset.devices.getValue(1).name)
        assertEquals(LightIcon.Bulb, reset.devices.getValue(1).lightIcon)
        assertEquals(listOf(1L), reset.visibleRooms.map { it.id })
    }

    @Test fun customizationIsAccountScopedAndDisplayFocusIsInstallationWide() {
        val alice = Preferences(disclaimerAccepted = true, displayFocus = "temperature", hideCooling = true).forAccount("alice").copy(
            deviceNames = mapOf(1L to "Local name"), hiddenDevices = setOf(1L), lightIcons = mapOf(1L to LightIcon.Strip),
            blindPresets = mapOf(2L to BlindSlots(25, 75)))
        val bob = alice.forAccount("bob")
        assertTrue(bob.deviceNames.isEmpty()); assertTrue(bob.hiddenDevices.isEmpty()); assertTrue(bob.lightIcons.isEmpty())
        assertTrue(bob.blindPresets.isEmpty())
        assertEquals("temperature", bob.displayFocus); assertTrue(bob.disclaimerAccepted); assertTrue(bob.hideCooling)
        assertTrue(alice.forDemo().deviceNames.isEmpty())
        assertTrue(alice.forDemo().blindPresets.isEmpty())
        assertEquals("clock", Preferences().displayFocus)
        assertTrue(Preferences().dynamicColor)
        assertFalse(Preferences().hideCooling)
        assertTrue(LightIcon.entries.size >= 12)
    }

    @Test fun outsideSensorUsesLocalizedDefaultUnlessCustomServerOrLocalNameSet() {
        val text = TextCatalog { key, number -> if (key == TextKey.OutsideSensor) "Zunanje tipalo" else "${key.name}:${number ?: ""}" }
        val home = parseHome(
            json("""{"flat":{"floors":[{"rooms":[{"id":1,"devices":[
                {"id":10,"label":""},
                {"id":11,"label":"Zunanji senzor"},
                {"id":12,"label":"North balcony"},
                {"id":13,"label":"Zunaj"}
            ]}]}]}}""").jsonObject,
            json("""{"devices":[
                {"id":10,"room_id":1,"attributes":[{"id":1104,"value":17}]},
                {"id":11,"room_id":1,"attributes":[{"id":1104,"value":17}]},
                {"id":12,"room_id":1,"attributes":[{"id":1104,"value":17}]},
                {"id":13,"room_id":1,"attributes":[{"id":801,"value":17}]},
                {"id":14,"attributes":[{"id":1104,"value":17}]}
            ]}"""),
            text = text
        )
        assertEquals("Zunanje tipalo", home.devices.getValue(10).name)
        assertEquals("Zunanje tipalo", home.devices.getValue(11).name)
        assertEquals("North balcony", home.devices.getValue(12).name)
        assertEquals("Zunanje tipalo", home.devices.getValue(13).name)
        assertEquals("Zunanje tipalo", home.devices.getValue(14).name)
        assertEquals("Outside sensor", EnglishText(TextKey.OutsideSensor))
    }

    @Test fun hueEnabledIsInstallationWideWhileHueBindingsAreAccountScopedAndKeysRedacted() {
        val alice = Preferences(disclaimerAccepted = true, hueEnabled = true).forAccount("alice").copy(
            hueBindings = mapOf(101L to HueBinding("uuid-1", "light", "Ceiling Spot"))
        )
        val bob = alice.forAccount("bob")
        assertTrue(bob.hueEnabled)
        assertTrue(bob.hueBindings.isEmpty())
        assertTrue(alice.forDemo().hueEnabled)
        assertTrue(alice.forDemo().hueBindings.isEmpty())
        assertFalse(Preferences().hueEnabled)
        assertTrue(Preferences().hueBindings.isEmpty())

        val creds = HueCredentials("192.168.1.50", "secret-hue-app-key", "secret-client-key", "001788fffe01")
        assertFalse(creds.toString().contains("secret-hue-app-key"))
        assertFalse(creds.toString().contains("secret-client-key"))
    }

    @Test fun standaloneHueLightsAttachToRoomsAreAccountScopedAndRespectHueEnabled() {
        val rooms = listOf(
            Room(1, "Living room", "Floor", listOf(1L)),
            Room(2, "Guest room", "Floor", emptyList()),
        )
        val base = Home(rooms, mapOf(1L to Device(1, "Ceiling", 1, mapOf(Attr.LIGHT to reading(0)))))
        val standalone = StandaloneHueLight(
            id = -1001L,
            roomId = 2L,
            name = "Hue Floor Lamp",
            icon = LightIcon.FloorLamp,
            targets = listOf(HueTargetRef("hue-floor", "light", "Floor Lamp")),
        )

        val disabled = base.withLocalOverrides(emptyMap(), emptySet(), emptyMap(), listOf(standalone), hueEnabled = false)
        assertNull(disabled.devices[-1001L])
        assertEquals(listOf(1L), disabled.visibleRooms.map { it.id })

        val enabled = disabled.withLocalOverrides(emptyMap(), emptySet(), emptyMap(), listOf(standalone), hueEnabled = true)
        val synthetic = enabled.devices.getValue(-1001L)
        assertTrue(synthetic.isStandaloneHue)
        assertTrue(synthetic.isLight)
        assertTrue(synthetic.available)
        assertEquals(2L, synthetic.roomId)
        assertEquals("Hue Floor Lamp", synthetic.name)
        assertEquals(LightIcon.FloorLamp, synthetic.lightIcon)
        assertEquals(listOf(1L, 2L), enabled.visibleRooms.map { it.id })
        assertTrue(-1001L in enabled.rooms.first { it.id == 2L }.deviceIds)

        val turnedOffAgain = enabled.withLocalOverrides(emptyMap(), emptySet(), emptyMap(), listOf(standalone), hueEnabled = false)
        assertNull(turnedOffAgain.devices[-1001L])
        assertEquals(listOf(1L), turnedOffAgain.visibleRooms.map { it.id })

        val alice = Preferences(disclaimerAccepted = true, hueEnabled = true).forAccount("alice").copy(
            standaloneHueLights = listOf(standalone)
        )
        assertEquals(listOf(HueTargetRef("hue-floor", "light", "Floor Lamp")), alice.hueBindingFor(-1001L)?.targets)
        val bob = alice.forAccount("bob")
        assertTrue(bob.hueEnabled)
        assertTrue(bob.standaloneHueLights.isEmpty())
        assertNull(bob.hueBindingFor(-1001L))
        assertTrue(alice.forDemo().standaloneHueLights.isEmpty())
    }

    @Test fun languagePreferenceAndEntiaHueOutletMappingWorkAcrossAccounts() {
        val prefs = Preferences(disclaimerAccepted = true, hueEnabled = true, language = "sl")
            .forAccount("alice")
            .copy(
                hueBindings = mapOf(10L to HueBinding("hue-1", "light", "Ceiling 1")),
                standaloneHueLights = listOf(
                    StandaloneHueLight(
                        id = -1001L,
                        roomId = 1L,
                        name = "Floor Lamp",
                        targets = listOf(HueTargetRef("hue-2", "light", "Floor")),
                        entiaDeviceId = 10L,
                    ),
                    StandaloneHueLight(
                        id = -1002L,
                        roomId = 1L,
                        name = "Shared Ceiling",
                        targets = listOf(HueTargetRef("hue-1", "light", "Ceiling 1")),
                        entiaDeviceId = null,
                    ),
                    StandaloneHueLight(
                        id = -1003L,
                        roomId = 1L,
                        name = "Always On",
                        targets = listOf(HueTargetRef("hue-3", "light", "Always On")),
                        entiaDeviceId = null,
                    ),
                ),
            )
        assertEquals("sl", prefs.language)
        assertEquals("sl", prefs.forAccount("bob").language)
        assertEquals("sl", prefs.forDemo().language)
        assertEquals("system", Preferences().language)

        assertEquals(10L, prefs.linkedEntiaSwitchId(10L))
        assertEquals(10L, prefs.linkedEntiaSwitchId(-1001L))
        assertEquals(10L, prefs.linkedEntiaSwitchId(-1002L))
        assertNull(prefs.linkedEntiaSwitchId(-1003L))
        assertEquals(listOf("hue-1", "hue-2"), prefs.allHueTargetsOnEntiaSwitch(10L).map { it.id })

        val home = Home(
            rooms = listOf(Room(1L, "Living", "Floor", listOf(10L, -1001L, -1003L))),
            devices = mapOf(
                10L to Device(10L, "Switch", 1L, mapOf(Attr.LIGHT to reading(0))),
            ),
        ).withLocalOverrides(emptyMap(), emptySet(), emptyMap(), prefs.standaloneHueLights, hueEnabled = true)
        val hueState = HueState(
            paired = true,
            targets = listOf(
                HueTarget("hue-2", "light", "Floor", on = true),
                HueTarget("hue-3", "light", "Always On", on = true),
            ),
        )
        // Linked standalone (-1001L) appears OFF while Entia switch 10L is OFF; unlinked (-1003L) stays ON.
        val linkedOff = resolveLightState(home, prefs, hueState, home.devices.getValue(-1001L))
        assertEquals(10L, linkedOff.linkedEntiaId)
        assertFalse(linkedOff.mainsOn)
        assertFalse(linkedOff.on)
        val unlinkedOn = resolveLightState(home, prefs, hueState, home.devices.getValue(-1003L))
        assertNull(unlinkedOn.linkedEntiaId)
        assertTrue(unlinkedOn.mainsOn)
        assertTrue(unlinkedOn.on)

        val homeMainsOn = home.applyEvent(AttributeEvent(10L, Attr.LIGHT, reading(1)))
        val linkedOn = resolveLightState(homeMainsOn, prefs, hueState, homeMainsOn.devices.getValue(-1001L))
        assertTrue(linkedOn.mainsOn)
        assertTrue(linkedOn.on)
    }

    @Test fun heatingIndicatorRoomsKeepFixedOrderAndReflectActiveHeating() {
        val home = Home(
            rooms = listOf(
                Room(1L, "Living", "Ground", listOf(10L, 11L)),
                Room(2L, "Kitchen", "Ground", listOf(20L)),
                Room(3L, "Bedroom", "First", listOf(30L)),
                Room(4L, "Bath", "First", listOf(40L)),
                Room(5L, "Office", "First", listOf(50L)),
            ),
            devices = mapOf(
                10L to Device(10L, "Climate", 1L, mapOf(Attr.INSIDE to reading(42), Attr.TARGET to reading(44), Attr.MODE to reading(2)), telemetryRoomId = 1L),
                11L to Device(11L, "Valve", 1L, mapOf(Attr.VALVE to reading(1)), telemetryRoomId = 1L),
                20L to Device(20L, "Light", 2L, mapOf(Attr.LIGHT to reading(1)), telemetryRoomId = 2L),
                30L to Device(30L, "Climate", 3L, mapOf(Attr.INSIDE to reading(44), Attr.TARGET to reading(44), Attr.MODE to reading(0)), telemetryRoomId = 3L),
                40L to Device(40L, "Climate", 4L, mapOf(Attr.INSIDE to reading(40), Attr.TARGET to reading(46), Attr.MODE to reading(2)), telemetryRoomId = 4L),
                50L to Device(50L, "Climate", 5L, mapOf(Attr.INSIDE to reading(48), Attr.TARGET to reading(42), Attr.MODE to reading(1)), telemetryRoomId = 5L),
            ),
        )
        // Only climate/valve rooms appear, in canonical room order (Kitchen has no climate device).
        assertEquals(listOf(1L, 3L, 4L, 5L), home.heatingIndicatorRooms.map { it.id })
        // Living (valve open + Heat mode) and Bath (estimated heating, inside < target) are heating.
        assertTrue(home.isRoomHeating(1L))
        assertFalse(home.isRoomHeating(3L))
        assertTrue(home.isRoomHeating(4L))
        // Office is in Cool mode, not heating.
        assertFalse(home.isRoomHeating(5L))
    }

    @Test fun panelHeatingBarAndChildLockPreferencesHaveExpectedDefaultsAndScope() {
        val defaults = Preferences()
        assertTrue(defaults.showHeatingRooms)
        assertFalse(defaults.childLock)

        val customized = defaults.copy(
            disclaimerAccepted = true,
            showHeatingRooms = false,
            childLock = true,
        ).forAccount("alice")
        val switchedAccount = customized.forAccount("bob")
        assertFalse(switchedAccount.showHeatingRooms)
        assertTrue(switchedAccount.childLock)
        assertFalse(customized.forDemo().showHeatingRooms)
        assertTrue(customized.forDemo().childLock)
    }
}
