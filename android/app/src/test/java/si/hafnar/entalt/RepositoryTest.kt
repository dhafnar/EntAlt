package si.hafnar.entalt

import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import si.hafnar.entalt.data.*
import si.hafnar.entalt.domain.*

class RepositoryTest {
    private val flat = Json.parseToJsonElement("""{"flat":{"floors":[{"label":"Floor","rooms":[{"id":1,"label":"Room","devices":[{"id":1}]}]}]}}""").jsonObject
    private fun telemetry(value: Int) = Json.parseToJsonElement("""{"devices":[{"id":1,"room_id":1,"attributes":[{"id":401,"value":$value}]}]}""")

    private class Accounts(var record: Credentials? = null) : AccountStore {
        var loads = 0
        var failSave = false
        var failClear = false
        var beforeSave: (() -> Unit)? = null
        override fun load(): Credentials? { loads++; return record }
        override fun save(credentials: Credentials) { beforeSave?.invoke(); if (failSave) throw IOException(); record = credentials }
        override fun clear() { if (failClear) throw IOException(); record = null }
    }
    private class Prefs(var value: Preferences = Preferences(disclaimerAccepted = true)) : PreferenceStore {
        var failSave = false
        override suspend fun load() = value
        override suspend fun save(preferences: Preferences) { if (failSave) throw IOException(); value = preferences }
        override suspend fun clearAccount() { value = value.copy(lastRoom = null, startupRoom = null, displayRoom = null, speeds = emptyMap(), accountId = null,
            deviceNames = emptyMap(), hiddenDevices = emptySet(), lightIcons = emptyMap(), blindPresets = emptyMap(), hueBindings = emptyMap(),
            standaloneHueLights = emptyList()) }
    }
    private inner class Gateway : EntiaGateway {
        var closed = false
        val didClose = CompletableDeferred<Unit>()
        var definition = flat
        var validateHandler: suspend () -> JsonObject = { definition }
        var flatHandler: suspend () -> JsonObject = { definition }
        var value = 0
        var deviceCalls = 0
        var deviceHandler: suspend () -> JsonElement = { telemetry(value) }
        var writes = 0
        var setHandler: suspend (Int) -> Unit = { value = it }
        var renewCalls = 0
        var clearTokenCalls = 0
        var renewHandler: suspend (String?) -> String = { "mock" }
        var sockets = 0
        var maxSockets = 0
        var emit: ((String) -> Unit)? = null
        var listenHandler: suspend () -> Unit = {}
        override suspend fun token() = "mock"
        override suspend fun renew(rejected: String?): String { renewCalls++; return renewHandler(rejected) }
        override fun clearToken() { clearTokenCalls++ }
        override suspend fun validate() = validateHandler()
        override suspend fun flat() = flatHandler()
        override suspend fun devices(): JsonElement { deviceCalls++; return deviceHandler() }
        override suspend fun set(device: Long, attribute: Int, value: Int) { writes++; setHandler(value) }
        override suspend fun listen(onOpen: () -> Unit, onEvent: (String) -> Unit) {
            sockets++; maxSockets = maxOf(maxSockets, sockets); emit = onEvent
            try { onOpen(); listenHandler(); awaitCancellation() } finally { sockets-- }
        }
        override fun close() { closed = true; didClose.complete(Unit) }
    }
    private fun scenario(block: suspend CoroutineScope.() -> Unit) {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher -> runBlocking(dispatcher, block) }
    }
    private suspend fun ready(r: EntiaRepository) = withTimeout(3_000) { r.state.first { !it.restoring && !it.signingIn && it.home.syncedAt > 0 } }
    private suspend fun state(r: EntiaRepository, predicate: (AppState) -> Boolean) = withTimeout(3_000) { r.state.first(predicate) }
    private fun CoroutineScope.dispatcher() = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
    private suspend fun settle() { repeat(12) { yield() } }

    @Test fun preferenceFailureAfterCredentialCommitStillInstallsCandidate() = scenario {
        val accounts = Accounts(Credentials("alice", "old"))
        val prefs = Prefs(Preferences(disclaimerAccepted = true).forAccount("alice").copy(lastRoom = 1, startupRoom = 1, speeds = mapOf(1L to 3)))
        val old = Gateway(); val replacement = Gateway()
        val r = EntiaRepository(accounts, prefs, { if (it.username == "alice") old else replacement }, dispatcher = dispatcher(), io = dispatcher())
        try {
            ready(r); prefs.failSave = true
            r.signIn("bob", "new")
            val current = state(r) { it.username == "bob" && !it.signingIn }
            assertEquals("bob", accounts.record!!.username)
            assertTrue(old.closed); assertNull(current.loginError)
            assertNull(current.preferences.startupRoom); assertTrue(current.preferences.speeds.isEmpty())
            assertNotEquals(prefs.value.accountId, current.preferences.accountId)
        } finally { r.close() }
    }

    @Test fun failedCredentialSavePreservesWorkingAccount() = scenario {
        val accounts = Accounts(Credentials("alice", "old")); val old = Gateway(); val replacement = Gateway()
        val r = EntiaRepository(accounts, Prefs(), { if (it.username == "alice") old else replacement }, dispatcher = dispatcher(), io = dispatcher())
        try {
            ready(r); accounts.failSave = true; r.signIn("bob", "new")
            val current = state(r) { it.loginError != null }
            assertEquals("alice", current.username); assertEquals("alice", accounts.record!!.username)
            assertFalse(old.closed); assertTrue(replacement.closed)
        } finally { r.close() }
    }

    @Test fun cancelledStartupCandidateCannotReplaceNewSignIn() = scenario {
        val release = CompletableDeferred<Unit>(); val started = CompletableDeferred<Unit>()
        val old = Gateway().apply { flatHandler = { started.complete(Unit); withContext(NonCancellable) { release.await() }; flat } }
        val replacement = Gateway(); val accounts = Accounts(Credentials("alice", "old"))
        val r = EntiaRepository(accounts, Prefs(), { if (it.username == "alice") old else replacement }, dispatcher = dispatcher(), io = dispatcher())
        try {
            withTimeout(3_000) { started.await() }
            r.signIn("bob", "new"); ready(r)
            release.complete(Unit); withTimeout(3_000) { old.didClose.await() }
            assertEquals("bob", r.state.value.username); assertEquals("bob", accounts.record!!.username)
            assertFalse(replacement.closed)
        } finally { release.complete(Unit); r.close() }
    }

    @Test fun postWriteSnapshotBeginsAfterOlderRefreshCompletes() = scenario {
        val api = Gateway(); val r = EntiaRepository(Accounts(Credentials("alice", "old")), Prefs(), { api },
            dispatcher = dispatcher(), io = dispatcher(), confirmationMillis = 60_000)
        val release = CompletableDeferred<Unit>()
        try {
            r.setForeground(true); ready(r); settle()
            val started = CompletableDeferred<Unit>()
            api.deviceHandler = { started.complete(Unit); release.await(); telemetry(0) }
            r.refresh(); withTimeout(3_000) { started.await() }
            val before = api.deviceCalls
            r.command(1, Attr.LIGHT, 1); settle()
            assertEquals(1, api.writes)
            api.deviceHandler = { telemetry(api.value) }
            release.complete(Unit)
            state(r) { it.commands[1]?.phase == CommandPhase.Confirmed }
            assertTrue(api.deviceCalls > before)
            assertEquals(1, r.state.value.home.devices.getValue(1).value(Attr.LIGHT))
        } finally { release.complete(Unit); r.close() }
    }

    @Test fun foregroundOwnsOneSocketAndLogoutIgnoresLateEvents() = scenario {
        val api = Gateway(); val accounts = Accounts(Credentials("alice", "old"))
        val r = EntiaRepository(accounts, Prefs(), { api }, dispatcher = dispatcher(), io = dispatcher())
        try {
            ready(r); r.setForeground(true); settle()
            r.setForeground(true); settle(); assertEquals(1, api.sockets)
            r.setForeground(false); settle(); assertEquals(0, api.sockets)
            r.setForeground(true); settle(); assertEquals(1, api.sockets); assertEquals(1, api.maxSockets)
            val lateEvent = api.emit!!
            r.logout(); state(r) { !it.restoring && it.username == null }; settle()
            lateEvent("""{"event":"attribute_event","data":{"device_id":1,"attribute_id":401,"value":1}}""")
            assertTrue(r.state.value.home.devices.isEmpty()); assertNull(accounts.record)
            assertEquals(0, api.sockets)
        } finally { r.close() }
    }

    @Test fun failedForgetIsVisibleAndStillClosesSession() = scenario {
        val accounts = Accounts(Credentials("alice", "old")); val api = Gateway()
        val r = EntiaRepository(accounts, Prefs(), { api }, dispatcher = dispatcher(), io = dispatcher())
        try {
            ready(r); accounts.failClear = true; r.logout()
            val current = state(r) { !it.restoring && it.loginError != null }
            assertNull(current.username); assertTrue(current.home.devices.isEmpty()); assertTrue(api.closed)
            assertNotNull(accounts.record)
        } finally { r.close() }
    }

    @Test fun serverFailureLeavesWriteOutcomeUnknownAndDoesNotReplay() = scenario {
        val api = Gateway().apply { setHandler = { throw ApiException(500) } }
        val r = EntiaRepository(Accounts(Credentials("alice", "old")), Prefs(), { api }, dispatcher = dispatcher(), io = dispatcher())
        try {
            r.setForeground(true); ready(r); settle(); r.command(1, Attr.LIGHT, 1)
            state(r) { it.commands[1]?.phase == CommandPhase.Unconfirmed }
            assertEquals(1, api.writes)
        } finally { r.close() }
    }

    @Test fun validationAndPermissionErrorsUseLocalizedMessages() = scenario {
        val api = Gateway().apply { setHandler = { throw ApiException(403) } }
        val r = EntiaRepository(Accounts(), Prefs(), { api }, dispatcher = dispatcher(), io = dispatcher(),
            text = TextCatalog { key, _ -> key.name })
        try {
            state(r) { !it.restoring }; r.signIn("", "")
            assertEquals("CredentialsRequired", r.state.value.loginError)
            r.signIn("alice", "secret"); r.setForeground(true); ready(r); settle()
            r.command(1, Attr.LIGHT, 1)
            val current = state(r) { it.commands[1]?.phase == CommandPhase.Failed }
            assertEquals("PermissionDenied", current.commands.getValue(1).error)
            assertEquals(1, api.writes)
        } finally { r.close() }
    }

    @Test fun demoControlsNeverOpenNetworkOrSaveCredentials() = scenario {
        var clients = 0; val accounts = Accounts()
        val r = EntiaRepository(accounts, Prefs(), { clients++; Gateway() }, dispatcher = dispatcher(), io = dispatcher())
        try {
            state(r) { !it.restoring }; r.setForeground(true); r.demo()
            r.command(105, Attr.HRV_SPEED, 3); r.command(105, Attr.HRV_SPEED, 0)
            assertEquals(3, r.state.value.preferences.speeds[105])
            assertEquals(0, r.state.value.home.devices.getValue(105).value(Attr.HRV_SPEED))
            assertNull(accounts.record); assertEquals(0, clients)
        } finally { r.close() }
    }

    @Test fun demoBlindObservationAllowsSubsequentTiltAndPositionCommands() = scenario {
        val r = EntiaRepository(Accounts(), Prefs(), { error("Demo must stay local") }, dispatcher = dispatcher(), io = dispatcher())
        try {
            state(r) { !it.restoring }; r.setForeground(true); r.demo()
            r.command(102, Attr.POSITION, 50)
            assertNull(r.state.value.tilts.getValue(102).pending)
            r.tilt(102, 50)
            assertEquals(48, r.state.value.home.devices.getValue(102).value(Attr.POSITION))
            assertNull(r.state.value.tilts.getValue(102).pending)
            r.command(102, Attr.POSITION, 20); r.tilt(102, 100)
            assertEquals(23, r.state.value.home.devices.getValue(102).value(Attr.POSITION))
            assertEquals(CommandPhase.Confirmed, r.state.value.commands.getValue(102).phase)
        } finally { r.close() }
    }

    @Test fun logoutDuringAtomicSaveAlwaysClearsTheNewRecord() = scenario {
        val accounts = Accounts(); val api = Gateway()
        val started = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        accounts.beforeSave = { started.complete(Unit); check(release.await(3, TimeUnit.SECONDS)) }
        val r = EntiaRepository(accounts, Prefs(), { api }, dispatcher = dispatcher(), io = Dispatchers.IO)
        try {
            state(r) { !it.restoring }; r.signIn("alice", "new")
            withTimeout(3_000) { started.await() }
            r.logout(); release.countDown()
            state(r) { !it.restoring && it.username == null }
            assertNull(accounts.record); assertTrue(api.closed)
        } finally { release.countDown(); r.close() }
    }

    @Test fun unrelatedEventsCannotResetBlindDirectionOrConfirmBeforeAcceptance() = scenario {
        val api = Gateway(); var raw = 30
        api.deviceHandler = { Json.parseToJsonElement("""{"devices":[{"id":1,"attributes":[
            {"id":601,"value":$raw},{"id":603,"value":0},{"id":401,"value":0}]}]}""") }
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        api.setHandler = { started.complete(Unit); release.await(); raw = it }
        val r = EntiaRepository(Accounts(Credentials("alice", "old")), Prefs(), { api },
            dispatcher = dispatcher(), io = dispatcher(), confirmationMillis = 60_000)
        try {
            r.setForeground(true); ready(r); settle()
            r.command(1, Attr.POSITION, 60); withTimeout(3_000) { started.await() }
            api.emit!!("""{"event":"attribute_event","data":{"device_id":1,"attribute_id":401,"value":1}}""")
            assertTrue(r.state.value.tilts.getValue(1).directionKnown)
            assertEquals(60, r.state.value.tilts.getValue(1).base)
            api.emit!!("""{"event":"attribute_event","data":{"device_id":1,"attribute_id":601,"value":60}}""")
            assertEquals(CommandPhase.Sending, r.state.value.commands.getValue(1).phase)
            release.complete(Unit)
            state(r) { it.commands[1]?.phase == CommandPhase.Confirmed }
            assertTrue(r.state.value.tilts.getValue(1).directionKnown)
            assertNull(r.state.value.tilts.getValue(1).pending)
        } finally { release.complete(Unit); r.close() }
    }

    @Test fun startupAndExplicitSelectionSkipRoomsWithoutSupportedStatusOrControls() = scenario {
        val api = Gateway().apply {
            definition = Json.parseToJsonElement("""{"flat":{"floors":[{"rooms":[
                {"id":1,"devices":[{"id":1}]},{"id":2,"devices":[{"id":2}]},
                {"id":3,"devices":[{"id":3}]},{"id":4,"devices":[]}]}]}}""").jsonObject
            deviceHandler = { Json.parseToJsonElement("""{"devices":[
                {"id":1,"attributes":[{"id":401,"value":1}]},
                {"id":2,"attributes":[{"id":9999,"value":1}]},
                {"id":3,"attributes":[{"id":801,"value":43}]}]}""") }
        }
        val prefs = Prefs(Preferences(disclaimerAccepted = true).forAccount("alice").copy(startupRoom = 2, lastRoom = 3))
        val r = EntiaRepository(Accounts(Credentials("alice", "test")), prefs, { api }, dispatcher = dispatcher(), io = dispatcher())
        try {
            ready(r)
            assertEquals(3L, r.state.value.selectedRoom)
            assertEquals(listOf(1L, 3L), r.state.value.home.visibleRooms.map { it.id })
            r.selectRoom(2); assertEquals(3L, r.state.value.selectedRoom)
            r.selectRoom(4); assertEquals(3L, r.state.value.selectedRoom)
            r.selectRoom(1); assertEquals(1L, r.state.value.selectedRoom)
        } finally { r.close() }
    }

    @Test fun roomDisappearsAndReturnsAsUsableReadingsChange() = scenario {
        val api = Gateway()
        val r = EntiaRepository(Accounts(Credentials("alice", "test")), Prefs(), { api }, dispatcher = dispatcher(), io = dispatcher())
        try {
            r.setForeground(true); ready(r); settle(); r.selectRoom(1)
            api.emit!!("""{"event":"attribute_event","data":{"device_id":1,"attribute_id":401,"value":null}}""")
            assertTrue(r.state.value.home.visibleRooms.isEmpty())
            assertNull(r.state.value.selectedRoom)
            assertEquals(1, r.state.value.home.devices.size)
            api.emit!!("""{"event":"attribute_event","data":{"device_id":1,"attribute_id":401,"value":0}}""")
            assertEquals(listOf(1L), r.state.value.home.visibleRooms.map { it.id })
            r.selectRoom(1)
            api.deviceHandler = { Json.parseToJsonElement("""{"devices":[]}""") }
            r.refresh()
            state(r) { !it.syncing && it.home.visibleRooms.isEmpty() }
            assertNull(r.state.value.selectedRoom)
        } finally { r.close() }
    }

    @Test fun agreementIsRequiredBeforeAccountOrNetworkAccessAndPersistsThroughLogoutAndRestart() = scenario {
        val accounts = Accounts(Credentials("alice", "test")); val prefs = Prefs(Preferences())
        var clients = 0
        val r = EntiaRepository(accounts, prefs, { clients++; Gateway() }, dispatcher = dispatcher(), io = dispatcher())
        try {
            state(r) { !it.restoring }; r.setForeground(true)
            r.signIn("bob", "test"); r.demo(); settle()
            assertEquals(0, accounts.loads); assertEquals(0, clients)
            assertFalse(r.state.value.preferences.disclaimerAccepted)
            assertNull(r.state.value.username)
            r.acceptDisclaimer(); ready(r)
            assertTrue(prefs.value.disclaimerAccepted); assertEquals(1, clients)
            r.logout(); state(r) { !it.restoring && it.username == null }
            assertTrue(prefs.value.disclaimerAccepted)
        } finally { r.close() }
        val restored = EntiaRepository(accounts, prefs, { clients++; Gateway() }, dispatcher = dispatcher(), io = dispatcher())
        try {
            val current = state(restored) { !it.restoring }
            assertTrue(current.preferences.disclaimerAccepted)
            assertEquals(1, clients)
        } finally { restored.close() }
    }

    @Test fun failedAgreementPersistenceKeepsTheGateClosedUntilRetrySucceeds() = scenario {
        val accounts = Accounts(Credentials("alice", "test"))
        val prefs = Prefs(Preferences()).apply { failSave = true }
        var clients = 0
        val r = EntiaRepository(accounts, prefs, { clients++; Gateway() }, dispatcher = dispatcher(), io = dispatcher())
        try {
            state(r) { !it.restoring }; r.acceptDisclaimer()
            val current = state(r) { it.error != null && !it.acceptingDisclaimer }
            assertFalse(current.preferences.disclaimerAccepted)
            assertEquals(0, accounts.loads); assertEquals(0, clients)
            prefs.failSave = false; r.acceptDisclaimer(); ready(r)
            assertTrue(r.state.value.preferences.disclaimerAccepted)
            assertEquals(1, accounts.loads); assertEquals(1, clients)
        } finally { r.close() }
    }

    @Test fun localDeviceEditsPersistAcrossRepositoryRestoreAndNeverWriteToEntia() = scenario {
        val accounts = Accounts(Credentials("alice", "test")); val prefs = Prefs(); val api = Gateway()
        val r = EntiaRepository(accounts, prefs, { api }, dispatcher = dispatcher(), io = dispatcher())
        try {
            ready(r); r.selectRoom(1)
            r.customizeDevice(1, "Reading lamp", LightIcon.TableLamp); r.hideDevice(1, true); settle()
            assertEquals("Reading lamp", prefs.value.deviceNames[1])
            assertEquals(setOf(1L), prefs.value.hiddenDevices)
            assertNull(r.state.value.selectedRoom); assertTrue(r.state.value.home.visibleRooms.isEmpty())
            r.refresh(); state(r) { !it.syncing }; settle()
            assertEquals("Reading lamp", r.state.value.home.devices.getValue(1).name)
            assertTrue(r.state.value.home.devices.getValue(1).hidden)
            assertEquals(0, api.writes)
        } finally { r.close() }
        val fresh = Gateway()
        val restored = EntiaRepository(accounts, prefs, { fresh }, dispatcher = dispatcher(), io = dispatcher())
        try {
            ready(restored)
            assertEquals("Reading lamp", restored.state.value.home.devices.getValue(1).name)
            assertEquals(LightIcon.TableLamp, restored.state.value.home.devices.getValue(1).lightIcon)
            restored.hideDevice(1, false); settle()
            assertEquals(listOf(1L), restored.state.value.home.visibleRooms.map { it.id })
            restored.customizeDevice(1, "", LightIcon.Bulb); settle()
            assertEquals(restored.state.value.home.devices.getValue(1).serverName, restored.state.value.home.devices.getValue(1).name)
            assertTrue(prefs.value.deviceNames.isEmpty()); assertEquals(0, fresh.writes)
        } finally { restored.close() }
    }

    @Test fun demoLocalEditsAndFocusPersistWithoutSavingCredentialsOrOpeningClient() = scenario {
        val accounts = Accounts(); val prefs = Prefs(); var clients = 0
        val r = EntiaRepository(accounts, prefs, { clients++; Gateway() }, dispatcher = dispatcher(), io = dispatcher())
        try {
            state(r) { !it.restoring }; r.demo()
            r.customizeDevice(101, "Corner lamp", LightIcon.Pendant)
            r.updatePreferences { it.copy(displayFocus = "temperature") }; settle()
            r.logout(); r.demo()
            assertEquals("Corner lamp", r.state.value.home.devices.getValue(101).name)
            assertEquals("temperature", r.state.value.preferences.displayFocus)
            assertEquals(0, clients); assertNull(accounts.record)
        } finally { r.close() }
    }

    @Test fun socketFailureKeepsRestReadingsAndCommandsUsable() = scenario {
        val failed = CompletableDeferred<Unit>()
        val api = Gateway().apply { listenHandler = { failed.complete(Unit); throw IOException() } }
        val r = EntiaRepository(Accounts(Credentials("alice", "test")), Prefs(), { api }, dispatcher = dispatcher(), io = dispatcher())
        try {
            r.setForeground(true); ready(r); failed.await(); settle()
            assertFalse(r.state.value.socketConnected); assertTrue(r.state.value.restReachable); assertNull(r.state.value.error)
            r.command(1, Attr.LIGHT, 1)
            state(r) { it.commands[1]?.phase == CommandPhase.Confirmed }
            assertEquals(1, api.writes)
        } finally { r.close() }
    }

    @Test fun buttonPressWhenDisconnectedResetsConnectionAndExecutesCommand() = scenario {
        var failDevices = false
        val api = Gateway().apply {
            deviceHandler = {
                if (failDevices) throw IOException("Network down")
                telemetry(value)
            }
        }
        val r = EntiaRepository(Accounts(Credentials("alice", "test")), Prefs(), { api }, dispatcher = dispatcher(), io = dispatcher())
        try {
            r.setForeground(true); ready(r); settle()
            failDevices = true
            r.refresh()
            state(r) { !it.syncing && !it.restReachable }
            failDevices = false
            // Pressing a control button while restReachable is false resets the connection and applies the command
            r.command(1, Attr.LIGHT, 1)
            state(r) { it.restReachable && it.commands[1]?.phase == CommandPhase.Confirmed }
            assertEquals(1, api.writes)
            assertEquals(1, r.state.value.home.devices.getValue(1).value(Attr.LIGHT))
        } finally { r.close() }
    }

    @Test fun blindPresetsPersistPerDeviceAndClearOnLogoutWhileCoolingSettingStays() = scenario {
        val accounts = Accounts(Credentials("alice", "test")); val prefs = Prefs()
        val r = EntiaRepository(accounts, prefs, { Gateway() }, dispatcher = dispatcher(), io = dispatcher())
        try {
            ready(r)
            assertTrue(r.state.value.preferences.dynamicColor)
            assertFalse(r.state.value.preferences.hideCooling)
            r.updatePreferences { it.copy(hideCooling = true) }
            r.setBlindPreset(1, 1, 25)
            r.setBlindPreset(1, 2, 80)
            settle()
            assertEquals(BlindSlots(25, 80), r.state.value.preferences.blindPresets[1L])
            assertEquals(BlindSlots(25, 80), prefs.value.blindPresets[1L])
            assertTrue(prefs.value.hideCooling)
            r.logout(); state(r) { !it.restoring && it.username == null }
            assertTrue(r.state.value.preferences.blindPresets.isEmpty())
            assertTrue(r.state.value.preferences.hideCooling)
        } finally { r.close() }
    }

    @Test fun smartMainsGuardRestores230VWhenOffAndKeepsMainsOnWhenTogglingHueOff() = scenario {
        val entiaWrites = mutableListOf<Int>()
        val api = Gateway().apply {
            value = 0 // Entia 230V relay starts OFF
            setHandler = { v ->
                entiaWrites += v
                value = v
            }
        }
        val hueCalls = mutableListOf<String>()
        var hueTargets = listOf(
            HueTarget(
                id = "hue-1",
                type = "light",
                name = "Desk Bulb",
                kind = HueTargetKind.Light,
                on = false,
                brightness = 70,
                supportsDimming = true,
                mirek = 370,
                supportsColorTemp = true,
                xy = 0.45 to 0.41,
                supportsColor = true,
            )
        )
        val fakeHueGateway = object : HueGateway {
            override suspend fun fetchTargets() = hueTargets
            override suspend fun setPower(targetType: String, id: String, on: Boolean) {
                hueCalls += "power:$targetType:$id:$on"
                hueTargets = hueTargets.map { if (it.id == id) it.copy(on = on) else it }
            }
            override suspend fun setBrightness(targetType: String, id: String, brightness: Int) {
                hueCalls += "brightness:$targetType:$id:$brightness"
                hueTargets = hueTargets.map { if (it.id == id) it.copy(on = true, brightness = brightness) else it }
            }
            override suspend fun setColorTemp(targetType: String, id: String, mirek: Int) {
                hueCalls += "mirek:$targetType:$id:$mirek"
                hueTargets = hueTargets.map { if (it.id == id) it.copy(on = true, mirek = mirek) else it }
            }
            override suspend fun setColorXy(targetType: String, id: String, x: Double, y: Double) {
                hueCalls += "xy:$targetType:$id:$x,$y"
                hueTargets = hueTargets.map { if (it.id == id) it.copy(on = true, xy = x to y) else it }
            }
            override fun close() = Unit
        }
        val hueStore = object : HueStore {
            var creds: HueCredentials? = HueCredentials("192.168.1.50", "app-key-1")
            override fun saveHue(credentials: HueCredentials) { creds = credentials }
            override fun loadHue(): HueCredentials? = creds
            override fun clearHue() { creds = null }
        }
        val prefs = Prefs(
            Preferences(disclaimerAccepted = true, hueEnabled = true).forAccount("alice").copy(
                hueBindings = mapOf(1L to HueBinding("hue-1", "light", "Desk Bulb"))
            )
        )
        val r = EntiaRepository(
            Accounts(Credentials("alice", "pw")),
            prefs,
            { api },
            dispatcher = dispatcher(),
            io = dispatcher(),
            hueStore = hueStore,
            hueGatewayFactory = { _, _ -> fakeHueGateway },
        )
        try {
            r.setForeground(true)
            ready(r)
            state(r) { it.hue.paired && it.hue.targets.isNotEmpty() }

            // 1. Mains relay is 0; tapping Hue-managed light restores Entia 230V relay (1) AND turns Hue ON
            r.toggleHueLight(1L)
            state(r) { it.home.devices.getValue(1L).value(Attr.LIGHT) == 1 && it.commands[1L]?.phase == CommandPhase.Confirmed }
            settle()
            assertEquals(listOf(1), entiaWrites)
            assertEquals(listOf("power:light:hue-1:true"), hueCalls)
            assertTrue(r.state.value.hue.targets.single().on)

            // 2. Mains relay is 1 and Hue is ON; toggling turns Hue OFF while keeping Entia 230V relay at 1
            r.toggleHueLight(1L)
            settle()
            assertEquals(listOf(1), entiaWrites) // No Attr.LIGHT = 0 write!
            assertEquals(1, r.state.value.home.devices.getValue(1L).value(Attr.LIGHT))
            assertEquals("power:light:hue-1:false", hueCalls.last())
            assertFalse(r.state.value.hue.targets.single().on)

            // 3. Explicit mains cut cuts 230V relay to 0
            r.command(1L, Attr.LIGHT, 0)
            state(r) { it.home.devices.getValue(1L).value(Attr.LIGHT) == 0 && it.commands[1L]?.phase == CommandPhase.Confirmed }
            assertEquals(listOf(1, 0), entiaWrites)

            // 4. Setting brightness while mains is 0 automatically restores 230V relay to 1
            r.setHueBrightness(1L, 55)
            state(r) { it.home.devices.getValue(1L).value(Attr.LIGHT) == 1 && it.commands[1L]?.phase == CommandPhase.Confirmed }
            settle()
            assertEquals(listOf(1, 0, 1), entiaWrites)
            assertEquals("brightness:light:hue-1:55", hueCalls.last())
            assertEquals(55, r.state.value.hue.targets.single().brightness)

            // 5. Color temp & XY update state cleanly
            r.setHueColorTemp(1L, 285)
            r.setHueColorXy(1L, 0.285, 0.205)
            settle()
            assertEquals(285, r.state.value.hue.targets.single().mirek)
            assertEquals(0.285, r.state.value.hue.targets.single().xy!!.first, 0.001)
            assertEquals(listOf(1, 0, 1), entiaWrites)
        } finally {
            r.close()
        }
    }

    @Test fun hueGuidedPairingDemoSimulationAndDisconnectWork() = scenario {
        var pairAttempts = 0
        val hueStore = object : HueStore {
            var creds: HueCredentials? = null
            override fun saveHue(credentials: HueCredentials) { creds = credentials }
            override fun loadHue(): HueCredentials? = creds
            override fun clearHue() { creds = null }
        }
        val r = EntiaRepository(
            Accounts(Credentials("alice", "pw")),
            Prefs(Preferences(disclaimerAccepted = true)),
            { Gateway() },
            dispatcher = dispatcher(),
            io = dispatcher(),
            hueStore = hueStore,
            hueGatewayFactory = { _, _ ->
                object : HueGateway {
                    override suspend fun fetchTargets() = listOf(HueTarget("g-1", "grouped_light", "Living room", HueTargetKind.Room, true, 90, true))
                    override suspend fun setPower(targetType: String, id: String, on: Boolean) = Unit
                    override suspend fun setBrightness(targetType: String, id: String, brightness: Int) = Unit
                    override suspend fun setColorTemp(targetType: String, id: String, mirek: Int) = Unit
                    override suspend fun setColorXy(targetType: String, id: String, x: Double, y: Double) = Unit
                    override fun close() = Unit
                }
            },
            hueDiscoverer = { listOf(DiscoveredBridge("192.168.1.99", "001788fffe99", "mdns")) },
            huePairer = {
                pairAttempts++
                if (pairAttempts < 3) HuePairResult.LinkButtonNotPressed
                else HuePairResult.Success("paired-key-xyz", "client-key")
            },
            huePollDelayMillis = 5L,
            huePairingTimeoutSeconds = 30,
        )
        try {
            r.setForeground(true)
            ready(r)
            r.setHueEnabled(true)
            r.discoverHueBridges()
            state(r) { it.hue.discoveredBridges.isNotEmpty() }
            assertEquals("192.168.1.99", r.state.value.hue.discoveredBridges.single().ip)

            r.pairHueBridge("192.168.1.99")
            state(r) { it.hue.paired && it.hue.targets.isNotEmpty() }
            assertEquals(3, pairAttempts)
            assertEquals("paired-key-xyz", hueStore.creds?.appKey)
            assertEquals("Living room", r.state.value.hue.targets.single().name)

            r.disconnectHue()
            settle()
            assertFalse(r.state.value.hue.paired)
            assertNull(hueStore.creds)

            // Logout clears hueBindings while keeping hueEnabled
            r.setHueBinding(1L, HueBinding("g-1", "grouped_light", "Living room"))
            settle()
            assertEquals(1, r.state.value.preferences.hueBindings.size)
            r.logout()
            state(r) { !it.restoring && it.username == null }
            assertTrue(r.state.value.preferences.hueEnabled)
            assertTrue(r.state.value.preferences.hueBindings.isEmpty())

            // Demo mode with hueEnabled provides simulated targets offline with Smart Mains Guard
            r.demo()
            assertTrue(r.state.value.hue.paired)
            assertTrue(r.state.value.hue.targets.any { it.type == "light" })
            assertTrue(r.state.value.hue.targets.any { it.type == "grouped_light" })
            val firstTarget = r.state.value.hue.targets.first { it.type == "light" }
            r.setHueBinding(201L, HueBinding(firstTarget.id, firstTarget.type, firstTarget.name))
            assertEquals(0, r.state.value.home.devices.getValue(201L).value(Attr.LIGHT))
            r.toggleHueLight(201L)
            assertEquals(1, r.state.value.home.devices.getValue(201L).value(Attr.LIGHT))
            assertTrue(r.state.value.hue.targetFor(r.state.value.preferences.hueBindings[201L])!!.on)

            // Multiple bulbs on a single Entia outlet (101L)
            val twoBulbs = r.state.value.hue.targets.filter { it.type == "light" }.take(2)
            assertEquals(2, twoBulbs.size)
            val multiBinding = HueBinding(twoBulbs.map { HueTargetRef(it.id, it.type, it.name) })
            r.setHueBinding(101L, multiBinding)
            r.setHuePower(101L, false)
            assertFalse(r.state.value.hue.targetsFor(multiBinding).any { it.on })
            // Toggling turns ALL linked bulbs ON
            r.toggleHueLight(101L)
            assertTrue(r.state.value.hue.targetsFor(multiBinding).all { it.on })
            // Individual targetId brightness changes only that bulb
            r.setHueBrightness(101L, 35, targetId = twoBulbs[0].id)
            r.setHueBrightness(101L, 85, targetId = twoBulbs[1].id)
            val updated = r.state.value.hue.targetsFor(multiBinding)
            assertEquals(35, updated[0].brightness)
            assertEquals(85, updated[1].brightness)
            assertEquals(60, r.state.value.hue.targetFor(multiBinding)!!.brightness)
        } finally {
            r.close()
        }
    }

    @Test fun offlineStartupRestoresSavedAccountWithoutDroppingToSignInAndRecoversWhenOnline() = scenario {
        val api = Gateway().apply {
            flatHandler = { throw IOException("Offline") }
            deviceHandler = { throw IOException("Offline") }
        }
        val r = EntiaRepository(Accounts(Credentials("alice", "secret")), Prefs(), { api }, dispatcher = dispatcher(), io = dispatcher())
        try {
            val offline = state(r) { !it.restoring && !it.syncing }
            assertEquals("alice", offline.username)
            assertFalse(offline.needsSignIn)
            assertFalse(offline.restReachable)
            assertTrue(offline.home.rooms.isEmpty())

            api.flatHandler = { flat }
            api.deviceHandler = { telemetry(1) }
            r.refresh()
            val online = ready(r)
            assertEquals("alice", online.username)
            assertFalse(online.needsSignIn)
            assertTrue(online.restReachable)
            assertEquals(listOf(1L), online.home.visibleRooms.map { it.id })
            assertEquals(1, online.home.devices.getValue(1L).value(Attr.LIGHT))
        } finally {
            r.close()
        }
    }

    @Test fun tokenExpiryInRefreshSetAndListenRenewsWithoutDroppingToSignIn() = scenario {
        var failRefreshAuth = true
        var failWriteAuth = true
        var listenAttempts = 0
        val listenRetried = CompletableDeferred<Unit>()
        val api = Gateway().apply {
            flatHandler = {
                if (failRefreshAuth) {
                    failRefreshAuth = false
                    throw AuthenticationException()
                }
                flat
            }
            setHandler = { v ->
                if (failWriteAuth) {
                    failWriteAuth = false
                    throw AuthenticationException()
                }
                value = v
            }
            listenHandler = {
                listenAttempts++
                if (listenAttempts == 1) {
                    throw AuthenticationException()
                } else {
                    listenRetried.complete(Unit)
                }
            }
        }
        val accounts = Accounts(Credentials("alice", "secret"))
        val r = EntiaRepository(accounts, Prefs(), { api }, dispatcher = dispatcher(), io = dispatcher())
        try {
            val loaded = ready(r)
            assertEquals("alice", loaded.username)
            assertFalse(loaded.needsSignIn)
            assertTrue(api.clearTokenCalls >= 1)
            assertTrue(api.renewCalls >= 1)

            val renewsBeforeSocket = api.renewCalls
            r.setForeground(true)
            withTimeout(5_000) { listenRetried.await() }
            state(r) { it.socketConnected }
            assertEquals(2, listenAttempts)
            assertTrue(api.renewCalls > renewsBeforeSocket)
            assertFalse(r.state.value.needsSignIn)

            val renewsBeforeWrite = api.renewCalls
            r.command(1L, Attr.LIGHT, 1)
            state(r) { it.commands[1L]?.phase == CommandPhase.Confirmed }
            assertEquals(1, r.state.value.home.devices.getValue(1L).value(Attr.LIGHT))
            assertTrue(api.renewCalls > renewsBeforeWrite)
            assertFalse(r.state.value.needsSignIn)
            assertEquals("alice", r.state.value.username)

            // Explicit credential replacement still rejects bad credentials without overwriting saved account
            val badGateway = Gateway().apply { validateHandler = { throw AuthenticationException() } }
            val r2 = EntiaRepository(Accounts(), Prefs(), { badGateway }, dispatcher = dispatcher(), io = dispatcher(),
                text = TextCatalog { key, _ -> key.name })
            try {
                state(r2) { !it.restoring }
                r2.signIn("bob", "wrong")
                val rejected = state(r2) { !it.signingIn && it.loginError != null }
                assertEquals("CredentialsRejected", rejected.loginError)
                assertNull(rejected.username)
            } finally {
                r2.close()
            }
        } finally {
            r.close()
        }
    }

    @Test fun standaloneHueLightsAddCustomizeControlAndRemoveWithoutEntiaWrites() = scenario {
        val multiRoomFlat = Json.parseToJsonElement(
            """{"flat":{"floors":[{"label":"Floor","rooms":[{"id":1,"label":"Living","devices":[{"id":1}]},{"id":2,"label":"Guest","devices":[]}]}]}}"""
        ).jsonObject
        val api = Gateway().apply {
            definition = multiRoomFlat
            setHandler = { error("Standalone Hue light must never call Entia set") }
        }
        val hueCalls = mutableListOf<String>()
        var hueTargets = listOf(
            HueTarget("hue-1", "light", "Floor Lamp", HueTargetKind.Light, false, 60, true, 370, 153, 500, true, 0.4 to 0.4, true),
            HueTarget("hue-2", "light", "Strip", HueTargetKind.Light, false, 80, true),
        )
        val fakeHueGateway = object : HueGateway {
            override suspend fun fetchTargets() = hueTargets
            override suspend fun setPower(targetType: String, id: String, on: Boolean) {
                hueCalls += "power:$targetType:$id:$on"
                hueTargets = hueTargets.map { if (it.id == id) it.copy(on = on) else it }
            }
            override suspend fun setBrightness(targetType: String, id: String, brightness: Int) {
                hueCalls += "brightness:$targetType:$id:$brightness"
                hueTargets = hueTargets.map { if (it.id == id) it.copy(on = true, brightness = brightness) else it }
            }
            override suspend fun setColorTemp(targetType: String, id: String, mirek: Int) {
                hueCalls += "mirek:$targetType:$id:$mirek"
                hueTargets = hueTargets.map { if (it.id == id) it.copy(on = true, mirek = mirek) else it }
            }
            override suspend fun setColorXy(targetType: String, id: String, x: Double, y: Double) {
                hueCalls += "xy:$targetType:$id:$x,$y"
                hueTargets = hueTargets.map { if (it.id == id) it.copy(on = true, xy = x to y) else it }
            }
            override fun close() = Unit
        }
        val hueStore = object : HueStore {
            var creds: HueCredentials? = HueCredentials("192.168.1.50", "key")
            override fun saveHue(credentials: HueCredentials) { creds = credentials }
            override fun loadHue(): HueCredentials? = creds
            override fun clearHue() { creds = null }
        }
        val prefs = Prefs(Preferences(disclaimerAccepted = true, hueEnabled = true))
        val r = EntiaRepository(
            Accounts(Credentials("alice", "pw")),
            prefs,
            { api },
            dispatcher = dispatcher(),
            io = dispatcher(),
            hueStore = hueStore,
            hueGatewayFactory = { _, _ -> fakeHueGateway },
        )
        try {
            r.setForeground(true)
            ready(r)
            state(r) { it.hue.paired && it.hue.targets.isNotEmpty() }
            assertEquals(listOf(1L), r.state.value.home.visibleRooms.map { it.id })

            r.addStandaloneHueLight(
                roomId = 2L,
                name = "",
                icon = LightIcon.FloorLamp,
                targets = listOf(HueTargetRef("hue-1", "light", "Floor Lamp")),
            )
            settle()

            val added = r.state.value.preferences.standaloneHueLights.single()
            assertEquals(-1001L, added.id)
            assertEquals(2L, added.roomId)
            assertEquals("Floor Lamp", added.name)
            assertEquals(LightIcon.FloorLamp, added.icon)
            assertEquals(listOf(1L, 2L), r.state.value.home.visibleRooms.map { it.id })
            val synth = r.state.value.home.devices.getValue(-1001L)
            assertTrue(synth.isStandaloneHue)
            assertEquals("Floor Lamp", synth.name)

            r.toggleHueLight(-1001L)
            r.setHueBrightness(-1001L, 42)
            r.setHueColorTemp(-1001L, 300)
            r.setHueColorXy(-1001L, 0.3, 0.3)
            settle()
            assertEquals(0, api.writes)
            assertTrue(r.state.value.hue.targets.first { it.id == "hue-1" }.on)
            assertEquals(42, r.state.value.hue.targets.first { it.id == "hue-1" }.brightness)
            assertEquals(300, r.state.value.hue.targets.first { it.id == "hue-1" }.mirek)
            assertEquals(0.3, r.state.value.hue.targets.first { it.id == "hue-1" }.xy!!.first, 0.001)
            assertEquals(listOf("power:light:hue-1:true", "brightness:light:hue-1:42", "mirek:light:hue-1:300", "xy:light:hue-1:0.3,0.3"), hueCalls)

            r.customizeDevice(-1001L, "Corner Lamp", LightIcon.TableLamp)
            r.setHueBinding(
                -1001L,
                HueBinding(listOf(HueTargetRef("hue-1", "light", "Floor Lamp"), HueTargetRef("hue-2", "light", "Strip"))),
            )
            settle()
            val edited = r.state.value.preferences.standaloneHueLights.single()
            assertEquals("Corner Lamp", edited.name)
            assertEquals(LightIcon.TableLamp, edited.icon)
            assertEquals(2, edited.targets.size)
            assertEquals("Corner Lamp", r.state.value.home.devices.getValue(-1001L).name)

            r.hideDevice(-1001L, true)
            settle()
            assertTrue(r.state.value.preferences.standaloneHueLights.isEmpty())
            assertFalse(-1001L in r.state.value.home.devices)
            assertTrue(r.state.value.preferences.hiddenDevices.isEmpty())
            assertEquals(listOf(1L), r.state.value.home.visibleRooms.map { it.id })
            assertEquals(0, api.writes)

            // Logout clears standalone lights while keeping hueEnabled
            r.addStandaloneHueLight(1L, "Temp", LightIcon.Bulb, listOf(HueTargetRef("hue-1", "light", "Floor Lamp")))
            settle()
            assertEquals(1, r.state.value.preferences.standaloneHueLights.size)
            r.logout()
            state(r) { !it.restoring && it.username == null }
            assertTrue(r.state.value.preferences.hueEnabled)
            assertTrue(r.state.value.preferences.standaloneHueLights.isEmpty())
        } finally {
            r.close()
        }
    }

    @Test fun commandWhileSocketDisconnectedDoesNotCancelWorkersOrDoubleRefresh() = scenario {
        val socketFailed = CompletableDeferred<Unit>()
        var listenCalls = 0
        val api = Gateway().apply {
            listenHandler = {
                listenCalls++
                socketFailed.complete(Unit)
                throw IOException("Transient socket drop")
            }
        }
        val r = EntiaRepository(Accounts(Credentials("alice", "pw")), Prefs(), { api }, dispatcher = dispatcher(), io = dispatcher())
        try {
            r.setForeground(true)
            ready(r)
            withTimeout(3_000) { socketFailed.await() }
            settle()
            assertFalse(r.state.value.socketConnected)
            assertTrue(r.state.value.restReachable)

            val callsBeforeCommand = api.deviceCalls
            val listensBeforeCommand = listenCalls
            r.command(1L, Attr.LIGHT, 1)
            state(r) { it.commands[1L]?.phase == CommandPhase.Confirmed }
            assertEquals(1, api.writes)
            assertEquals(callsBeforeCommand + 1, api.deviceCalls)
            assertEquals(listensBeforeCommand, listenCalls)
        } finally {
            r.close()
        }
    }

    @Test fun overlappingRefreshHueCallsAreDeduplicated() = scenario {
        var fetchCalls = 0
        val fetchStarted = CompletableDeferred<Unit>()
        val fetchRelease = CompletableDeferred<Unit>()
        val hueStore = object : HueStore {
            var creds: HueCredentials? = HueCredentials("192.168.1.50", "key")
            override fun saveHue(credentials: HueCredentials) { creds = credentials }
            override fun loadHue(): HueCredentials? = creds
            override fun clearHue() { creds = null }
        }
        val r = EntiaRepository(
            Accounts(Credentials("alice", "pw")),
            Prefs(Preferences(disclaimerAccepted = true, hueEnabled = true)),
            { Gateway() },
            dispatcher = dispatcher(),
            io = dispatcher(),
            hueStore = hueStore,
            hueGatewayFactory = { _, _ ->
                object : HueGateway {
                    override suspend fun fetchTargets(): List<HueTarget> {
                        fetchCalls++
                        fetchStarted.complete(Unit)
                        fetchRelease.await()
                        return listOf(HueTarget("hue-1", "light", "Lamp", HueTargetKind.Light, true, 75, true))
                    }
                    override suspend fun setPower(targetType: String, id: String, on: Boolean) = Unit
                    override suspend fun setBrightness(targetType: String, id: String, brightness: Int) = Unit
                    override suspend fun setColorTemp(targetType: String, id: String, mirek: Int) = Unit
                    override suspend fun setColorXy(targetType: String, id: String, x: Double, y: Double) = Unit
                    override fun close() = Unit
                }
            },
        )
        try {
            r.setForeground(true)
            ready(r)
            withTimeout(3_000) { fetchStarted.await() }
            assertTrue(r.state.value.hue.syncing)
            r.refreshHue()
            r.refreshHue()
            settle()
            assertEquals(1, fetchCalls)

            fetchRelease.complete(Unit)
            state(r) { !it.hue.syncing && it.hue.targets.isNotEmpty() }
            assertEquals(1, fetchCalls)
            assertEquals("Lamp", r.state.value.hue.targets.single().name)
        } finally {
            fetchRelease.complete(Unit)
            r.close()
        }
    }

    @Test fun multiHueOnOneEntiaOutletTurnsOnEntiaAndPreservesSiblingStates() = scenario {
        val entiaWrites = mutableListOf<Int>()
        val hueCalls = mutableListOf<String>()
        var hueTargets = listOf(
            HueTarget("hue-1", "light", "Ceiling 1", HueTargetKind.Light, false, 70, true),
            HueTarget("hue-2", "light", "Ceiling 2", HueTargetKind.Light, false, 80, true),
            HueTarget("hue-3", "light", "Ceiling 3", HueTargetKind.Light, true, 35, true),
        )
        val api = Gateway().apply {
            setHandler = { v ->
                entiaWrites += v
                value = v
                if (v == 1) {
                    // Simulate physical 230V mains power-on resetting all bulbs on the outlet to ON at 100%.
                    hueTargets = hueTargets.map { it.copy(on = true, brightness = 100) }
                }
            }
        }
        val fakeHueGateway = object : HueGateway {
            override suspend fun fetchTargets() = hueTargets
            override suspend fun setPower(targetType: String, id: String, on: Boolean) {
                hueCalls += "power:$targetType:$id:$on"
                hueTargets = hueTargets.map { if (it.id == id) it.copy(on = on) else it }
            }
            override suspend fun setBrightness(targetType: String, id: String, brightness: Int) {
                hueCalls += "brightness:$targetType:$id:$brightness"
                hueTargets = hueTargets.map { if (it.id == id) it.copy(on = true, brightness = brightness) else it }
            }
            override suspend fun setColorTemp(targetType: String, id: String, mirek: Int) = Unit
            override suspend fun setColorXy(targetType: String, id: String, x: Double, y: Double) = Unit
            override fun close() = Unit
        }
        val hueStore = object : HueStore {
            override fun saveHue(credentials: HueCredentials) = Unit
            override fun loadHue() = HueCredentials("192.168.1.50", "key")
            override fun clearHue() = Unit
        }
        val prefs = Prefs(
            Preferences(disclaimerAccepted = true, hueEnabled = true).forAccount("alice").copy(
                standaloneHueLights = listOf(
                    StandaloneHueLight(-1001L, 1L, "Bulb 1", LightIcon.Bulb, listOf(HueTargetRef("hue-1", "light", "Ceiling 1")), entiaDeviceId = 1L),
                    StandaloneHueLight(-1002L, 1L, "Bulb 2", LightIcon.Bulb, listOf(HueTargetRef("hue-2", "light", "Ceiling 2")), entiaDeviceId = 1L),
                    StandaloneHueLight(-1003L, 1L, "Bulb 3", LightIcon.Bulb, listOf(HueTargetRef("hue-3", "light", "Ceiling 3")), entiaDeviceId = 1L),
                )
            )
        )
        val r = EntiaRepository(
            Accounts(Credentials("alice", "pw")),
            prefs,
            { api },
            dispatcher = dispatcher(),
            io = dispatcher(),
            hueStore = hueStore,
            hueGatewayFactory = { _, _ -> fakeHueGateway },
            hueMainsBootDelayMillis = 30L,
        )
        try {
            r.setForeground(true)
            ready(r)
            state(r) { it.hue.paired && it.hue.targets.size == 3 }
            assertEquals(0, r.state.value.home.devices.getValue(1L).value(Attr.LIGHT))

            // 1. Mains is OFF: turning ON Bulb 1 (-1001L) turns ON Entia switch 1L, turns hue-1 ON,
            // keeps hue-2 OFF, and preserves hue-3 at 35% ON after 230V mains boot delay.
            r.toggleHueLight(-1001L)
            state(r) { it.home.devices.getValue(1L).value(Attr.LIGHT) == 1 && it.commands[1L]?.phase == CommandPhase.Confirmed }
            delay(80L)
            settle()
            assertEquals(listOf(1), entiaWrites)
            assertTrue(r.state.value.hue.targets.first { it.id == "hue-1" }.on)
            assertFalse(r.state.value.hue.targets.first { it.id == "hue-2" }.on)
            assertTrue(r.state.value.hue.targets.first { it.id == "hue-3" }.on)
            assertEquals(35, r.state.value.hue.targets.first { it.id == "hue-3" }.brightness)
            assertTrue(hueTargets.first { it.id == "hue-1" }.on)
            assertFalse(hueTargets.first { it.id == "hue-2" }.on)
            assertTrue(hueTargets.first { it.id == "hue-3" }.on)
            assertEquals(35, hueTargets.first { it.id == "hue-3" }.brightness)

            // 2. Mains is already ON: toggling Bulb 1 OFF must NOT turn off Entia switch 1L and must NOT touch sibling bulbs.
            hueCalls.clear()
            r.toggleHueLight(-1001L)
            settle()
            assertEquals(listOf(1), entiaWrites)
            assertEquals(listOf("power:light:hue-1:false"), hueCalls)
            assertFalse(hueTargets.first { it.id == "hue-2" }.on)
            assertEquals(35, hueTargets.first { it.id == "hue-3" }.brightness)

            // 3. Direct multi-bulb Entia binding: turning mains OFF then adjusting only hue-1 restores Entia 1L and keeps hue-2 OFF.
            r.setHueBinding(1L, HueBinding(listOf(HueTargetRef("hue-1", "light", "Ceiling 1"), HueTargetRef("hue-2", "light", "Ceiling 2"))))
            r.command(1L, Attr.LIGHT, 0)
            state(r) { it.home.devices.getValue(1L).value(Attr.LIGHT) == 0 && it.commands[1L]?.phase == CommandPhase.Confirmed }
            r.setHueBrightness(1L, 45, targetId = "hue-1")
            state(r) { it.home.devices.getValue(1L).value(Attr.LIGHT) == 1 && it.commands[1L]?.phase == CommandPhase.Confirmed }
            delay(80L)
            settle()
            assertTrue(hueTargets.first { it.id == "hue-1" }.on)
            assertEquals(45, hueTargets.first { it.id == "hue-1" }.brightness)
            assertFalse(hueTargets.first { it.id == "hue-2" }.on)
        } finally {
            r.close()
        }
    }

    @Test fun manualLanguageSelectionUpdatesCatalogAndPersistsAcrossLogout() = scenario {
        lateinit var repoRef: EntiaRepository
        val prefs = Prefs(Preferences(disclaimerAccepted = true))
        val catalog = TextCatalog { key, number ->
            val lang = repoRef.state.value.preferences.language
            "${lang}:${key.name}:${number ?: ""}"
        }
        repoRef = EntiaRepository(
            Accounts(),
            prefs,
            { Gateway() },
            dispatcher = dispatcher(),
            io = dispatcher(),
            text = catalog,
        )
        try {
            state(repoRef) { !it.restoring }
            repoRef.setForeground(true)
            repoRef.demo()
            assertEquals("system:DemoHome:", repoRef.state.value.username)
            assertEquals("system:LivingRoom:", repoRef.state.value.home.rooms.first().name)

            // Mutate simulated device state before switching language; it must survive translation.
            repoRef.command(101L, Attr.LIGHT, 0)
            assertEquals(0, repoRef.state.value.home.devices.getValue(101L).value(Attr.LIGHT))

            repoRef.setLanguage("sl")
            settle()
            assertEquals("sl", repoRef.state.value.preferences.language)
            assertEquals("sl", prefs.value.language)
            assertEquals("sl:DemoHome:", repoRef.state.value.username)
            assertEquals("sl:LivingRoom:", repoRef.state.value.home.rooms.first().name)
            assertEquals(0, repoRef.state.value.home.devices.getValue(101L).value(Attr.LIGHT))

            repoRef.setLanguage("invalid-code")
            settle()
            assertEquals("system", repoRef.state.value.preferences.language)

            repoRef.setLanguage("en")
            settle()
            repoRef.logout()
            state(repoRef) { !it.restoring && !it.demo }
            assertEquals("en", repoRef.state.value.preferences.language)
            assertEquals("en", prefs.value.language)
        } finally {
            repoRef.close()
        }
    }
}
