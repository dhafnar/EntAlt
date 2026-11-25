package si.hafnar.entalt.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.ln
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import si.hafnar.entalt.EntAltApplication
import si.hafnar.entalt.R
import si.hafnar.entalt.data.AppPreferences
import si.hafnar.entalt.data.CredentialStore
import si.hafnar.entalt.data.EntiaApi
import si.hafnar.entalt.data.HueApi
import si.hafnar.entalt.data.HueBinding
import si.hafnar.entalt.data.Preferences
import si.hafnar.entalt.domain.Attr
import si.hafnar.entalt.domain.LightIcon
import si.hafnar.entalt.domain.Reading
import si.hafnar.entalt.withAppLanguage

class EntAltWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        updateWidgets(context, appWidgetManager, appWidgetIds)
        val pendingResult = goAsync()
        scope.launch {
            try {
                withTimeout(8_000L) {
                    refreshCacheFromServer(context)
                }
            } catch (_: Exception) {
                // Keep cached widget view on transient network errors.
            } finally {
                updateWidgets(context, appWidgetManager, appWidgetIds)
                pendingResult.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        updateWidgets(context, appWidgetManager, intArrayOf(appWidgetId), newOptions)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val store = WidgetStore(context)
        appWidgetIds.forEach(store::deleteConfig)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_WIDGET_TRIGGER) {
            val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            val slotIndex = intent.getIntExtra(EXTRA_SLOT_INDEX, -1)
            if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID || slotIndex < 0) return

            val store = WidgetStore(context)
            val slot = store.loadConfig(appWidgetId).items.getOrNull(slotIndex) ?: return

            val pendingResult = goAsync()
            scope.launch {
                try {
                    withTimeout(8_000L) {
                        executeSlotAction(context, store, slot)
                    }
                } catch (_: Exception) {
                    // Preserve responsiveness; widget stays intact on transient failure.
                } finally {
                    refreshAllWidgets(context)
                    pendingResult.finish()
                }
            }
            return
        }
        super.onReceive(context, intent)
    }

    private suspend fun executeSlotAction(context: Context, store: WidgetStore, slot: WidgetSlot) {
        val app = context.applicationContext as? EntAltApplication
        val repo = app?.repository
        val repoState = repo?.state?.value
        if (repo != null && repoState?.demo == true) {
            val device = repoState.home.devices[slot.deviceId] ?: return
            val isHueManaged = slot.action == WidgetActionType.LightToggle &&
                repoState.preferences.hueEnabled &&
                repoState.preferences.hueBindingFor(slot.deviceId) != null
            withContext(Dispatchers.Main.immediate) {
                if (isHueManaged) {
                    repo.toggleHueLight(slot.deviceId, allowBackground = true)
                } else if (!device.isStandaloneHue && slot.deviceId >= 0) {
                    val (attr, target) = resolveTarget(slot, device.value(Attr.LIGHT) == 1)
                    repo.command(slot.deviceId, attr, target, allowBackground = true)
                }
            }
            val updated = repo.state.value
            store.saveCacheFromHome(updated.home, updated.preferences, updated.hue)
            return
        }

        val prefs = runCatching { AppPreferences(context).load() }.getOrNull() ?: return
        if (!prefs.disclaimerAccepted) return
        store.saveLanguage(prefs.language)
        val credStore = CredentialStore(context)
        val credentials = runCatching { credStore.load() }.getOrNull() ?: return
        val hueBinding = if (slot.action == WidgetActionType.LightToggle && prefs.hueEnabled) {
            prefs.hueBindingFor(slot.deviceId)
        } else null
        val isStandalone = slot.deviceId < 0
        if (isStandalone && hueBinding == null) return
        val entiaSwitchId = if (isStandalone) prefs.linkedEntiaSwitchId(slot.deviceId) else slot.deviceId

        val api = if (entiaSwitchId == null) null else EntiaApi(credentials, client = EntiaApi.sharedClient())
        try {
            if (hueBinding != null) {
                executeHueWidgetToggle(credStore, api, store, slot, hueBinding, prefs, entiaSwitchId)
                refreshAllWidgets(context)
                if (entiaSwitchId != null && api != null) {
                    delay(650L)
                    runCatching { api.devices() }.getOrNull()?.let { updateCacheFromDevicesJson(store, it, prefs) }
                }
                if (repo != null && repo.state.value.username != null) {
                    withContext(Dispatchers.Main.immediate) {
                        if (isStandalone) repo.refreshHue()
                        if (entiaSwitchId != null) repo.refresh()
                    }
                }
                return
            }
            if (api == null) return

            var currentLightOn = store.loadCache()[slot.deviceId]?.let { it.mainsOn ?: it.lightOn } ?: false
            if (slot.action == WidgetActionType.LightToggle) {
                val devicesPayload = runCatching { api.devices() }.getOrNull()
                if (devicesPayload != null) {
                    updateCacheFromDevicesJson(store, devicesPayload, prefs)
                    currentLightOn = store.loadCache()[slot.deviceId]?.let { it.mainsOn ?: it.lightOn } ?: currentLightOn
                }
            }
            val (attr, target) = resolveTarget(slot, currentLightOn)
            api.set(slot.deviceId, attr, target)
            store.updateCachedDevice(slot.deviceId) { cached ->
                when (attr) {
                    Attr.LIGHT -> cached.copy(lightOn = target == 1, mainsOn = target == 1)
                    Attr.POSITION -> cached.copy(position = target)
                    Attr.MODE -> cached.copy(mode = target)
                    Attr.TARGET -> cached.copy(target = target)
                    Attr.HRV_SPEED -> cached.copy(hrvSpeed = target)
                    else -> cached
                }
            }
            refreshAllWidgets(context)
            delay(650L)
            runCatching { api.devices() }.getOrNull()?.let { updateCacheFromDevicesJson(store, it, prefs) }
            if (repo != null && repo.state.value.username != null) {
                withContext(Dispatchers.Main.immediate) {
                    repo.refresh()
                }
            }
        } finally {
            api?.close()
        }
    }

    private suspend fun executeHueWidgetToggle(
        credStore: CredentialStore,
        api: EntiaApi?,
        store: WidgetStore,
        slot: WidgetSlot,
        binding: HueBinding,
        prefs: Preferences,
        entiaSwitchId: Long?,
    ) {
        val isStandalone = slot.deviceId < 0
        if (entiaSwitchId != null && api != null) {
            runCatching { api.devices() }.getOrNull()?.let { updateCacheFromDevicesJson(store, it, prefs) }
        }
        val cache = store.loadCache()
        val cached = cache[slot.deviceId]
        val entiaCached = entiaSwitchId?.let { cache[it] }
        val mainsOn = if (entiaSwitchId == null) true else (entiaCached?.mainsOn ?: entiaCached?.lightOn ?: false)
        val hueCreds = runCatching { credStore.loadHue() }.getOrNull()
        val refs = binding.targets
        val siblingRefs = if (!mainsOn && entiaSwitchId != null) {
            val activeKeys = refs.asSequence().map { "${it.type}:${it.id}" }.toSet()
            val hasIndividualLight = refs.any { it.type == "light" }
            prefs.allHueTargetsOnEntiaSwitch(entiaSwitchId).filter { sib ->
                "${sib.type}:${sib.id}" !in activeKeys && !(hasIndividualLight && sib.type == "grouped_light")
            }
        } else emptyList()
        val hueApi = if (hueCreds != null && (refs.isNotEmpty() || siblingRefs.isNotEmpty())) {
            HueApi(hueCreds.bridgeIp, hueCreds.appKey)
        } else null
        try {
            if (!mainsOn && entiaSwitchId != null && api != null) {
                val preTargets = if (hueApi != null && siblingRefs.isNotEmpty()) {
                    runCatching { hueApi.fetchTargets() }.getOrNull()
                } else null
                val siblingPriors = siblingRefs.associateWith { sib ->
                    preTargets?.firstOrNull { it.id == sib.id && it.type == sib.type }
                        ?: preTargets?.firstOrNull { it.id == sib.id }
                }
                api.set(entiaSwitchId, Attr.LIGHT, 1)
                suspend fun applyTurnOnAndSiblings(): Boolean {
                    if (hueApi == null) return false
                    return coroutineScope {
                        val activeResults = refs.map { ref ->
                            async { runCatching { hueApi.setPower(ref.type, ref.id, true) } }
                        }
                        val siblingJobs = siblingPriors.entries.map { (sib, prior) ->
                            async {
                                runCatching {
                                    if (prior != null && prior.on) {
                                        val brightness = prior.brightness
                                        if (prior.supportsDimming && brightness != null && brightness > 0) {
                                            hueApi.setBrightness(sib.type, sib.id, brightness)
                                        } else {
                                            hueApi.setPower(sib.type, sib.id, true)
                                        }
                                    } else {
                                        hueApi.setPower(sib.type, sib.id, false)
                                    }
                                }
                            }
                        }
                        val succeeded = activeResults.awaitAll().any { it.isSuccess }
                        siblingJobs.awaitAll()
                        succeeded
                    }
                }
                var hueSucceeded = applyTurnOnAndSiblings()
                if (hueApi != null && (refs.isNotEmpty() || siblingPriors.isNotEmpty())) {
                    delay(1200L)
                    hueSucceeded = applyTurnOnAndSiblings() || hueSucceeded
                }
                store.updateCachedDevices { updated ->
                    val cur = updated[slot.deviceId] ?: CachedDeviceState("")
                    val nextHue = if (hueSucceeded) true else cur.hueOn
                    val switchState = updated[entiaSwitchId] ?: CachedDeviceState("")
                    updated[entiaSwitchId] = switchState.copy(
                        mainsOn = true,
                        hueOn = if (entiaSwitchId == slot.deviceId) nextHue else switchState.hueOn,
                        lightOn = if (entiaSwitchId == slot.deviceId) (nextHue ?: true) else (switchState.hueOn ?: true),
                    )
                    if (slot.deviceId != entiaSwitchId) {
                        updated[slot.deviceId] = cur.copy(
                            mainsOn = true,
                            hueOn = nextHue,
                            lightOn = nextHue ?: true,
                        )
                    }
                    prefs.standaloneHueLights.forEach { light ->
                        if (light.id != slot.deviceId && prefs.linkedEntiaSwitchId(light.id) == entiaSwitchId) {
                            val sibCur = updated[light.id] ?: CachedDeviceState(light.name, lightIcon = light.icon)
                            updated[light.id] = sibCur.copy(
                                mainsOn = true,
                                lightOn = sibCur.hueOn == true,
                            )
                        }
                    }
                }
            } else if (hueApi != null && refs.isNotEmpty()) {
                val targets = runCatching { hueApi.fetchTargets() }.getOrNull()
                val liveMatched = refs.mapNotNull { ref ->
                    targets?.firstOrNull { it.id == ref.id && it.type == ref.type }
                        ?: targets?.firstOrNull { it.id == ref.id }
                }
                val currentHueOn = if (liveMatched.isNotEmpty()) {
                    liveMatched.any { it.on }
                } else {
                    cached?.hueOn ?: !isStandalone
                }
                val nextHueOn = !currentHueOn
                val results = coroutineScope {
                    refs.map { ref -> async { runCatching { hueApi.setPower(ref.type, ref.id, nextHueOn) } } }.awaitAll()
                }
                if (results.any { it.isSuccess }) {
                    store.updateCachedDevice(slot.deviceId) {
                        it.copy(mainsOn = true, hueOn = nextHueOn, lightOn = nextHueOn)
                    }
                }
            }
        } finally {
            hueApi?.close()
        }
    }

    private suspend fun refreshCacheFromServer(context: Context) {
        val app = context.applicationContext as? EntAltApplication
        val repo = app?.repository
        if (repo != null && repo.state.value.demo) {
            val s = repo.state.value
            val store = WidgetStore(context)
            store.saveLanguage(s.preferences.language)
            store.saveCacheFromHome(s.home, s.preferences, s.hue)
            return
        }
        val prefs = runCatching { AppPreferences(context).load() }.getOrNull() ?: return
        if (!prefs.disclaimerAccepted) return
        val store = WidgetStore(context)
        store.saveLanguage(prefs.language)
        val credStore = CredentialStore(context)
        val credentials = runCatching { credStore.load() }.getOrNull() ?: return
        val api = EntiaApi(credentials, client = EntiaApi.sharedClient())
        try {
            val payload = api.devices()
            updateCacheFromDevicesJson(store, payload, prefs)
        } catch (_: Exception) {
            // Keep cached Entia device states and still refresh local Hue Bridge targets.
        } finally {
            api.close()
        }
        val allHueBindings = buildMap {
            putAll(prefs.hueBindings)
            prefs.standaloneHueLights.forEach { put(it.id, HueBinding(it.targets)) }
        }
        if (prefs.hueEnabled && allHueBindings.isNotEmpty()) {
            val hueCreds = runCatching { credStore.loadHue() }.getOrNull() ?: return
            val hueApi = HueApi(hueCreds.bridgeIp, hueCreds.appKey)
            try {
                val targets = runCatching { hueApi.fetchTargets() }.getOrNull() ?: return
                store.updateCachedDevices { cache ->
                    allHueBindings.forEach { (deviceId, binding) ->
                        val matched = binding.targets.mapNotNull { ref ->
                            targets.firstOrNull { it.id == ref.id && it.type == ref.type }
                                ?: targets.firstOrNull { it.id == ref.id }
                        }
                        if (matched.isEmpty()) return@forEach
                        val anyOn = matched.any { it.on }
                        val cached = cache[deviceId] ?: CachedDeviceState("")
                        val linkedEntiaId = prefs.linkedEntiaSwitchId(deviceId)
                        val mainsCached = linkedEntiaId?.let { cache[it] }
                        val mainsOn = if (linkedEntiaId == null) true
                        else (mainsCached?.mainsOn ?: mainsCached?.lightOn ?: (deviceId < 0))
                        val resolvedIcon = prefs.lightIcons[deviceId]
                            ?: prefs.standaloneHueLights.firstOrNull { it.id == deviceId }?.icon
                            ?: LightIcon.Bulb
                        cache[deviceId] = cached.copy(
                            mainsOn = mainsOn,
                            hueOn = anyOn,
                            lightOn = mainsOn && anyOn,
                            lightIcon = resolvedIcon,
                        )
                    }
                }
            } finally {
                hueApi.close()
            }
        }
    }

    private fun resolveTarget(slot: WidgetSlot, currentLightOn: Boolean): Pair<Int, Int> = when (slot.action) {
        WidgetActionType.LightToggle -> Attr.LIGHT to if (currentLightOn) 0 else 1
        WidgetActionType.BlindSet -> Attr.POSITION to slot.value.coerceIn(0, 100)
        WidgetActionType.ClimateMode -> Attr.MODE to slot.value.coerceIn(0, 2)
        WidgetActionType.ClimateTemp -> Attr.TARGET to slot.value.coerceIn(20, 60)
        WidgetActionType.VentilationSpeed -> Attr.HRV_SPEED to slot.value.coerceIn(0, 3)
    }

    private fun updateCacheFromDevicesJson(
        store: WidgetStore,
        payload: kotlinx.serialization.json.JsonElement,
        prefs: Preferences = Preferences(),
    ) {
        val list = when (payload) {
            is JsonArray -> payload
            is JsonObject -> payload["devices"] as? JsonArray ?: return
            else -> return
        }
        store.updateCachedDevices { cache ->
            list.forEach { element ->
                val obj = element as? JsonObject ?: return@forEach
                val id = (obj["id"] as? JsonPrimitive)?.contentOrNull?.toBigDecimalOrNull()?.longValueExactOrNull() ?: return@forEach
                val attrs = (obj["attributes"] as? JsonArray).orEmpty().mapNotNull { attrEl ->
                    val item = attrEl as? JsonObject ?: return@mapNotNull null
                    val attrId = (item["id"] as? JsonPrimitive)?.contentOrNull?.toBigDecimalOrNull()?.intValueExactOrNull()
                        ?: return@mapNotNull null
                    val value = item["value"]?.let { Reading(it).value }
                    attrId to value
                }.toMap()
                val cached = cache[id] ?: CachedDeviceState("")
                val mains = if (Attr.LIGHT in attrs) attrs[Attr.LIGHT]?.let { it == 1 } else cached.mainsOn
                val isHue = prefs.hueEnabled && prefs.hueBindingFor(id) != null
                val effectiveLight = if (isHue && cached.hueOn != null) (mains == true && cached.hueOn) else mains ?: cached.lightOn
                val resolvedIcon = prefs.lightIcons[id]
                    ?: prefs.standaloneHueLights.firstOrNull { it.id == id }?.icon
                    ?: LightIcon.Bulb
                cache[id] = cached.copy(
                    lightOn = effectiveLight,
                    mainsOn = mains,
                    position = if (Attr.POSITION in attrs) attrs[Attr.POSITION]?.takeIf { it in 0..100 } else cached.position,
                    mode = if (Attr.MODE in attrs) attrs[Attr.MODE]?.takeIf { it in 0..2 } else cached.mode,
                    target = if (Attr.TARGET in attrs) attrs[Attr.TARGET]?.takeIf { it in 20..60 } else cached.target,
                    hrvSpeed = if (Attr.HRV_SPEED in attrs) attrs[Attr.HRV_SPEED]?.takeIf { it in 0..3 } else cached.hrvSpeed,
                    lightIcon = resolvedIcon,
                )
            }
            if (prefs.hueEnabled) {
                prefs.standaloneHueLights.forEach { standalone ->
                    val linkedId = prefs.linkedEntiaSwitchId(standalone.id) ?: return@forEach
                    val switchState = cache[linkedId] ?: return@forEach
                    val mains = switchState.mainsOn ?: switchState.lightOn ?: return@forEach
                    val cur = cache[standalone.id] ?: CachedDeviceState(standalone.name, lightIcon = standalone.icon)
                    cache[standalone.id] = cur.copy(
                        mainsOn = mains,
                        lightOn = if (!mains) false else if (cur.hueOn != null) cur.hueOn else cur.lightOn,
                    )
                }
            }
        }
    }

    companion object {
        const val ACTION_WIDGET_TRIGGER = "si.hafnar.entalt.widget.ACTION_TRIGGER"
        const val EXTRA_SLOT_INDEX = "slot_index"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun refreshAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, EntAltWidgetProvider::class.java))
            if (ids.isNotEmpty()) updateWidgets(context, manager, ids)
        }

        data class ResponsiveGrid(
            val rowItemCounts: List<Int>,
            val tileWidthDp: Int,
            val tileHeightDp: Int,
        ) {
            val visibleCount: Int get() = rowItemCounts.sum()
            val rows: Int get() = rowItemCounts.size
            val maxCols: Int get() = rowItemCounts.maxOrNull() ?: 0
        }

        fun updateWidgets(
            context: Context,
            manager: AppWidgetManager,
            appWidgetIds: IntArray,
            optionsOverride: Bundle? = null,
        ) {
            val store = WidgetStore(context)
            val cache = store.loadCache()
            val localizedContext = context.withAppLanguage(store.loadLanguage())
            appWidgetIds.forEach { appWidgetId ->
                val config = store.loadConfig(appWidgetId)
                val options = optionsOverride ?: manager.getAppWidgetOptions(appWidgetId)
                val views = buildResponsiveRemoteViews(localizedContext, config, cache, options)
                manager.updateAppWidget(appWidgetId, views)
            }
        }

        private fun buildResponsiveRemoteViews(
            context: Context,
            config: WidgetConfig,
            cache: Map<Long, CachedDeviceState>,
            options: Bundle?,
        ): RemoteViews {
            if (Build.VERSION.SDK_INT >= 31 && options != null) {
                val sizes = if (Build.VERSION.SDK_INT >= 33) {
                    options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                }
                if (!sizes.isNullOrEmpty()) {
                    val viewMapping = sizes.associateWith { size ->
                        buildRemoteViews(
                            context = context,
                            config = config,
                            cache = cache,
                            widthDp = size.width.toInt(),
                            heightDp = size.height.toInt(),
                        )
                    }
                    return RemoteViews(viewMapping)
                }
            }
            val minWidthDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
            val minHeightDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0
            return buildRemoteViews(context, config, cache, minWidthDp, minHeightDp)
        }

        internal fun computeResponsiveLayout(
            itemCount: Int,
            widthDp: Int,
            heightDp: Int,
        ): ResponsiveGrid {
            val w = if (widthDp > 0) widthDp else 180
            val h = if (heightDp > 0) heightDp else 140
            if (itemCount <= 0) {
                return ResponsiveGrid(listOf(1), w, h)
            }
            val maxCols = when {
                w < 115 -> 1
                w < 185 -> 2
                w < 255 -> 3
                w < 340 -> 4
                else -> 5
            }
            val maxRows = when {
                h < 105 -> 1
                h < 175 -> 2
                h < 250 -> 3
                else -> 4
            }
            val visibleCount = minOf(itemCount, maxCols * maxRows)
            var bestCols = 1
            var bestRows = 1
            var bestScore = Double.MAX_VALUE
            for (r in 1..maxRows) {
                for (c in 1..maxCols) {
                    if (c * r < visibleCount) continue
                    // Skip grids that leave an entire row or column completely unused.
                    if (c > 1 && (c - 1) * r >= visibleCount) continue
                    if (r > 1 && c * (r - 1) >= visibleCount) continue
                    val tileAspect = (w.toDouble() / c) / (h.toDouble() / r)
                    val aspectPenalty = abs(ln(tileAspect / 1.35))
                    val emptyPenalty = (c * r - visibleCount) * 0.12
                    val score = aspectPenalty + emptyPenalty
                    if (score < bestScore) {
                        bestScore = score
                        bestCols = c
                        bestRows = r
                    }
                }
            }
            val base = visibleCount / bestRows
            val extra = visibleCount % bestRows
            val rowCounts = List(bestRows) { rowIdx -> base + if (rowIdx < extra) 1 else 0 }
            val actualCols = rowCounts.maxOrNull()?.coerceAtLeast(1) ?: bestCols
            return ResponsiveGrid(
                rowItemCounts = rowCounts,
                tileWidthDp = (w / actualCols).coerceAtLeast(48),
                tileHeightDp = (h / bestRows).coerceAtLeast(44),
            )
        }

        private fun lightIconDrawable(icon: LightIcon?): Int = when (icon) {
            LightIcon.Ceiling -> R.drawable.ic_widget_light_ceiling
            LightIcon.Pendant -> R.drawable.ic_widget_light_pendant
            LightIcon.TableLamp -> R.drawable.ic_widget_light_table_lamp
            LightIcon.FloorLamp -> R.drawable.ic_widget_light_floor_lamp
            LightIcon.WallLamp -> R.drawable.ic_widget_light_wall_lamp
            LightIcon.Chandelier -> R.drawable.ic_widget_light_chandelier
            LightIcon.Spotlight -> R.drawable.ic_widget_light_spotlight
            LightIcon.Strip -> R.drawable.ic_widget_light_strip
            LightIcon.Downlight -> R.drawable.ic_widget_light_downlight
            LightIcon.Nightlight -> R.drawable.ic_widget_light_nightlight
            LightIcon.Outdoor -> R.drawable.ic_widget_light_outdoor
            LightIcon.Bulb, null -> R.drawable.ic_widget_light_bulb
        }

        private fun cellLayoutFor(tileWidthDp: Int, tileHeightDp: Int): Int {
            val area = tileWidthDp * tileHeightDp
            return when {
                tileWidthDp < 78 || tileHeightDp < 62 || area <= 4800 -> R.layout.entalt_widget_cell_sm
                area <= 8200 -> R.layout.entalt_widget_cell_md
                area <= 12500 -> R.layout.entalt_widget_cell_lg
                else -> R.layout.entalt_widget_cell_xl
            }
        }

        private fun buildRemoteViews(
            context: Context,
            config: WidgetConfig,
            cache: Map<Long, CachedDeviceState>,
            widthDp: Int,
            heightDp: Int,
        ): RemoteViews {
            val root = RemoteViews(context.packageName, R.layout.entalt_widget_root)
            root.removeAllViews(R.id.widget_grid)

            val layout = computeResponsiveLayout(config.items.size, widthDp, heightDp)
            val cellLayout = cellLayoutFor(layout.tileWidthDp, layout.tileHeightDp)
            val activeTextColor = ContextCompat.getColor(context, R.color.widget_active_text)
            val normalTextColor = ContextCompat.getColor(context, R.color.widget_text)
            val secondaryTextColor = ContextCompat.getColor(context, R.color.widget_text_secondary)

            var itemIndex = 0
            layout.rowItemCounts.forEach { countInRow ->
                val rowView = RemoteViews(context.packageName, R.layout.entalt_widget_row)
                repeat(countInRow) {
                    val slotIndex = itemIndex++
                    val slot = config.items.getOrNull(slotIndex)
                    val cell = RemoteViews(context.packageName, cellLayout)

                    if (slot == null) {
                        cell.setInt(R.id.cell_button, "setBackgroundResource", R.drawable.widget_cell_empty_background)
                        cell.setViewVisibility(R.id.cell_assigned_content, View.GONE)
                        cell.setViewVisibility(R.id.cell_empty_content, View.VISIBLE)
                        cell.setInt(R.id.cell_empty_icon, "setColorFilter", secondaryTextColor)
                        cell.setTextViewText(R.id.cell_empty_label, context.getString(R.string.widget_add_short))
                        cell.setTextColor(R.id.cell_empty_label, secondaryTextColor)
                    } else {
                        val cached = cache[slot.deviceId]
                        val active = when (slot.action) {
                            WidgetActionType.LightToggle -> cached?.lightOn == true
                            WidgetActionType.BlindSet -> cached?.position == slot.value
                            WidgetActionType.ClimateMode -> cached?.mode == slot.value
                            WidgetActionType.ClimateTemp -> cached?.target == slot.value
                            WidgetActionType.VentilationSpeed -> cached?.hrvSpeed == slot.value
                        }
                        val iconRes = when (slot.action) {
                            WidgetActionType.LightToggle -> lightIconDrawable(cached?.lightIcon)
                            WidgetActionType.BlindSet -> R.drawable.ic_widget_blinds
                            WidgetActionType.ClimateMode, WidgetActionType.ClimateTemp -> R.drawable.ic_widget_climate
                            WidgetActionType.VentilationSpeed -> R.drawable.ic_widget_ventilation
                        }
                        val statusText = when (slot.action) {
                            WidgetActionType.LightToggle -> when (cached?.lightOn) {
                                true -> context.getString(R.string.on)
                                false -> context.getString(R.string.off)
                                null -> "—"
                            }
                            WidgetActionType.BlindSet -> context.getString(R.string.percent, slot.value)
                            WidgetActionType.ClimateMode -> context.getString(
                                when (slot.value) {
                                    1 -> R.string.cool
                                    2 -> R.string.heat
                                    else -> R.string.off
                                }
                            )
                            WidgetActionType.ClimateTemp -> context.getString(R.string.panel_temperature, slot.value / 2.0)
                            WidgetActionType.VentilationSpeed -> context.getString(
                                when (slot.value) {
                                    1 -> R.string.low
                                    2 -> R.string.medium
                                    3 -> R.string.high
                                    else -> R.string.off
                                }
                            )
                        }
                        cell.setInt(
                            R.id.cell_button,
                            "setBackgroundResource",
                            if (active) R.drawable.widget_cell_active_background else R.drawable.widget_cell_background
                        )
                        cell.setViewVisibility(R.id.cell_assigned_content, View.VISIBLE)
                        cell.setViewVisibility(R.id.cell_empty_content, View.GONE)
                        cell.setImageViewResource(R.id.cell_icon, iconRes)
                        cell.setInt(R.id.cell_icon, "setColorFilter", if (active) activeTextColor else normalTextColor)
                        cell.setTextViewText(R.id.cell_title, slot.label)
                        cell.setTextColor(R.id.cell_title, if (active) activeTextColor else normalTextColor)
                        cell.setTextViewText(R.id.cell_status, statusText)
                        cell.setTextColor(R.id.cell_status, if (active) activeTextColor else secondaryTextColor)
                    }

                    val slotUri = Uri.parse("entalt://widget/${config.appWidgetId}/slot/$slotIndex")
                    val pendingIntent = if (slot == null) {
                        val configIntent = Intent(context, EntAltWidgetConfigureActivity::class.java).apply {
                            action = AppWidgetManager.ACTION_APPWIDGET_CONFIGURE
                            data = slotUri
                            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, config.appWidgetId)
                            putExtra(EXTRA_SLOT_INDEX, slotIndex)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                        PendingIntent.getActivity(
                            context,
                            config.appWidgetId * 100 + slotIndex,
                            configIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                    } else {
                        val triggerIntent = Intent(context, EntAltWidgetProvider::class.java).apply {
                            action = ACTION_WIDGET_TRIGGER
                            data = slotUri
                            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, config.appWidgetId)
                            putExtra(EXTRA_SLOT_INDEX, slotIndex)
                        }
                        PendingIntent.getBroadcast(
                            context,
                            config.appWidgetId * 100 + slotIndex,
                            triggerIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                    }
                    cell.setOnClickPendingIntent(R.id.cell_button, pendingIntent)
                    rowView.addView(R.id.widget_row, cell)
                }
                root.addView(R.id.widget_grid, rowView)
            }
            return root
        }
    }
}

private fun java.math.BigDecimal.longValueExactOrNull(): Long? =
    try { longValueExact() } catch (_: ArithmeticException) { null }

private fun java.math.BigDecimal.intValueExactOrNull(): Int? =
    try { intValueExact() } catch (_: ArithmeticException) { null }
