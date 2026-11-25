@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package si.hafnar.entalt.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import si.hafnar.entalt.EntAltApplication
import si.hafnar.entalt.MainActivity
import si.hafnar.entalt.R
import si.hafnar.entalt.data.AppState
import si.hafnar.entalt.data.resolveLightState
import si.hafnar.entalt.domain.Attr
import si.hafnar.entalt.domain.Device
import si.hafnar.entalt.ui.EntAltTheme
import si.hafnar.entalt.ui.lightGlyph

class EntAltWidgetConfigureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setResult(RESULT_CANCELED)

        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val app = application as EntAltApplication
        val store = WidgetStore(this)
        val initialConfig = store.loadConfig(appWidgetId)
        val initialSlotIndex = intent?.getIntExtra(EntAltWidgetProvider.EXTRA_SLOT_INDEX, -1)?.takeIf { it >= 0 }
        val options = AppWidgetManager.getInstance(this)?.getAppWidgetOptions(appWidgetId)
        val previewWidthDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)?.takeIf { it > 0 } ?: 220
        val previewHeightDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)?.takeIf { it > 0 } ?: 140

        setContent {
            val state by app.repository.state.collectAsStateWithLifecycle()
            EntAltTheme(state.preferences) {
                WidgetConfigureScreen(
                    state = state,
                    initial = initialConfig,
                    openPickerImmediately = initialSlotIndex != null && initialConfig.items.isEmpty(),
                    previewWidthDp = previewWidthDp,
                    previewHeightDp = previewHeightDp,
                    onCancel = { finish() },
                    onOpenApp = {
                        startActivity(Intent(this, MainActivity::class.java))
                    },
                    onSave = { config ->
                        store.saveConfig(config)
                        store.saveCacheFromHome(state.home, state.preferences, state.hue)
                        val manager = AppWidgetManager.getInstance(this)
                        EntAltWidgetProvider.updateWidgets(this, manager, intArrayOf(appWidgetId))
                        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
                        finish()
                    }
                )
            }
        }
    }
}

@Composable private fun WidgetConfigureScreen(
    state: AppState,
    initial: WidgetConfig,
    openPickerImmediately: Boolean = false,
    previewWidthDp: Int = 220,
    previewHeightDp: Int = 140,
    onCancel: () -> Unit,
    onOpenApp: () -> Unit,
    onSave: (WidgetConfig) -> Unit
) {
    var items by remember { mutableStateOf(initial.items) }
    // null = closed, -1 = adding new item, >= 0 = editing existing item at index
    var editingIndex by remember { mutableStateOf<Int?>(if (openPickerImmediately) -1 else null) }

    val controllableDevices = remember(state.home) {
        state.home.devices.values
            .filter { !it.hidden && it.available && (it.isLight || it.isBlind || it.isClimate || it.isVentilation) }
            .sortedWith(compareBy({ state.home.rooms.indexOfFirst { r -> r.id == it.roomId }.let { idx -> if (idx < 0) Int.MAX_VALUE else idx } }, { it.name }))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.widget_configure_title)) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    if (state.preferences.disclaimerAccepted && (state.username != null || state.demo)) {
                        Button(
                            onClick = {
                                onSave(
                                    WidgetConfig(
                                        appWidgetId = initial.appWidgetId,
                                        items = items,
                                    )
                                )
                            },
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text(stringResource(R.string.save))
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (state.restoring) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        if (!state.preferences.disclaimerAccepted || (state.username == null && !state.demo)) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    stringResource(R.string.widget_sign_in_required),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
                Button(onClick = onOpenApp) {
                    Text(stringResource(R.string.widget_open_app))
                }
            }
            return@Scaffold
        }

        val previewLayout = remember(items.size, previewWidthDp, previewHeightDp) {
            EntAltWidgetProvider.computeResponsiveLayout(items.size, previewWidthDp, previewHeightDp)
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Live responsive preview matching how the widget adapts to its launcher bounds
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height((previewLayout.rows * 74).coerceIn(80, 260).dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    var idx = 0
                    previewLayout.rowItemCounts.forEach { countInRow ->
                        Row(
                            Modifier.fillMaxWidth().weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            repeat(countInRow) {
                                val slotIdx = idx++
                                val slot = items.getOrNull(slotIdx)
                                val device = slot?.let { state.home.devices[it.deviceId] }
                                val previewStatus = slot?.let { previewSlotStatus(it, device, state) }
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(18.dp))
                                        .clickable { editingIndex = if (slot != null) slotIdx else -1 },
                                    shape = RoundedCornerShape(18.dp),
                                    color = if (slot != null) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f),
                                    border = BorderStroke(
                                        1.dp,
                                        if (slot != null) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                                        else MaterialTheme.colorScheme.outlineVariant
                                    )
                                ) {
                                    if (slot == null) {
                                        Column(
                                            Modifier.fillMaxSize().padding(6.dp),
                                            verticalArrangement = Arrangement.Center,
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Icon(
                                                Icons.Outlined.Add,
                                                null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Text(
                                                stringResource(R.string.widget_add_short),
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                textAlign = TextAlign.Center,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    } else {
                                        Column(
                                            Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(
                                                Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Icon(
                                                    slotIcon(slot.action, device),
                                                    null,
                                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Text(
                                                    previewStatus.orEmpty(),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            Text(
                                                slot.label,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Text(
                stringResource(R.string.widget_adaptive_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.widget_actions_title), style = MaterialTheme.typography.titleMedium)
                if (items.size < 16) {
                    FilledTonalButton(onClick = { editingIndex = -1 }) {
                        Icon(Icons.Outlined.Add, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.widget_add_action))
                    }
                }
            }

            if (items.isEmpty()) {
                OutlinedButton(
                    onClick = { editingIndex = -1 },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Outlined.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.widget_tap_to_assign))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items.forEachIndexed { index, slot ->
                        val device = state.home.devices[slot.deviceId]
                        val status = previewSlotStatus(slot, device, state)
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { editingIndex = index },
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    slotIcon(slot.action, device),
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        slot.label,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        status,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        if (index > 0) {
                                            val updated = items.toMutableList()
                                            val moved = updated.removeAt(index)
                                            updated.add(index - 1, moved)
                                            items = updated
                                        }
                                    },
                                    enabled = index > 0
                                ) {
                                    Icon(Icons.Outlined.KeyboardArrowUp, null)
                                }
                                IconButton(
                                    onClick = {
                                        if (index < items.lastIndex) {
                                            val updated = items.toMutableList()
                                            val moved = updated.removeAt(index)
                                            updated.add(index + 1, moved)
                                            items = updated
                                        }
                                    },
                                    enabled = index < items.lastIndex
                                ) {
                                    Icon(Icons.Outlined.KeyboardArrowDown, null)
                                }
                                IconButton(onClick = {
                                    items = items.filterIndexed { i, _ -> i != index }
                                }) {
                                    Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.widget_clear_slot))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editingIndex?.let { index ->
        SlotPickerDialog(
            state = state,
            devices = controllableDevices,
            existing = items.getOrNull(index),
            onDismiss = { editingIndex = null },
            onClear = {
                if (index in items.indices) {
                    items = items.filterIndexed { i, _ -> i != index }
                }
                editingIndex = null
            },
            onConfirm = { newSlot ->
                items = if (index in items.indices) {
                    items.toMutableList().apply { set(index, newSlot) }
                } else {
                    (items + newSlot).take(16)
                }
                editingIndex = null
            }
        )
    }
}

@Composable private fun SlotPickerDialog(
    state: AppState,
    devices: List<Device>,
    existing: WidgetSlot?,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onConfirm: (WidgetSlot) -> Unit
) {
    var selectedDevice by remember { mutableStateOf(devices.firstOrNull { it.id == existing?.deviceId }) }
    var action by remember { mutableStateOf(existing?.action ?: WidgetActionType.LightToggle) }
    var value by remember { mutableIntStateOf(existing?.value ?: 0) }
    var label by remember { mutableStateOf(existing?.label ?: "") }

    LaunchedEffect(selectedDevice?.id) {
        val device = selectedDevice ?: return@LaunchedEffect
        when {
            device.isLight -> {
                action = WidgetActionType.LightToggle
                value = 0
            }
            device.isBlind -> {
                action = WidgetActionType.BlindSet
                value = value.coerceIn(0, 100)
            }
            device.isClimate -> {
                if (Attr.MODE in device.attributes) {
                    if (action != WidgetActionType.ClimateMode && action != WidgetActionType.ClimateTemp) {
                        action = WidgetActionType.ClimateMode
                        value = 2
                    } else if (action == WidgetActionType.ClimateMode) {
                        value = value.coerceIn(0, 2)
                    } else {
                        value = value.coerceIn(20, 60)
                    }
                } else {
                    action = WidgetActionType.ClimateTemp
                    value = if (value in 20..60) value else 44
                }
            }
            device.isVentilation -> {
                action = WidgetActionType.VentilationSpeed
                value = value.coerceIn(0, 3)
            }
        }
    }

    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        if (selectedDevice != null) selectedDevice = null else onDismiss()
                    }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                    }
                    Text(
                        stringResource(if (selectedDevice == null) R.string.widget_choose_device else R.string.widget_choose_action),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge
                    )
                    if (existing != null) {
                        TextButton(onClick = onClear) {
                            Text(stringResource(R.string.widget_clear_slot))
                        }
                    }
                }

                val device = selectedDevice
                if (device == null) {
                    if (devices.isEmpty()) {
                        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                            if (state.syncing) {
                                CircularProgressIndicator()
                            } else {
                                Text(stringResource(R.string.widget_no_devices), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(devices, key = { it.id }) { item ->
                                val roomName = state.home.rooms.firstOrNull { it.id == item.roomId }?.name
                                OutlinedButton(
                                    onClick = {
                                        selectedDevice = item
                                        if (existing?.deviceId == item.id) {
                                            label = existing.label
                                            action = existing.action
                                            value = existing.value
                                        } else {
                                            label = item.name
                                            when {
                                                item.isLight -> {
                                                    action = WidgetActionType.LightToggle
                                                    value = 0
                                                }
                                                item.isBlind -> {
                                                    action = WidgetActionType.BlindSet
                                                    value = item.value(Attr.POSITION)?.takeIf { it in 0..100 } ?: 0
                                                }
                                                item.isClimate -> {
                                                    if (Attr.MODE in item.attributes) {
                                                        action = WidgetActionType.ClimateMode
                                                        value = 2
                                                    } else {
                                                        action = WidgetActionType.ClimateTemp
                                                        value = item.value(Attr.TARGET)?.takeIf { it in 20..60 } ?: 44
                                                    }
                                                }
                                                item.isVentilation -> {
                                                    action = WidgetActionType.VentilationSpeed
                                                    value = 1
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Icon(defaultDeviceIcon(item), null)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                                        Text(item.name, style = MaterialTheme.typography.titleMedium)
                                        roomName?.let {
                                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    Icon(Icons.Outlined.ChevronRight, null)
                                }
                            }
                        }
                    }
                } else {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        OutlinedTextField(
                            value = label,
                            onValueChange = { label = it.take(40) },
                            label = { Text(stringResource(R.string.widget_button_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        when {
                            device.isLight -> {
                                Text(stringResource(R.string.widget_toggle_light), style = MaterialTheme.typography.titleMedium)
                            }
                            device.isBlind -> {
                                val presets = state.preferences.blindPresets[device.id]
                                Text(stringResource(R.string.widget_set_blind, value), style = MaterialTheme.typography.titleMedium)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    listOf(0, 50, 100).forEach { pct ->
                                        FilterChip(
                                            selected = value == pct,
                                            onClick = { value = pct },
                                            label = { Text(stringResource(R.string.percent, pct)) }
                                        )
                                    }
                                    presets?.slot1?.let { pct ->
                                        FilterChip(
                                            selected = value == pct,
                                            onClick = { value = pct },
                                            label = { Text(stringResource(R.string.widget_preset_slot, 1, pct)) }
                                        )
                                    }
                                    presets?.slot2?.let { pct ->
                                        FilterChip(
                                            selected = value == pct,
                                            onClick = { value = pct },
                                            label = { Text(stringResource(R.string.widget_preset_slot, 2, pct)) }
                                        )
                                    }
                                }
                                Slider(
                                    value = value.toFloat(),
                                    onValueChange = { value = it.roundToInt().coerceIn(0, 100) },
                                    valueRange = 0f..100f
                                )
                            }
                            device.isClimate -> {
                                val supportsMode = Attr.MODE in device.attributes
                                val modeOptions = buildList {
                                    add(0 to stringResource(R.string.off))
                                    add(2 to stringResource(R.string.heat))
                                    if (!state.preferences.hideCooling) add(1 to stringResource(R.string.cool))
                                }
                                if (supportsMode) {
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FilterChip(
                                            selected = action == WidgetActionType.ClimateMode,
                                            onClick = { action = WidgetActionType.ClimateMode; value = 2 },
                                            label = { Text(stringResource(R.string.climate)) }
                                        )
                                        FilterChip(
                                            selected = action == WidgetActionType.ClimateTemp,
                                            onClick = { action = WidgetActionType.ClimateTemp; value = 44 },
                                            label = { Text(stringResource(R.string.set_temperature)) }
                                        )
                                    }
                                }
                                if (supportsMode && action == WidgetActionType.ClimateMode) {
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        modeOptions.forEach { (modeVal, modeLabel) ->
                                            FilterChip(
                                                selected = value == modeVal,
                                                onClick = { value = modeVal },
                                                label = { Text(modeLabel) }
                                            )
                                        }
                                    }
                                } else {
                                    val clamped = value.coerceIn(20, 60)
                                    Text(stringResource(R.string.celsius, clamped / 2.0), style = MaterialTheme.typography.titleMedium)
                                    Slider(
                                        value = clamped.toFloat(),
                                        onValueChange = { value = it.roundToInt().coerceIn(20, 60) },
                                        valueRange = 20f..60f,
                                        steps = 39
                                    )
                                }
                            }
                            device.isVentilation -> {
                                val speeds = listOf(
                                    0 to stringResource(R.string.off),
                                    1 to stringResource(R.string.low),
                                    2 to stringResource(R.string.medium),
                                    3 to stringResource(R.string.high)
                                )
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    speeds.forEach { (spd, spdLabel) ->
                                        FilterChip(
                                            selected = value == spd,
                                            onClick = { value = spd },
                                            label = { Text(spdLabel) }
                                        )
                                    }
                                }
                            }
                        }

                        Button(
                            onClick = {
                                val normalizedValue = when (action) {
                                    WidgetActionType.LightToggle -> 0
                                    WidgetActionType.BlindSet -> value.coerceIn(0, 100)
                                    WidgetActionType.ClimateMode -> value.coerceIn(0, 2)
                                    WidgetActionType.ClimateTemp -> value.coerceIn(20, 60)
                                    WidgetActionType.VentilationSpeed -> value.coerceIn(0, 3)
                                }
                                onConfirm(
                                    WidgetSlot(
                                        deviceId = device.id,
                                        label = label.trim().ifEmpty { device.name },
                                        action = action,
                                        value = normalizedValue
                                    )
                                )
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) {
                            Text(stringResource(R.string.save))
                        }
                    }
                }
            }
        }
    }
}

private fun defaultDeviceIcon(device: Device) = when {
    device.isLight -> lightGlyph(device.lightIcon)
    device.isBlind -> Icons.Outlined.Blinds
    device.isClimate -> Icons.Outlined.Thermostat
    device.isVentilation -> Icons.Outlined.Air
    else -> Icons.Outlined.Sensors
}

private fun slotIcon(action: WidgetActionType, device: Device?) = when (action) {
    WidgetActionType.LightToggle -> lightGlyph(device?.lightIcon ?: si.hafnar.entalt.domain.LightIcon.Bulb)
    WidgetActionType.BlindSet -> Icons.Outlined.Blinds
    WidgetActionType.ClimateMode, WidgetActionType.ClimateTemp -> Icons.Outlined.Thermostat
    WidgetActionType.VentilationSpeed -> Icons.Outlined.Air
}

@Composable private fun previewSlotStatus(slot: WidgetSlot, device: Device?, state: AppState): String = when (slot.action) {
    WidgetActionType.LightToggle -> {
        val isOn = if (device != null) {
            resolveLightState(state.home, state.preferences, state.hue, device).on
        } else false
        stringResource(if (isOn) R.string.on else R.string.off)
    }
    WidgetActionType.BlindSet -> stringResource(R.string.percent, slot.value)
    WidgetActionType.ClimateMode -> stringResource(
        when (slot.value) {
            1 -> R.string.cool
            2 -> R.string.heat
            else -> R.string.off
        }
    )
    WidgetActionType.ClimateTemp -> stringResource(R.string.panel_temperature, slot.value / 2.0)
    WidgetActionType.VentilationSpeed -> stringResource(
        when (slot.value) {
            1 -> R.string.low
            2 -> R.string.medium
            3 -> R.string.high
            else -> R.string.off
        }
    )
}
