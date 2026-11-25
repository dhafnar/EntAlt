package si.hafnar.entalt.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import si.hafnar.entalt.domain.*
import kotlin.math.roundToInt
import kotlin.random.Random

enum class CommandPhase { Sending, Awaiting, Confirmed, Unconfirmed, Failed }
data class Command(val attribute: Int, val desired: Int, val phase: CommandPhase, val error: String? = null) {
    val pending get() = phase == CommandPhase.Sending || phase == CommandPhase.Awaiting
}
data class HueState(
    val bridgeIp: String? = null,
    val bridgeId: String? = null,
    val paired: Boolean = false,
    val pairing: Boolean = false,
    val pairingSecondsLeft: Int = 0,
    val discovering: Boolean = false,
    val discoveredBridges: List<DiscoveredBridge> = emptyList(),
    val syncing: Boolean = false,
    val targets: List<HueTarget> = emptyList(),
    val error: String? = null,
) {
    fun targetsFor(binding: HueBinding?): List<HueTarget> {
        if (binding == null || binding.targets.isEmpty()) return emptyList()
        return binding.targets.mapNotNull { ref ->
            targets.firstOrNull { it.id == ref.id && it.type == ref.type }
                ?: targets.firstOrNull { it.id == ref.id }
        }.distinctBy { "${it.type}:${it.id}" }
    }

    fun targetFor(binding: HueBinding?, targetId: String? = null): HueTarget? {
        val matched = targetsFor(binding)
        if (matched.isEmpty()) return null
        if (targetId != null) {
            return matched.firstOrNull { it.id == targetId } ?: matched.first()
        }
        if (matched.size == 1) return matched.single()
        val activeForBrightness = matched.filter { it.on && it.brightness != null }.ifEmpty { matched.filter { it.brightness != null } }
        val avgBrightness = activeForBrightness.mapNotNull { it.brightness }
            .takeIf { it.isNotEmpty() }?.average()?.roundToInt()?.coerceIn(1, 100)
        val ctTargets = matched.filter { it.supportsColorTemp }
        return HueTarget(
            id = matched.joinToString(",") { it.id },
            type = "multi",
            name = matched.joinToString(", ") { it.name },
            kind = HueTargetKind.Light,
            on = matched.any { it.on },
            brightness = avgBrightness,
            supportsDimming = matched.any { it.supportsDimming },
            mirek = ctTargets.firstNotNullOfOrNull { it.mirek },
            mirekMin = ctTargets.minOfOrNull { it.mirekMin } ?: 153,
            mirekMax = ctTargets.maxOfOrNull { it.mirekMax } ?: 500,
            supportsColorTemp = ctTargets.isNotEmpty(),
            xy = matched.firstNotNullOfOrNull { it.xy },
            supportsColor = matched.any { it.supportsColor },
        )
    }
}
data class AppState(
    val restoring: Boolean = true,
    val acceptingDisclaimer: Boolean = false,
    val signingIn: Boolean = false,
    val username: String? = null,
    val needsSignIn: Boolean = false,
    val demo: Boolean = false,
    val home: Home = Home(),
    val selectedRoom: Long? = null,
    val preferences: Preferences = Preferences(),
    val syncing: Boolean = false,
    val foreground: Boolean = false,
    val restReachable: Boolean = false,
    val socketConnected: Boolean = false,
    val error: String? = null,
    val loginError: String? = null,
    val commands: Map<Long, Command> = emptyMap(),
    val tilts: Map<Long, BlindTiltState> = emptyMap(),
    val hue: HueState = HueState(),
)

data class ResolvedLightState(
    val binding: HueBinding?,
    val target: HueTarget?,
    val linkedEntiaId: Long?,
    val entiaSwitch: Device?,
    val mainsOn: Boolean,
    val on: Boolean,
)

fun resolveLightState(
    home: Home,
    preferences: Preferences,
    hue: HueState,
    device: Device,
    targetId: String? = null,
): ResolvedLightState {
    val binding = if (preferences.hueEnabled) preferences.hueBindingFor(device.id) else null
    val target = if (binding != null) hue.targetFor(binding, targetId) else null
    val linkedEntiaId = if (preferences.hueEnabled) preferences.linkedEntiaSwitchId(device.id) else null
    val entiaSwitch = linkedEntiaId?.let { home.devices[it] } ?: device.takeUnless { it.isStandaloneHue }
    val mainsOn = entiaSwitch?.let { it.value(Attr.LIGHT) == 1 } ?: true
    val on = when {
        device.isStandaloneHue -> mainsOn && (target?.on == true)
        binding != null && target != null -> mainsOn && target.on
        else -> mainsOn
    }
    return ResolvedLightState(binding, target, linkedEntiaId, entiaSwitch, mainsOn, on)
}

/** All state changes run on one dispatcher. Session identity guards every suspension boundary. */
class EntiaRepository(
    private val accounts: AccountStore,
    private val preferences: PreferenceStore,
    private val gateway: (Credentials) -> EntiaGateway = { EntiaApi(it) },
    private val decodeLabel: (String) -> String = { it },
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val pollMillis: Long = 300_000,
    private val confirmationMillis: Long = 10_000,
    private val text: TextCatalog = EnglishText,
    private val hueStore: HueStore? = null,
    private val hueGatewayFactory: (String, String) -> HueGateway = { ip, key -> HueApi(ip, key) },
    private val hueDiscoverer: suspend () -> List<DiscoveredBridge> = { emptyList() },
    private val huePairer: suspend (String) -> HuePairResult = { ip -> HueApi.pair(ip) },
    private val huePollDelayMillis: Long = 1500L,
    private val huePairingTimeoutSeconds: Int = 30,
    private val hueMainsBootDelayMillis: Long = 1200L,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val accountLock = Mutex()
    private val preferenceLock = Mutex()
    private val hueLock = Mutex()
    private val mutable = MutableStateFlow(AppState())
    val state = mutable.asStateFlow()
    private var attempt = 0L
    private var authJob: Job? = null
    private var session: Session? = null
    private var loadedPreferences = Preferences()
    private var accountMutations = 0
    private var hueGateway: HueGateway? = null
    private var hueJob: Job? = null
    private var huePollJob: Job? = null
    private var hueRefreshJob: Job? = null
    private val hueBootSyncJobs = mutableMapOf<Long, Job>()
    private val hueMainsBootUntil = mutableMapOf<Long, Long>()

    private inner class Session(val api: EntiaGateway, var flat: JsonObject, var lastDevices: JsonElement? = null) {
        val jobs = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        var workers: Job? = null
        var refresh: Deferred<Unit>? = null
        var fetching = false
        val events = mutableListOf<AttributeEvent>()
        var resync = false
        fun close() { api.close(); jobs.cancel() }
    }

    init {
        authJob = scope.launch {
            val ticket = attempt
            loadedPreferences = try { withContext(io) { preferences.load() } } catch (e: CancellationException) { throw e }
                catch (_: Exception) { Preferences() }
            if (ticket != attempt) return@launch
            mutable.value = mutable.value.copy(preferences = loadedPreferences)
            if (!loadedPreferences.disclaimerAccepted) mutable.value = mutable.value.copy(restoring = false)
            else {
                if (loadedPreferences.hueEnabled) restoreHue()
                restoreAccount(ticket)
            }
        }
    }

    private suspend fun restoreAccount(ticket: Long) {
        val saved = accountLock.withLock {
            try { withContext(io) { accounts.load() } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                // Keep the failed load and its cleanup under the same lock. A late
                // startup failure must never delete a newly saved candidate.
                if (ticket == attempt) {
                    withContext(io) { runCatching { accounts.clear() } }
                    mutable.value = mutable.value.copy(loginError = text(TextKey.SavedAccountUnreadable))
                }
                null
            }
        }
        if (ticket != attempt) return
        if (saved == null) {
            mutable.value = mutable.value.copy(restoring = false)
            return
        }
        installSession(saved, ticket, save = false, initialFlat = JsonObject(emptyMap()))
    }

    fun acceptDisclaimer() {
        if (mutable.value.restoring || mutable.value.acceptingDisclaimer || loadedPreferences.disclaimerAccepted) return
        val ticket = attempt
        mutable.value = mutable.value.copy(acceptingDisclaimer = true, error = null)
        authJob = scope.launch {
            try {
                preferenceLock.withLock {
                    val accepted = loadedPreferences.copy(disclaimerAccepted = true)
                    withContext(io) { preferences.save(accepted) }
                    if (ticket != attempt) return@launch
                    loadedPreferences = accepted
                    mutable.value = mutable.value.copy(preferences = accepted, acceptingDisclaimer = false, restoring = true)
                }
                if (loadedPreferences.hueEnabled) restoreHue()
                restoreAccount(ticket)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (ticket == attempt) mutable.value = mutable.value.copy(acceptingDisclaimer = false, restoring = false,
                    error = text(TextKey.DisclaimerUnsaved))
            }
        }
    }

    fun signIn(username: String, password: String) {
        if (!loadedPreferences.disclaimerAccepted) return
        // Atomic persistence is a short commit boundary; don't cancel it for another form submission.
        if (accountMutations > 0) return
        if (username.isBlank() || password.isEmpty()) {
            mutable.value = mutable.value.copy(loginError = text(TextKey.CredentialsRequired)); return
        }
        attempt++
        val ticket = attempt
        authJob?.cancel()
        authJob = scope.launch { authenticate(Credentials(username.trim(), password), ticket) }
    }

    private suspend fun authenticate(credentials: Credentials, ticket: Long) {
        mutable.value = mutable.value.copy(signingIn = true, loginError = null)
        val candidate = gateway(credentials)
        var handedOff = false
        try {
            val flat = candidate.validate()
            // Validate hierarchy before persisting. An empty home is valid.
            require(flat["flat"] is JsonObject) { "Invalid home definition." }
            handedOff = true
            installSession(credentials, ticket, save = true, initialFlat = flat, candidate = candidate)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (ticket == attempt) mutable.value = mutable.value.copy(restoring = false, signingIn = false,
                loginError = if (e is AuthenticationException) text(TextKey.CredentialsRejected) else text(TextKey.SignInFailed))
        } finally { if (!handedOff) candidate.close() }
    }

    private suspend fun installSession(
        credentials: Credentials,
        ticket: Long,
        save: Boolean,
        initialFlat: JsonObject,
        candidate: EntiaGateway = gateway(credentials),
    ): Boolean {
        var installed = false
        try {
            accountLock.withLock {
                if (ticket != attempt) return@withLock
                accountMutations++
                try { withContext(NonCancellable) {
                    if (save) withContext(io) { accounts.save(credentials) }
                    if (ticket != attempt) return@withContext // Logout owns the next disk mutation.
                    val owned = loadedPreferences.forAccount(credentials.username)
                    var preferenceFailure = false
                    if (owned != loadedPreferences) {
                        preferenceLock.withLock {
                            loadedPreferences = owned
                            try { withContext(io) { preferences.save(owned) } } catch (_: Exception) { preferenceFailure = true }
                        }
                    }
                    if (ticket != attempt) return@withContext
                    session?.close()
                    val s = Session(candidate, initialFlat)
                    session = s
                    installed = true
                    val old = mutable.value
                    mutable.value = AppState(restoring = false, username = credentials.username,
                        preferences = owned, foreground = old.foreground,
                        hue = old.hue,
                        error = if (preferenceFailure) text(TextKey.AccountPreferencesUnsaved) else null)
                    refresh(s)
                    if (old.foreground) startWorkers(s)
                } } finally { accountMutations-- }
            }
        } finally { if (!installed) candidate.close() }
        return installed
    }

    fun setForeground(foreground: Boolean) {
        if (mutable.value.foreground == foreground) return
        mutable.value = mutable.value.copy(foreground = foreground)
        if (foreground && !mutable.value.demo && mutable.value.preferences.hueEnabled) {
            if (hueGateway == null && !mutable.value.hue.paired) restoreHue()
            else if (mutable.value.hue.paired) {
                refreshHue()
                startHuePolling()
            }
        } else if (!foreground) {
            huePollJob?.cancel()
            huePollJob = null
        }
        val s = session ?: return
        if (foreground) { refresh(s); startWorkers(s) }
        else { s.workers?.cancel(); s.workers = null; mutable.value = mutable.value.copy(socketConnected = false) }
    }

    private fun startWorkers(s: Session) {
        if (s.workers?.isActive == true) return
        s.workers = s.jobs.launch {
            launch {
                while (isActive) { delay(pollMillis); refresh(s).await() }
            }
            launch {
                var backoff = 2_000L
                while (isActive && session === s) {
                    try {
                        s.api.listen(onOpen = {
                            if (session === s) {
                                backoff = 2_000
                                mutable.value = mutable.value.copy(socketConnected = true)
                                refresh(s)
                            }
                        }, onEvent = { text ->
                            if (session === s) receive(s, text)
                        })
                    } catch (e: CancellationException) { throw e }
                    catch (_: AuthenticationException) {
                        s.api.clearToken()
                        result { s.api.renew(null) }
                    }
                    catch (_: Exception) { /* REST remains usable; no login on ordinary socket failures. */ }
                    if (session !== s) return@launch
                    mutable.value = mutable.value.copy(socketConnected = false)
                    delay(backoff + Random.nextLong((backoff / 4).coerceAtLeast(1)))
                    backoff = (backoff * 2).coerceAtMost(300_000)
                }
            }
        }
    }

    fun refresh() {
        resetConnection()
        val current = mutable.value
        if (current.preferences.hueEnabled && current.hue.paired && !current.demo) refreshHue()
    }

    fun resetConnection() {
        val current = mutable.value
        if (current.demo || current.needsSignIn || current.signingIn) return
        val s = session
        if (s != null) {
            if (current.foreground && !current.socketConnected) {
                s.workers?.cancel()
                s.workers = null
                startWorkers(s)
            }
            if (!current.restReachable && s.refresh?.isActive == true) {
                s.refresh?.cancel()
                s.refresh = null
            }
            refresh(s)
        } else if (loadedPreferences.disclaimerAccepted && accountMutations == 0) {
            val ticket = attempt
            authJob?.cancel()
            authJob = scope.launch { restoreAccount(ticket) }
        }
    }

    private fun refresh(s: Session): Deferred<Unit> {
        s.refresh?.takeIf { it.isActive }?.let { return it }
        return s.jobs.async {
            if (session !== s) return@async
            s.fetching = true
            s.events.clear()
            mutable.value = mutable.value.copy(syncing = true)
            try {
                supervisorScope {
                    val hierarchy = async { result { s.api.flat() } }
                    val telemetry = async { result { s.api.devices() } }
                    var flat = hierarchy.await()
                    var devices = telemetry.await()
                    if (session !== s) return@supervisorScope
                    if (flat.exceptionOrNull() is AuthenticationException || devices.exceptionOrNull() is AuthenticationException) {
                        s.api.clearToken()
                        val renewed = result { s.api.renew(null) }
                        if (session !== s) return@supervisorScope
                        if (renewed.isSuccess) {
                            val retryFlat = if (flat.exceptionOrNull() is AuthenticationException) async { result { s.api.flat() } } else null
                            val retryDevices = if (devices.exceptionOrNull() is AuthenticationException) async { result { s.api.devices() } } else null
                            retryFlat?.let { flat = it.await() }
                            retryDevices?.let { devices = it.await() }
                            if (session !== s) return@supervisorScope
                        }
                    }
                    flat.getOrNull()?.let { if (it["flat"] is JsonObject) s.flat = it }
                    devices.getOrNull()?.let { s.lastDevices = it }
                    if (devices.isSuccess && s.flat["flat"] is JsonObject) {
                        val flatJson = s.flat
                        val devicesJson = devices.getOrThrow()
                        val snapshot = withContext(io) { parseHome(flatJson, devicesJson, text = text, decode = decodeLabel) }
                        if (session !== s) return@supervisorScope
                        val prefs = mutable.value.preferences
                        val home = reconcileSnapshot(snapshot, mutable.value.home, s.events)
                            .withLocalOverrides(prefs.deviceNames, prefs.hiddenDevices, prefs.lightIcons, prefs.standaloneHueLights, prefs.hueEnabled)
                        var selected = home.visibleRoomId(mutable.value.selectedRoom)
                        if (mutable.value.home.syncedAt == 0L) selected = home.startupRoom(prefs.startupRoom, prefs.lastRoom)
                        mutable.value = mutable.value.copy(home = home, selectedRoom = selected, restReachable = true,
                            error = if (flat.isFailure) text(TextKey.RoomsRefreshFailed) else null)
                        observed(s)
                    } else mutable.value = mutable.value.copy(restReachable = false,
                        error = text(TextKey.RefreshFailed))
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (session === s) mutable.value = mutable.value.copy(restReachable = false, error = text(TextKey.InvalidResponse))
            } finally {
                s.fetching = false
                s.events.clear()
                if (session === s) mutable.value = mutable.value.copy(syncing = false)
                if (s.resync && session === s) {
                    s.resync = false
                    s.jobs.launch { delay(500); refresh(s).await() }
                }
            }
        }.also { s.refresh = it }
    }

    private suspend fun <T> result(block: suspend () -> T): Result<T> = try { Result.success(block()) }
        catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }

    private fun receive(s: Session, text: String) {
        val event = parseEvent(text) ?: return
        if (s.fetching) {
            if (s.events.size < 2048) s.events += event else s.resync = true
        }
        if (event.device !in mutable.value.home.devices) {
            // Coalesce discovery for devices not in the current hierarchy.
            if (s.fetching) s.resync = true else refresh(s)
            return
        }
        val home = mutable.value.home.applyEvent(event)
        mutable.value = mutable.value.copy(home = home, selectedRoom = home.visibleRoomId(mutable.value.selectedRoom))
        observed(s, event)
    }

    private fun observed(s: Session, event: AttributeEvent? = null) {
        val current = mutable.value
        val tilts = current.tilts.toMutableMap()
        val speeds = current.preferences.speeds.toMutableMap()
        current.home.devices.values.forEach { d ->
            val positionCommand = current.commands[d.id]?.takeIf { it.attribute == Attr.POSITION && it.pending }
            d.value(Attr.POSITION)?.takeIf { it in 0..100 &&
                (event == null || (event.device == d.id && event.attribute in setOf(Attr.POSITION, Attr.MOVING))) &&
                (positionCommand == null || (positionCommand.phase == CommandPhase.Awaiting && it == positionCommand.desired))
            }?.let { raw ->
                tilts[d.id] = (tilts[d.id] ?: BlindTiltState(raw)).observed(raw, d.value(Attr.MOVING) == 1)
            }
            d.value(Attr.HRV_SPEED)?.takeIf { it in 1..3 }?.let { speeds[d.id] = it }
        }
        val commands = current.commands.mapValues { (id, command) ->
            val device = current.home.devices[id]
            if (command.pending && (device == null || !device.available || command.attribute !in device.attributes))
                command.copy(phase = CommandPhase.Failed, error = text(TextKey.ControlRemoved))
            else if (command.phase == CommandPhase.Awaiting && device?.value(command.attribute) == command.desired &&
                (command.attribute != Attr.POSITION || device.value(Attr.MOVING) != 1)) command.copy(phase = CommandPhase.Confirmed)
            else command
        }
        mutable.value = current.copy(tilts = tilts.filterKeys { it in current.home.devices }, commands = commands,
            preferences = current.preferences.copy(speeds = speeds))
        if (speeds != current.preferences.speeds) persistPreferences(s)
    }

    fun command(deviceId: Long, attribute: Int, raw: Int, tiltPercent: Int? = null, allowBackground: Boolean = false) {
        val current = mutable.value
        val device = current.home.devices[deviceId]
        val mainsTurnedOnFromOff = attribute == Attr.LIGHT && raw == 1 &&
            device != null && !device.isStandaloneHue && device.value(Attr.LIGHT) != 1 &&
            current.preferences.hueEnabled
        val outletTargets = if (mainsTurnedOnFromOff) {
            val allTargets = current.preferences.allHueTargetsOnEntiaSwitch(deviceId)
            val hasLight = allTargets.any { it.type == "light" }
            if (hasLight) allTargets.filter { it.type == "light" } else allTargets
        } else emptyList()
        val priorStates = outletTargets.associateWith { ref ->
            current.hue.targets.firstOrNull { it.id == ref.id && it.type == ref.type }
                ?: current.hue.targets.firstOrNull { it.id == ref.id }
        }
        val accepted = sendEntiaCommand(deviceId, attribute, raw, tiltPercent, allowBackground)
        if (!accepted) return
        if (attribute == Attr.LIGHT && raw == 0) {
            hueBootSyncJobs.remove(deviceId)?.cancel()
            hueMainsBootUntil.remove(deviceId)
        }
        if (mainsTurnedOnFromOff && priorStates.isNotEmpty() && !current.demo) {
            launchHueOutletRemote(
                entiaId = deviceId,
                activeRefs = emptyList(),
                priorSiblingStates = priorStates,
                bootWindowActive = true,
                restoreMains = true,
                remote = null,
            )
        }
    }

    private fun sendEntiaCommand(
        deviceId: Long,
        attribute: Int,
        raw: Int,
        tiltPercent: Int? = null,
        allowBackground: Boolean = false,
    ): Boolean {
        if (deviceId < 0) return false
        val current = mutable.value
        if (!current.demo) {
            val activeSession = session
            if (!current.restReachable || activeSession == null) {
                resetConnection()
            } else if (current.foreground && activeSession.workers?.isActive != true) {
                startWorkers(activeSession)
            }
        }
        val device = current.home.devices[deviceId] ?: return false
        if (device.isStandaloneHue) return false
        if (current.commands[deviceId]?.pending == true) return false
        if ((!current.foreground && !allowBackground) || current.needsSignIn) return false
        val validation = runCatching { validateCommand(device, attribute, raw) }
        if (validation.isFailure) return false
        if (attribute == Attr.POSITION && device.value(Attr.MOVING) == 1) return false
        val s = session
        if (!current.demo && s == null) return false
        val previousTilt = current.tilts[deviceId]
        val nextTilt = if (attribute == Attr.POSITION) {
            val tilt = previousTilt ?: BlindTiltState(device.value(Attr.POSITION) ?: raw)
            if (tiltPercent == null) tilt.position(raw) else runCatching { tilt.tilting(tiltPercent) }.getOrNull() ?: return false
        } else null
        val pending = Command(attribute, raw, CommandPhase.Sending)
        mutable.value = current.copy(commands = current.commands + (deviceId to pending),
            tilts = if (nextTilt == null) current.tilts else current.tilts + (deviceId to nextTilt))
        if (current.demo) {
            val home = current.home.applyEvent(AttributeEvent(deviceId, attribute, Reading(kotlinx.serialization.json.JsonPrimitive(raw))))
            mutable.value = mutable.value.copy(home = home,
                commands = mutable.value.commands + (deviceId to pending.copy(phase = CommandPhase.Confirmed)),
                tilts = if (nextTilt == null) mutable.value.tilts
                    else mutable.value.tilts + (deviceId to nextTilt.observed(raw, moving = false)))
            if (attribute == Attr.HRV_SPEED && raw > 0) mutable.value = mutable.value.copy(
                preferences = mutable.value.preferences.copy(speeds = mutable.value.preferences.speeds + (deviceId to raw)))
            return true
        }
        if (s == null) return false
        s.jobs.launch {
            try {
                try {
                    s.api.set(deviceId, attribute, raw)
                } catch (_: AuthenticationException) {
                    s.api.clearToken()
                    s.api.renew(null)
                    s.api.set(deviceId, attribute, raw)
                }
                if (session !== s) return@launch
                if (mutable.value.commands[deviceId]?.phase == CommandPhase.Sending) {
                    mutable.value = mutable.value.copy(commands = mutable.value.commands + (deviceId to pending.copy(phase = CommandPhase.Awaiting)))
                }
                // Every accepted write is reconciled with the full REST snapshots.
                if (mutable.value.foreground) refreshAfterWrite(s)
                delay(confirmationMillis)
                if (session !== s || mutable.value.commands[deviceId]?.pending != true) return@launch
                if (mutable.value.foreground) refresh(s).await()
                if (session === s && mutable.value.commands[deviceId]?.pending == true) {
                    mutable.value = mutable.value.copy(commands = mutable.value.commands + (deviceId to pending.copy(phase = CommandPhase.Unconfirmed,
                        error = text(TextKey.TargetUnobserved))))
                    if (attribute == Attr.POSITION) mutable.value = mutable.value.copy(tilts = mutable.value.tilts - deviceId)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (session === s) {
                    val definite = (e is ApiException && e.status in 400..499 && e.status != 408) || e is AuthenticationException
                    mutable.value = mutable.value.copy(commands = mutable.value.commands + (deviceId to pending.copy(
                        phase = if (definite) CommandPhase.Failed else CommandPhase.Unconfirmed,
                        error = when {
                            e is AuthenticationException -> text(TextKey.SessionRejected)
                            e is ApiException && e.status in 400..499 && e.status != 408 -> when (e.status) {
                                403 -> text(TextKey.PermissionDenied)
                                429 -> text(TextKey.RateLimited)
                                else -> text(TextKey.RequestFailed, e.status.toLong())
                            }
                            else -> text(TextKey.OutcomeUnknown)
                        })),
                        tilts = if (attribute == Attr.POSITION) mutable.value.tilts - deviceId else mutable.value.tilts)
                    if (mutable.value.foreground) refresh(s)
                }
            }
        }
        return true
    }

    private suspend fun refreshAfterWrite(s: Session) {
        // A request begun before the PUT was accepted cannot reconcile that write.
        s.refresh?.takeIf { it.isActive }?.await()
        if (session === s) refresh(s).await()
    }

    fun tilt(deviceId: Long, percent: Int) {
        val tilt = mutable.value.tilts[deviceId] ?: return
        val raw = runCatching { tilt.tiltValue(percent) }.getOrNull() ?: return
        command(deviceId, Attr.POSITION, raw, percent)
    }

    fun selectRoom(id: Long?) {
        val current = mutable.value
        if (id != null && current.home.visibleRoomId(id) == null) return
        mutable.value = current.copy(selectedRoom = id,
            preferences = if (id == null) current.preferences else current.preferences.copy(lastRoom = id))
        if (id != null) persistPreferences(session)
    }

    fun setLanguage(language: String) {
        val normalized = when (language) {
            "en", "sl" -> language
            else -> "system"
        }
        if (mutable.value.preferences.language == normalized) return
        updatePreferences { it.copy(language = normalized) }
    }

    fun updatePreferences(transform: (Preferences) -> Preferences) {
        val previous = mutable.value.preferences
        val prefs = transform(previous)
        val languageChanged = prefs.language != previous.language
        if (languageChanged) {
            // Update state.preferences before invoking TextCatalog so dynamic providers see the new language.
            mutable.value = mutable.value.copy(preferences = prefs)
        }
        val current = mutable.value
        val baseHome = if (languageChanged && current.demo) {
            val freshDemo = demoHome(text)
            freshDemo.copy(
                devices = freshDemo.devices.mapValues { (id, dev) ->
                    val existing = current.home.devices[id]
                    if (existing != null) dev.copy(attributes = existing.attributes) else dev
                },
                syncedAt = current.home.syncedAt.takeIf { it > 0 } ?: freshDemo.syncedAt,
            )
        } else current.home
        val home = baseHome.withLocalOverrides(
            prefs.deviceNames,
            prefs.hiddenDevices,
            prefs.lightIcons,
            prefs.standaloneHueLights,
            prefs.hueEnabled,
        )
        val nextHue = if (languageChanged && current.demo && prefs.hueEnabled) {
            val existingById = current.hue.targets.associateBy { "${it.type}:${it.id}" }
            val freshTargets = demoHueTargets(text).map { fresh ->
                existingById["${fresh.type}:${fresh.id}"]?.let { old ->
                    fresh.copy(on = old.on, brightness = old.brightness, mirek = old.mirek, xy = old.xy)
                } ?: fresh
            }
            current.hue.copy(targets = freshTargets)
        } else current.hue
        mutable.value = current.copy(
            preferences = prefs,
            username = if (languageChanged && current.demo) text(TextKey.DemoHome) else current.username,
            home = home,
            hue = nextHue,
            selectedRoom = home.visibleRoomId(current.selectedRoom),
        )
        persistPreferences(session)
        if (prefs.hueEnabled != previous.hueEnabled) {
            onHueEnabledChanged(prefs.hueEnabled)
        }
        if (languageChanged && !current.demo) {
            val s = session
            val cachedDevices = s?.lastDevices
            if (s != null && s.flat["flat"] is JsonObject && cachedDevices != null) {
                val flatJson = s.flat
                s.jobs.launch {
                    val reparsed = runCatching {
                        withContext(io) { parseHome(flatJson, cachedDevices, text = text, decode = decodeLabel) }
                    }.getOrNull() ?: return@launch
                    if (session !== s) return@launch
                    val latestPrefs = mutable.value.preferences
                    val latestHome = reconcileSnapshot(reparsed, mutable.value.home, emptyList())
                        .withLocalOverrides(
                            latestPrefs.deviceNames,
                            latestPrefs.hiddenDevices,
                            latestPrefs.lightIcons,
                            latestPrefs.standaloneHueLights,
                            latestPrefs.hueEnabled,
                        )
                    mutable.value = mutable.value.copy(
                        home = latestHome,
                        selectedRoom = latestHome.visibleRoomId(mutable.value.selectedRoom),
                    )
                }
            }
        }
    }

    fun setHueEnabled(enabled: Boolean) {
        if (mutable.value.preferences.hueEnabled == enabled) return
        updatePreferences { it.copy(hueEnabled = enabled) }
    }

    private fun cancelAllHueBootSyncJobs() {
        hueBootSyncJobs.values.forEach { it.cancel() }
        hueBootSyncJobs.clear()
        hueMainsBootUntil.clear()
    }

    private fun onHueEnabledChanged(enabled: Boolean) {
        if (!enabled) {
            hueJob?.cancel(); hueJob = null
            huePollJob?.cancel(); huePollJob = null
            hueRefreshJob?.cancel(); hueRefreshJob = null
            cancelAllHueBootSyncJobs()
            hueGateway?.close(); hueGateway = null
            mutable.value = mutable.value.copy(hue = HueState())
        } else if (mutable.value.demo) {
            mutable.value = mutable.value.copy(hue = demoHueState())
        } else {
            restoreHue()
        }
    }

    private fun demoHueState(bridgeIp: String = "192.168.1.42") = HueState(
        bridgeIp = bridgeIp,
        bridgeId = "demo001788fffe01",
        paired = true,
        targets = demoHueTargets(text),
    )

    private fun restoreHue() {
        val store = hueStore ?: return
        if (!mutable.value.preferences.hueEnabled || mutable.value.demo) return
        hueJob?.cancel()
        hueJob = scope.launch {
            val creds = hueLock.withLock {
                runCatching { withContext(io) { store.loadHue() } }.getOrNull()
            }
            if (creds == null || !mutable.value.preferences.hueEnabled || mutable.value.demo) return@launch
            val gw = withContext(io) { hueGatewayFactory(creds.bridgeIp, creds.appKey) }
            if (!mutable.value.preferences.hueEnabled || mutable.value.demo) {
                gw.close()
                return@launch
            }
            installHueGateway(creds, gw)
            refreshHue()
            if (mutable.value.foreground) startHuePolling()
        }
    }

    private fun installHueGateway(creds: HueCredentials, gateway: HueGateway) {
        hueRefreshJob?.cancel(); hueRefreshJob = null
        hueGateway?.close()
        hueGateway = gateway
        mutable.value = mutable.value.copy(
            hue = mutable.value.hue.copy(
                bridgeIp = creds.bridgeIp,
                bridgeId = creds.bridgeId,
                paired = true,
                pairing = false,
                pairingSecondsLeft = 0,
                syncing = false,
                error = null,
            )
        )
    }

    private fun startHuePolling() {
        if (huePollJob?.isActive == true) return
        if (mutable.value.demo || !mutable.value.preferences.hueEnabled || !mutable.value.hue.paired) return
        huePollJob = scope.launch {
            while (isActive && mutable.value.foreground && mutable.value.preferences.hueEnabled && mutable.value.hue.paired && !mutable.value.demo) {
                delay(60_000L)
                refreshHue()
            }
        }
    }

    fun discoverHueBridges() {
        if (!mutable.value.preferences.hueEnabled) return
        if (mutable.value.demo) {
            mutable.value = mutable.value.copy(
                hue = mutable.value.hue.copy(
                    discovering = false,
                    discoveredBridges = listOf(DiscoveredBridge("192.168.1.42", "demo001788fffe01", "demo")),
                    error = null,
                )
            )
            return
        }
        hueJob?.cancel()
        mutable.value = mutable.value.copy(hue = mutable.value.hue.copy(discovering = true, error = null))
        hueJob = scope.launch {
            val found = runCatching { withContext(io) { hueDiscoverer() } }.getOrDefault(emptyList())
            mutable.value = mutable.value.copy(
                hue = mutable.value.hue.copy(discovering = false, discoveredBridges = found)
            )
        }
    }

    fun pairHueBridge(bridgeIp: String) {
        if (!mutable.value.preferences.hueEnabled) return
        val normalized = normalizeBridgeAddress(bridgeIp)
        if (normalized == null) {
            mutable.value = mutable.value.copy(hue = mutable.value.hue.copy(error = text(TextKey.HueInvalidBridgeIp)))
            return
        }
        if (mutable.value.demo) {
            mutable.value = mutable.value.copy(hue = demoHueState(normalized))
            return
        }
        hueJob?.cancel()
        val bridgeId = mutable.value.hue.discoveredBridges.firstOrNull { it.ip == normalized }?.id
        val totalSteps = ((huePairingTimeoutSeconds * 1000L) / huePollDelayMillis.coerceAtLeast(1L)).toInt().coerceIn(1, 20)
        hueJob = scope.launch {
            mutable.value = mutable.value.copy(
                hue = mutable.value.hue.copy(
                    bridgeIp = normalized,
                    pairing = true,
                    pairingSecondsLeft = huePairingTimeoutSeconds,
                    error = null,
                )
            )
            for (step in 0 until totalSteps) {
                if (!mutable.value.preferences.hueEnabled || mutable.value.demo) return@launch
                val secondsLeft = (((totalSteps - step) * huePairingTimeoutSeconds + totalSteps - 1) / totalSteps).coerceAtLeast(1)
                mutable.value = mutable.value.copy(hue = mutable.value.hue.copy(pairingSecondsLeft = secondsLeft))
                val result = try {
                    withContext(io) { huePairer(normalized) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    HuePairResult.Error(text(TextKey.HueSyncFailed))
                }
                when (result) {
                    is HuePairResult.Success -> {
                        val creds = HueCredentials(normalized, result.appKey, result.clientKey, bridgeId)
                        runCatching { hueLock.withLock { withContext(io) { hueStore?.saveHue(creds) } } }
                        val gw = withContext(io) { hueGatewayFactory(creds.bridgeIp, creds.appKey) }
                        if (!mutable.value.preferences.hueEnabled || mutable.value.demo) {
                            gw.close()
                            return@launch
                        }
                        installHueGateway(creds, gw)
                        refreshHue()
                        if (mutable.value.foreground) startHuePolling()
                        return@launch
                    }
                    HuePairResult.LinkButtonNotPressed -> {
                        if (step < totalSteps - 1) delay(huePollDelayMillis)
                    }
                    is HuePairResult.Error -> {
                        mutable.value = mutable.value.copy(
                            hue = mutable.value.hue.copy(pairing = false, pairingSecondsLeft = 0, error = result.message)
                        )
                        return@launch
                    }
                }
            }
            mutable.value = mutable.value.copy(
                hue = mutable.value.hue.copy(pairing = false, pairingSecondsLeft = 0, error = text(TextKey.HuePairingTimeout))
            )
        }
    }

    fun cancelHuePairing() {
        hueJob?.cancel()
        hueJob = null
        mutable.value = mutable.value.copy(
            hue = mutable.value.hue.copy(pairing = false, pairingSecondsLeft = 0)
        )
    }

    fun disconnectHue() {
        hueJob?.cancel(); hueJob = null
        huePollJob?.cancel(); huePollJob = null
        hueRefreshJob?.cancel(); hueRefreshJob = null
        cancelAllHueBootSyncJobs()
        hueGateway?.close(); hueGateway = null
        val isDemo = mutable.value.demo
        mutable.value = mutable.value.copy(hue = HueState())
        if (!isDemo) {
            scope.launch {
                hueLock.withLock { runCatching { withContext(io) { hueStore?.clearHue() } } }
            }
        }
    }

    fun refreshHue() {
        val current = mutable.value
        if (!current.preferences.hueEnabled || current.demo || !current.hue.paired) return
        if (hueRefreshJob?.isActive == true) return
        val gw = hueGateway ?: return
        hueRefreshJob = scope.launch {
            mutable.value = mutable.value.copy(hue = mutable.value.hue.copy(syncing = true, error = null))
            try {
                val targets = withContext(io) { gw.fetchTargets() }
                if (hueGateway !== gw) return@launch
                mutable.value = mutable.value.copy(
                    hue = mutable.value.hue.copy(targets = targets, syncing = false, error = null)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: HueAuthException) {
                if (hueGateway === gw) {
                    mutable.value = mutable.value.copy(
                        hue = mutable.value.hue.copy(paired = false, syncing = false, error = text(TextKey.HueAuthRejected))
                    )
                }
            } catch (_: Exception) {
                if (hueGateway === gw) {
                    mutable.value = mutable.value.copy(
                        hue = mutable.value.hue.copy(syncing = false, error = text(TextKey.HueSyncFailed))
                    )
                }
            } finally {
                if (hueGateway === gw && mutable.value.hue.syncing) {
                    mutable.value = mutable.value.copy(hue = mutable.value.hue.copy(syncing = false))
                }
            }
        }
    }

    private fun validEntiaSwitchId(home: Home, candidate: Long?): Long? =
        candidate?.takeIf { id -> id >= 0 && home.devices[id]?.let { !it.isStandaloneHue && it.isLight } == true }

    fun addStandaloneHueLight(
        roomId: Long,
        name: String,
        icon: LightIcon,
        targets: List<HueTargetRef>,
        entiaDeviceId: Long? = null,
    ) {
        val current = mutable.value
        if (!current.preferences.hueEnabled) return
        if (roomId == Long.MIN_VALUE || current.home.rooms.none { it.id == roomId }) return
        val trimmed = name.trim().take(80)
        val fallbackName = targets.joinToString(", ") { it.name.ifBlank { it.id } }.trim().take(80)
        val finalName = trimmed.ifBlank { fallbackName }.ifBlank { text(TextKey.HueStandaloneDefaultName) }
        val distinctTargets = targets.distinctBy { "${it.type}:${it.id}" }
        val validEntiaId = validEntiaSwitchId(current.home, entiaDeviceId)
        updatePreferences { prefs ->
            val nextId = ((prefs.standaloneHueLights.minOfOrNull { it.id } ?: -1000L).coerceAtMost(-1000L)) - 1L
            val item = StandaloneHueLight(
                id = nextId,
                roomId = roomId,
                name = finalName,
                icon = icon,
                targets = distinctTargets,
                entiaDeviceId = validEntiaId,
            )
            prefs.copy(standaloneHueLights = prefs.standaloneHueLights + item)
        }
    }

    fun setStandaloneHueLight(deviceId: Long, binding: HueBinding, entiaDeviceId: Long?) {
        val current = mutable.value
        if (deviceId >= 0 || deviceId !in current.home.devices) return
        val validEntiaId = validEntiaSwitchId(current.home, entiaDeviceId)
        val distinctTargets = binding.targets.distinctBy { ref -> "${ref.type}:${ref.id}" }
        updatePreferences { prefs ->
            val updated = prefs.standaloneHueLights.map {
                if (it.id == deviceId) it.copy(targets = distinctTargets, entiaDeviceId = validEntiaId) else it
            }
            prefs.copy(standaloneHueLights = updated, hueBindings = prefs.hueBindings - deviceId)
        }
    }

    fun setHueBinding(deviceId: Long, binding: HueBinding?) {
        if (deviceId !in mutable.value.home.devices) return
        if (deviceId < 0) {
            updatePreferences { prefs ->
                val updated = if (binding == null) {
                    prefs.standaloneHueLights.filterNot { it.id == deviceId }
                } else {
                    prefs.standaloneHueLights.map {
                        if (it.id == deviceId) it.copy(targets = binding.targets.distinctBy { ref -> "${ref.type}:${ref.id}" }) else it
                    }
                }
                prefs.copy(standaloneHueLights = updated, hueBindings = prefs.hueBindings - deviceId)
            }
            return
        }
        updatePreferences { prefs ->
            prefs.copy(hueBindings = if (binding == null) prefs.hueBindings - deviceId else prefs.hueBindings + (deviceId to binding))
        }
    }

    private fun ensureMainsOn(deviceId: Long, allowBackground: Boolean = false) {
        if (deviceId < 0) return
        val current = mutable.value
        val device = current.home.devices[deviceId] ?: return
        if (device.isStandaloneHue) return
        if (device.value(Attr.LIGHT) != 1 && current.commands[deviceId]?.pending != true) {
            sendEntiaCommand(deviceId, Attr.LIGHT, 1, allowBackground = allowBackground)
        }
    }

    /** Smart Mains Guard: turns on physical Entia switch if off (preserving sibling Hue states), and controls target Hue lights. */
    fun toggleHueLight(deviceId: Long, allowBackground: Boolean = false) {
        val current = mutable.value
        val device = current.home.devices[deviceId] ?: return
        val binding = if (current.preferences.hueEnabled) current.preferences.hueBindingFor(deviceId) else null
        if (binding == null) {
            if (device.isStandaloneHue || deviceId < 0) return
            command(deviceId, Attr.LIGHT, if (device.value(Attr.LIGHT) == 1) 0 else 1, allowBackground = allowBackground)
            return
        }
        val resolved = resolveLightState(current.home, current.preferences, current.hue, device)
        val entiaId = resolved.linkedEntiaId
        val mainsWasOff = resolved.entiaSwitch != null && !resolved.mainsOn
        val refs = binding.targets
        if (refs.isEmpty()) {
            if (mainsWasOff && entiaId != null) ensureMainsOn(entiaId, allowBackground = allowBackground)
            return
        }
        val nextOn = if (mainsWasOff) true else !(resolved.target?.on ?: (deviceId >= 0))
        dispatchHueOutletCommand(
            entiaId = entiaId,
            activeRefs = refs,
            restoreMains = mainsWasOff,
            allowBackground = allowBackground,
            preserveSiblings = mainsWasOff,
            optimistic = { it.copy(on = nextOn) },
        ) { gw, ref ->
            gw.setPower(ref.type, ref.id, nextOn)
        }
    }

    fun setHuePower(deviceId: Long, on: Boolean, targetId: String? = null) {
        val current = mutable.value
        if (!current.preferences.hueEnabled) return
        val binding = current.preferences.hueBindingFor(deviceId) ?: return
        val refs = resolveRefs(binding, targetId)
        val entiaId = if (on) current.preferences.linkedEntiaSwitchId(deviceId) else null
        val entiaDevice = entiaId?.let { current.home.devices[it] }
        val mainsWasOff = on && entiaDevice != null && entiaDevice.value(Attr.LIGHT) != 1
        dispatchHueOutletCommand(
            entiaId = entiaId,
            activeRefs = refs,
            restoreMains = mainsWasOff,
            allowBackground = false,
            preserveSiblings = mainsWasOff,
            optimistic = { it.copy(on = on) },
        ) { gw, ref ->
            gw.setPower(ref.type, ref.id, on)
        }
    }

    fun setHueBrightness(deviceId: Long, brightness: Int, targetId: String? = null) {
        val current = mutable.value
        if (!current.preferences.hueEnabled) return
        val binding = current.preferences.hueBindingFor(deviceId) ?: return
        val clamped = brightness.coerceIn(1, 100)
        val refs = resolveRefs(binding, targetId)
        val entiaId = current.preferences.linkedEntiaSwitchId(deviceId)
        val entiaDevice = entiaId?.let { current.home.devices[it] }
        val mainsWasOff = entiaDevice != null && entiaDevice.value(Attr.LIGHT) != 1
        dispatchHueOutletCommand(
            entiaId = entiaId,
            activeRefs = refs,
            restoreMains = mainsWasOff,
            allowBackground = false,
            preserveSiblings = mainsWasOff,
            optimistic = { it.copy(on = true, brightness = clamped) },
        ) { gw, ref ->
            gw.setBrightness(ref.type, ref.id, clamped)
        }
    }

    fun setHueColorTemp(deviceId: Long, mirek: Int, targetId: String? = null) {
        val current = mutable.value
        if (!current.preferences.hueEnabled) return
        val binding = current.preferences.hueBindingFor(deviceId) ?: return
        val target = current.hue.targetFor(binding, targetId)
        val clamped = mirek.coerceIn(target?.mirekMin ?: 153, target?.mirekMax ?: 500)
        val refs = resolveRefs(binding, targetId)
        val entiaId = current.preferences.linkedEntiaSwitchId(deviceId)
        val entiaDevice = entiaId?.let { current.home.devices[it] }
        val mainsWasOff = entiaDevice != null && entiaDevice.value(Attr.LIGHT) != 1
        dispatchHueOutletCommand(
            entiaId = entiaId,
            activeRefs = refs,
            restoreMains = mainsWasOff,
            allowBackground = false,
            preserveSiblings = mainsWasOff,
            optimistic = { it.copy(on = true, mirek = clamped) },
        ) { gw, ref ->
            gw.setColorTemp(ref.type, ref.id, clamped)
        }
    }

    fun setHueColorXy(deviceId: Long, x: Double, y: Double, targetId: String? = null) {
        val current = mutable.value
        if (!current.preferences.hueEnabled) return
        val binding = current.preferences.hueBindingFor(deviceId) ?: return
        val safeX = x.coerceIn(0.0, 1.0)
        val safeY = y.coerceIn(0.0, 1.0)
        val refs = resolveRefs(binding, targetId)
        val entiaId = current.preferences.linkedEntiaSwitchId(deviceId)
        val entiaDevice = entiaId?.let { current.home.devices[it] }
        val mainsWasOff = entiaDevice != null && entiaDevice.value(Attr.LIGHT) != 1
        dispatchHueOutletCommand(
            entiaId = entiaId,
            activeRefs = refs,
            restoreMains = mainsWasOff,
            allowBackground = false,
            preserveSiblings = mainsWasOff,
            optimistic = { it.copy(on = true, xy = safeX to safeY) },
        ) { gw, ref ->
            gw.setColorXy(ref.type, ref.id, safeX, safeY)
        }
    }

    private fun resolveRefs(binding: HueBinding, targetId: String?): List<HueTargetRef> =
        if (targetId == null) binding.targets else binding.targets.filter { it.id == targetId }

    private fun dispatchHueOutletCommand(
        entiaId: Long?,
        activeRefs: List<HueTargetRef>,
        restoreMains: Boolean,
        allowBackground: Boolean,
        preserveSiblings: Boolean,
        optimistic: (HueTarget) -> HueTarget,
        remote: suspend (HueGateway, HueTargetRef) -> Unit,
    ) {
        val current = mutable.value
        if ((!current.foreground && !allowBackground) || (activeRefs.isEmpty() && !restoreMains)) return

        val bootWindowActive = entiaId != null && (
            restoreMains || (hueMainsBootUntil[entiaId]?.let { System.currentTimeMillis() < it } == true)
        )
        val shouldPreserveSiblings = (preserveSiblings || bootWindowActive) && entiaId != null
        val activeKeys = activeRefs.asSequence().map { "${it.type}:${it.id}" }.toSet()
        val siblingRefs = if (shouldPreserveSiblings && entiaId != null) {
            val hasIndividualActiveLight = activeRefs.any { it.type == "light" }
            current.preferences.allHueTargetsOnEntiaSwitch(entiaId).filter { sib ->
                val key = "${sib.type}:${sib.id}"
                key !in activeKeys && !(hasIndividualActiveLight && sib.type == "grouped_light")
            }
        } else emptyList()

        val priorSiblingStates = siblingRefs.associateWith { sib ->
            current.hue.targets.firstOrNull { it.id == sib.id && it.type == sib.type }
                ?: current.hue.targets.firstOrNull { it.id == sib.id }
        }

        if (restoreMains && entiaId != null) {
            ensureMainsOn(entiaId, allowBackground = allowBackground)
        }
        if (activeRefs.isEmpty()) return

        val latest = mutable.value
        val updatedTargets = latest.hue.targets.map { target ->
            if ("${target.type}:${target.id}" in activeKeys) optimistic(target)
            else target
        }
        mutable.value = latest.copy(hue = latest.hue.copy(targets = updatedTargets, error = null))
        if (latest.demo) return

        launchHueOutletRemote(
            entiaId = entiaId,
            activeRefs = activeRefs,
            priorSiblingStates = priorSiblingStates,
            bootWindowActive = bootWindowActive,
            restoreMains = restoreMains,
            remote = remote,
        )
    }

    private fun launchHueOutletRemote(
        entiaId: Long?,
        activeRefs: List<HueTargetRef>,
        priorSiblingStates: Map<HueTargetRef, HueTarget?>,
        bootWindowActive: Boolean,
        restoreMains: Boolean,
        remote: (suspend (HueGateway, HueTargetRef) -> Unit)?,
    ) {
        val gw = hueGateway ?: return
        if (restoreMains && entiaId != null && hueMainsBootDelayMillis > 0) {
            hueMainsBootUntil[entiaId] = System.currentTimeMillis() + hueMainsBootDelayMillis
        }
        val needsBootSync = bootWindowActive && entiaId != null && hueMainsBootDelayMillis > 0 &&
            (restoreMains || priorSiblingStates.isNotEmpty())
        if (entiaId != null) {
            hueBootSyncJobs.remove(entiaId)?.cancel()
        }

        val job = scope.launch {
            suspend fun executePass() {
                withContext(io) {
                    coroutineScope {
                        val activeJobs = if (remote != null) {
                            activeRefs.map { ref -> async { remote(gw, ref) } }
                        } else emptyList()
                        val siblingJob = if (priorSiblingStates.isNotEmpty()) {
                            async { applyHueSiblingStates(gw, priorSiblingStates) }
                        } else null
                        activeJobs.awaitAll()
                        siblingJob?.await()
                    }
                }
            }
            try {
                executePass()
                if (needsBootSync && entiaId != null) {
                    delay(hueMainsBootDelayMillis)
                    if (hueGateway !== gw || !mutable.value.preferences.hueEnabled || mutable.value.demo) return@launch
                    val entiaNow = mutable.value.home.devices[entiaId]
                    if (entiaNow != null && mutable.value.commands[entiaId]?.pending != true && entiaNow.value(Attr.LIGHT) == 0) {
                        return@launch
                    }
                    executePass()
                    if (hueGateway === gw && mutable.value.hue.error != null) {
                        mutable.value = mutable.value.copy(hue = mutable.value.hue.copy(error = null))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: HueAuthException) {
                if (hueGateway === gw) {
                    mutable.value = mutable.value.copy(
                        hue = mutable.value.hue.copy(paired = false, error = text(TextKey.HueAuthRejected))
                    )
                }
            } catch (_: Exception) {
                if (hueGateway === gw) {
                    mutable.value = mutable.value.copy(
                        hue = mutable.value.hue.copy(error = text(TextKey.HueSyncFailed))
                    )
                }
            } finally {
                if (entiaId != null && hueMainsBootUntil[entiaId]?.let { System.currentTimeMillis() >= it } == true) {
                    hueMainsBootUntil.remove(entiaId)
                }
            }
        }
        if (needsBootSync && entiaId != null) {
            hueBootSyncJobs[entiaId] = job
        }
    }

    private suspend fun applyHueSiblingStates(
        gw: HueGateway,
        priorSiblingStates: Map<HueTargetRef, HueTarget?>,
    ) {
        if (priorSiblingStates.isEmpty()) return
        coroutineScope {
            priorSiblingStates.entries.map { (ref, prior) ->
                async {
                    if (prior != null && prior.on) {
                        val brightness = prior.brightness
                        if (prior.supportsDimming && brightness != null && brightness > 0) {
                            gw.setBrightness(ref.type, ref.id, brightness)
                        } else {
                            gw.setPower(ref.type, ref.id, true)
                        }
                    } else {
                        gw.setPower(ref.type, ref.id, false)
                    }
                }
            }.awaitAll()
        }
    }

    fun customizeDevice(id: Long, name: String, icon: LightIcon) {
        val device = mutable.value.home.devices[id] ?: return
        val trimmed = name.trim()
        if (trimmed.length > 80) return
        if (id < 0 || device.isStandaloneHue) {
            updatePreferences { prefs ->
                val existing = prefs.standaloneHueLights.firstOrNull { it.id == id } ?: return@updatePreferences prefs
                val nextName = trimmed.ifBlank { existing.name }
                prefs.copy(
                    standaloneHueLights = prefs.standaloneHueLights.map {
                        if (it.id == id) it.copy(name = nextName, icon = icon) else it
                    },
                    deviceNames = prefs.deviceNames - id,
                    lightIcons = prefs.lightIcons - id,
                )
            }
            return
        }
        updatePreferences { prefs -> prefs.copy(
            deviceNames = if (trimmed.isBlank() || trimmed == device.serverName) prefs.deviceNames - id else prefs.deviceNames + (id to trimmed),
            lightIcons = if (!device.isLight || icon == LightIcon.Bulb) prefs.lightIcons - id else prefs.lightIcons + (id to icon)) }
    }

    fun hideDevice(id: Long, hidden: Boolean) {
        if (hidden && id !in mutable.value.home.devices) return
        if (id < 0) {
            if (!hidden) return
            updatePreferences { prefs ->
                prefs.copy(
                    standaloneHueLights = prefs.standaloneHueLights.filterNot { it.id == id },
                    hiddenDevices = prefs.hiddenDevices - id,
                    deviceNames = prefs.deviceNames - id,
                    lightIcons = prefs.lightIcons - id,
                    hueBindings = prefs.hueBindings - id,
                )
            }
            return
        }
        updatePreferences { it.copy(hiddenDevices = if (hidden) it.hiddenDevices + id else it.hiddenDevices - id) }
    }

    fun setBlindPreset(deviceId: Long, slot: Int, percent: Int) {
        if (deviceId !in mutable.value.home.devices || slot !in 1..2 || percent !in 0..100) return
        updatePreferences { prefs ->
            val current = prefs.blindPresets[deviceId] ?: BlindSlots()
            val updated = if (slot == 1) current.copy(slot1 = percent) else current.copy(slot2 = percent)
            prefs.copy(blindPresets = prefs.blindPresets + (deviceId to updated))
        }
    }

    private fun persistPreferences(s: Session?) {
        val ticket = attempt
        scope.launch {
            preferenceLock.withLock {
                if (session !== s || ticket != attempt) return@withLock
                val latest = mutable.value.preferences
                try {
                    withContext(io) { preferences.save(latest) }
                    if (session === s && ticket == attempt) loadedPreferences = latest
                }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { if (ticket == attempt) mutable.value = mutable.value.copy(error = text(TextKey.PreferencesUnsaved)) }
            }
        }
    }

    fun demo() {
        if (!loadedPreferences.disclaimerAccepted) return
        if (accountMutations > 0 || session != null || mutable.value.username != null) return
        attempt++; authJob?.cancel()
        hueJob?.cancel(); hueJob = null
        huePollJob?.cancel(); huePollJob = null
        hueRefreshJob?.cancel(); hueRefreshJob = null
        cancelAllHueBootSyncJobs()
        hueGateway?.close(); hueGateway = null
        val prefs = loadedPreferences.forDemo()
        val home = demoHome(text).withLocalOverrides(prefs.deviceNames, prefs.hiddenDevices, prefs.lightIcons, prefs.standaloneHueLights, prefs.hueEnabled)
        mutable.value = AppState(restoring = false, username = text(TextKey.DemoHome), demo = true, home = home,
            preferences = prefs,
            restReachable = true, foreground = mutable.value.foreground,
            hue = if (prefs.hueEnabled) demoHueState() else HueState())
    }

    fun logout() {
        attempt++
        val ticket = attempt
        authJob?.cancel(); session?.close(); session = null
        cancelAllHueBootSyncJobs()
        val wasDemo = mutable.value.demo
        val clearedPrefs = loadedPreferences.copy(lastRoom = null, startupRoom = null, displayRoom = null, speeds = emptyMap(), accountId = null,
            deviceNames = emptyMap(), hiddenDevices = emptySet(), lightIcons = emptyMap(), blindPresets = emptyMap(), hueBindings = emptyMap(),
            standaloneHueLights = emptyList())
        mutable.value = AppState(restoring = !wasDemo, foreground = mutable.value.foreground,
            preferences = clearedPrefs,
            hue = if (wasDemo) HueState() else mutable.value.hue)
        if (wasDemo) {
            if (loadedPreferences.hueEnabled) restoreHue()
            return
        }
        accountMutations++
        authJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { withContext(NonCancellable) {
                accountLock.withLock { withContext(io) { accounts.clear() } }
                preferenceLock.withLock {
                    withContext(io) { preferences.clearAccount() }
                    loadedPreferences = loadedPreferences.copy(lastRoom = null, startupRoom = null, displayRoom = null, speeds = emptyMap(), accountId = null,
                        deviceNames = emptyMap(), hiddenDevices = emptySet(), lightIcons = emptyMap(), blindPresets = emptyMap(), hueBindings = emptyMap(),
                        standaloneHueLights = emptyList())
                }
                if (attempt == ticket) mutable.value = mutable.value.copy(restoring = false)
            } } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (attempt == ticket) mutable.value = mutable.value.copy(restoring = false,
                loginError = text(TextKey.AccountRemovalFailed)) }
            finally { accountMutations-- }
        }
    }

    fun close() {
        attempt++
        session?.close(); session = null
        hueJob?.cancel(); hueJob = null
        huePollJob?.cancel(); huePollJob = null
        hueRefreshJob?.cancel(); hueRefreshJob = null
        cancelAllHueBootSyncJobs()
        hueGateway?.close(); hueGateway = null
        scope.cancel()
    }
}
