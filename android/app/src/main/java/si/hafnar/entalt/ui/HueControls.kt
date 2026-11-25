@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package si.hafnar.entalt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import si.hafnar.entalt.R
import si.hafnar.entalt.data.*
import si.hafnar.entalt.domain.Attr
import si.hafnar.entalt.domain.Device
import si.hafnar.entalt.domain.Home
import si.hafnar.entalt.domain.LightIcon

@Composable private fun hueScale(): Float {
    val window = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current.density
    val minDp = min(window.width, window.height) / density
    return (minDp / 480f).coerceIn(0.66f, 1.15f)
}

@Composable internal fun hueKindLabel(kind: HueTargetKind): String = stringResource(
    when (kind) {
        HueTargetKind.Light -> R.string.hue_kind_light
        HueTargetKind.Room -> R.string.hue_kind_room
        HueTargetKind.Zone -> R.string.hue_kind_zone
    }
)

@Composable private fun tempPresetLabel(preset: HueTempPreset): String = stringResource(
    when (preset) {
        HueTempPreset.Warm -> R.string.hue_temp_warm
        HueTempPreset.Soft -> R.string.hue_temp_soft
        HueTempPreset.Neutral -> R.string.hue_temp_neutral
        HueTempPreset.Daylight -> R.string.hue_temp_daylight
        HueTempPreset.Cool -> R.string.hue_temp_cool
    }
)

@Composable private fun colorPresetLabel(preset: HueColorPreset): String = stringResource(
    when (preset) {
        HueColorPreset.WarmAmber -> R.string.hue_color_warm_amber
        HueColorPreset.Coral -> R.string.hue_color_coral
        HueColorPreset.Sunset -> R.string.hue_color_sunset
        HueColorPreset.Lavender -> R.string.hue_color_lavender
        HueColorPreset.OceanBlue -> R.string.hue_color_ocean_blue
        HueColorPreset.Emerald -> R.string.hue_color_emerald
    }
)

internal data class EntiaSwitchOption(
    val id: Long,
    val label: String,
)

internal fun entiaSwitchOptions(
    home: Home,
    roomId: Long?,
    includeId: Long? = null,
): List<EntiaSwitchOption> {
    val roomIndex = home.rooms.withIndex().associate { it.value.id to it.index }
    val roomName = home.rooms.associate { it.id to it.name }
    return home.devices.values
        .filter { !it.isStandaloneHue && it.isLight && ((!it.hidden && it.available) || it.id == includeId) }
        .sortedWith(
            compareBy<Device>(
                { if (it.roomId == roomId) 0 else 1 },
                { roomIndex[it.roomId] ?: Int.MAX_VALUE },
                { it.name.lowercase() },
            )
        )
        .map { sw ->
            val rName = roomName[sw.roomId]
            val label = if (rName != null && sw.roomId != roomId) "${sw.name} ($rName)" else sw.name
            EntiaSwitchOption(sw.id, label)
        }
}

internal fun entiaSwitchPanelItems(
    noneLabel: String,
    options: List<EntiaSwitchOption>,
): List<Pair<String, String>> =
    listOf("none" to noneLabel) + options.map { it.id.toString() to it.label }

@Composable internal fun PhysicalEntiaSwitchChips(
    options: List<EntiaSwitchOption>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.hue_physical_switch),
            style = MaterialTheme.typography.labelLarge
        )
        Text(
            stringResource(R.string.hue_physical_switch_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            FilterChip(
                selected = selectedId == null,
                onClick = { onSelect(null) },
                label = { Text(stringResource(R.string.hue_physical_switch_none)) },
                leadingIcon = if (selectedId == null) ({ Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)) }) else null,
                modifier = Modifier.heightIn(min = 44.dp)
            )
            options.forEach { option ->
                val selected = selectedId == option.id
                FilterChip(
                    selected = selected,
                    onClick = { onSelect(option.id) },
                    label = { Text(option.label) },
                    leadingIcon = if (selected) ({ Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)) }) else null,
                    modifier = Modifier.heightIn(min = 44.dp)
                )
            }
        }
    }
}

private enum class CompactHueTab { Dimmer, White, Color }

@Composable internal fun HueControlWindow(
    device: Device,
    state: AppState,
    repository: EntiaRepository,
    onOpenEditor: () -> Unit,
    onDismiss: () -> Unit,
) {
    val liveDevice = state.home.devices[device.id] ?: device
    val resolved = resolveLightState(state.home, state.preferences, state.hue, liveDevice)
    val binding = resolved.binding
    val matchedTargets = state.hue.targetsFor(binding)
    var selectedTargetId by remember(device.id) { mutableStateOf<String?>(null) }
    if (selectedTargetId != null && matchedTargets.none { it.id == selectedTargetId }) {
        selectedTargetId = null
    }
    val target = state.hue.targetFor(binding, selectedTargetId)
    val relaySwitchId = resolved.linkedEntiaId ?: device.id.takeIf { !liveDevice.isStandaloneHue }
    val entiaSwitch = resolved.entiaSwitch
    val mainsOn = resolved.mainsOn
    val command = state.commands[device.id] ?: relaySwitchId?.let { state.commands[it] }
    val usable = liveDevice.available && (entiaSwitch?.available ?: true) && state.foreground && !state.needsSignIn && command?.pending != true
    val hueOn = mainsOn && (target?.on == true)
    val winScale = hueScale()

    var adjustingBrightness by remember(device.id, selectedTargetId) { mutableStateOf(false) }
    var draftBrightness by remember(device.id, selectedTargetId) { mutableIntStateOf((target?.brightness ?: 100).coerceIn(1, 100)) }
    LaunchedEffect(target?.brightness, selectedTargetId) {
        if (!adjustingBrightness && target?.brightness != null) {
            draftBrightness = target.brightness.coerceIn(1, 100)
        }
    }

    val mirekMin = target?.mirekMin ?: 153
    val mirekMax = target?.mirekMax ?: 500
    var adjustingMirek by remember(device.id, selectedTargetId) { mutableStateOf(false) }
    var draftMirek by remember(device.id, selectedTargetId) { mutableIntStateOf((target?.mirek ?: 370).coerceIn(mirekMin, mirekMax)) }
    LaunchedEffect(target?.mirek, selectedTargetId) {
        if (!adjustingMirek && target?.mirek != null) {
            draftMirek = target.mirek.coerceIn(mirekMin, mirekMax)
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(horizontal = (14 * winScale).dp, vertical = (8 * winScale).dp)
            ) {
                val compactHeight = maxHeight < 520.dp
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(if (compactHeight) (6 * winScale).dp else 12.dp)
                ) {
                    // Header
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = if (compactHeight) 44.dp else 52.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalIconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .width(if (compactHeight) (64 * winScale).coerceAtLeast(52f).dp else 64.dp)
                                .height(if (compactHeight) (44 * winScale).coerceAtLeast(40f).dp else 48.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back), Modifier.size((24 * winScale).dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                liveDevice.name,
                                fontSize = if (compactHeight) (20 * winScale).coerceAtLeast(15f).sp else 20.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val subtitle = target?.name ?: binding?.targetName?.takeIf { it.isNotBlank() }
                            if (subtitle != null && subtitle != liveDevice.name) {
                                Text(
                                    subtitle,
                                    fontSize = if (compactHeight) (13 * winScale).coerceAtLeast(11f).sp else 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                onDismiss()
                                onOpenEditor()
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Outlined.Edit, stringResource(R.string.edit_device))
                        }
                    }

                    if (matchedTargets.size > 1) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = selectedTargetId == null,
                                onClick = { selectedTargetId = null },
                                label = { Text(stringResource(R.string.hue_all_targets, matchedTargets.size)) }
                            )
                            matchedTargets.forEach { item ->
                                FilterChip(
                                    selected = selectedTargetId == item.id,
                                    onClick = { selectedTargetId = item.id },
                                    label = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                )
                            }
                        }
                    }

                    if (compactHeight) {
                        // Compact 480x480 square panel layout: tabbed view keeps all controls visible without clipping
                        val tabs = buildList {
                            add(CompactHueTab.Dimmer)
                            if (target?.supportsColorTemp == true) add(CompactHueTab.White)
                            if (target?.supportsColor == true) add(CompactHueTab.Color)
                        }
                        var selectedTab by remember(device.id, tabs.size) { mutableStateOf(CompactHueTab.Dimmer) }
                        if (selectedTab !in tabs) selectedTab = CompactHueTab.Dimmer

                        if (tabs.size > 1) {
                            SingleChoiceSegmentedButtonRow(
                                Modifier.fillMaxWidth().height((38 * winScale).coerceAtLeast(34f).dp)
                            ) {
                                tabs.forEachIndexed { index, tab ->
                                    val labelId = when (tab) {
                                        CompactHueTab.Dimmer -> R.string.hue_tab_dimmer
                                        CompactHueTab.White -> R.string.hue_tab_white
                                        CompactHueTab.Color -> R.string.hue_tab_color
                                    }
                                    SegmentedButton(
                                        selected = selectedTab == tab,
                                        onClick = { selectedTab = tab },
                                        shape = SegmentedButtonDefaults.itemShape(index = index, count = tabs.size)
                                    ) {
                                        Text(
                                            stringResource(labelId),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            fontSize = (15 * winScale).coerceAtLeast(12f).sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                        }

                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy((8 * winScale).dp)
                        ) {
                            when (selectedTab) {
                                CompactHueTab.Dimmer -> {
                                    if (target == null) {
                                        CompactUnlinkedCard(onEdit = {
                                            onDismiss()
                                            onOpenEditor()
                                        }, scale = winScale)
                                    } else {
                                        CompactDimmerControls(
                                            deviceId = device.id,
                                            target = target,
                                            hueOn = hueOn,
                                            usable = usable,
                                            draftBrightness = draftBrightness,
                                            scale = winScale,
                                            onDraftBrightness = {
                                                adjustingBrightness = true
                                                draftBrightness = it
                                            },
                                            onCommitBrightness = { value ->
                                                adjustingBrightness = false
                                                repository.setHueBrightness(device.id, value, selectedTargetId)
                                            },
                                            onPowerClick = { repository.setHuePower(device.id, !hueOn, selectedTargetId) },
                                            onStepBrightness = { next ->
                                                draftBrightness = next
                                                repository.setHueBrightness(device.id, next, selectedTargetId)
                                            },
                                        )
                                    }
                                    if (relaySwitchId != null && entiaSwitch != null) {
                                        MainsRelayCard(
                                            mainsOn = mainsOn,
                                            usable = usable,
                                            compact = true,
                                            scale = winScale,
                                            onToggleMains = {
                                                repository.command(relaySwitchId, Attr.LIGHT, if (mainsOn) 0 else 1)
                                            },
                                        )
                                    }
                                }
                                CompactHueTab.White -> {
                                    ColorTemperatureSection(
                                        mirek = draftMirek,
                                        mirekRange = mirekMin..mirekMax,
                                        usable = usable,
                                        compact = true,
                                        scale = winScale,
                                        onSelectPreset = { preset ->
                                            val clamped = preset.mirek.coerceIn(mirekMin, mirekMax)
                                            draftMirek = clamped
                                            repository.setHueColorTemp(device.id, clamped, selectedTargetId)
                                        },
                                        onDraftMirek = {
                                            adjustingMirek = true
                                            draftMirek = it
                                        },
                                        onCommitMirek = {
                                            adjustingMirek = false
                                            repository.setHueColorTemp(device.id, draftMirek, selectedTargetId)
                                        },
                                    )
                                }
                                CompactHueTab.Color -> {
                                    ColorPresetsSection(
                                        currentXy = target?.xy,
                                        usable = usable,
                                        compact = true,
                                        scale = winScale,
                                        onSelectColor = { preset ->
                                            repository.setHueColorXy(device.id, preset.x, preset.y, selectedTargetId)
                                        },
                                    )
                                }
                            }
                            state.hue.error?.let { err ->
                                Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    } else {
                        // Phone / tablet layout: all supported sections in a clean scrollable column
                        Column(
                            Modifier
                                .weight(1f)
                                .widthIn(max = 560.dp)
                                .align(Alignment.CenterHorizontally)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (target == null) {
                                CompactUnlinkedCard(onEdit = {
                                    onDismiss()
                                    onOpenEditor()
                                }, scale = 1f)
                            } else {
                                PhoneDimmerCard(
                                    target = target,
                                    hueOn = hueOn,
                                    usable = usable,
                                    draftBrightness = draftBrightness,
                                    onDraftBrightness = {
                                        adjustingBrightness = true
                                        draftBrightness = it
                                    },
                                    onCommitBrightness = { value ->
                                        adjustingBrightness = false
                                        repository.setHueBrightness(device.id, value, selectedTargetId)
                                    },
                                    onPowerClick = { repository.setHuePower(device.id, !hueOn, selectedTargetId) },
                                    onStepBrightness = { next ->
                                        draftBrightness = next
                                        repository.setHueBrightness(device.id, next, selectedTargetId)
                                    },
                                )
                                if (target.supportsColorTemp) {
                                    Card(
                                        Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(20.dp),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                                    ) {
                                        Box(Modifier.padding(16.dp)) {
                                            ColorTemperatureSection(
                                                mirek = draftMirek,
                                                mirekRange = mirekMin..mirekMax,
                                                usable = usable,
                                                compact = false,
                                                scale = 1f,
                                                onSelectPreset = { preset ->
                                                    val clamped = preset.mirek.coerceIn(mirekMin, mirekMax)
                                                    draftMirek = clamped
                                                    repository.setHueColorTemp(device.id, clamped, selectedTargetId)
                                                },
                                                onDraftMirek = {
                                                    adjustingMirek = true
                                                    draftMirek = it
                                                },
                                                onCommitMirek = {
                                                    adjustingMirek = false
                                                    repository.setHueColorTemp(device.id, draftMirek, selectedTargetId)
                                                },
                                            )
                                        }
                                    }
                                }
                                if (target.supportsColor) {
                                    Card(
                                        Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(20.dp),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                                    ) {
                                        Box(Modifier.padding(16.dp)) {
                                            ColorPresetsSection(
                                                currentXy = target.xy,
                                                usable = usable,
                                                compact = false,
                                                scale = 1f,
                                                onSelectColor = { preset ->
                                                    repository.setHueColorXy(device.id, preset.x, preset.y, selectedTargetId)
                                                },
                                            )
                                        }
                                    }
                                }
                            }

                            state.hue.error?.let { err ->
                                Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }

                            if (relaySwitchId != null && entiaSwitch != null) {
                                MainsRelayCard(
                                    mainsOn = mainsOn,
                                    usable = usable,
                                    compact = false,
                                    scale = 1f,
                                    onToggleMains = {
                                        repository.command(relaySwitchId, Attr.LIGHT, if (mainsOn) 0 else 1)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun CompactUnlinkedCard(onEdit: () -> Unit, scale: Float) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            Modifier.fillMaxWidth().padding((12 * scale).dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                stringResource(R.string.hue_unlinked_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                Text(stringResource(R.string.edit_device))
            }
        }
    }
}

@Composable private fun CompactDimmerControls(
    deviceId: Long,
    target: HueTarget,
    hueOn: Boolean,
    usable: Boolean,
    draftBrightness: Int,
    scale: Float,
    onDraftBrightness: (Int) -> Unit,
    onCommitBrightness: (Int) -> Unit,
    onPowerClick: () -> Unit,
    onStepBrightness: (Int) -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape((18 * scale).dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = (12 * scale).dp, vertical = (10 * scale).dp),
            verticalArrangement = Arrangement.spacedBy((6 * scale).dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy((10 * scale).dp)
            ) {
                FilledTonalButton(
                    onClick = onPowerClick,
                    enabled = usable,
                    modifier = Modifier.weight(1f).height((46 * scale).coerceAtLeast(40f).dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (hueOn) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = if (hueOn) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Icon(Icons.Outlined.PowerSettingsNew, null, Modifier.size((20 * scale).dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(if (hueOn) R.string.hue_turn_off else R.string.hue_turn_on),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = (16 * scale).coerceAtLeast(13f).sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (target.supportsDimming) {
                    Text(
                        stringResource(R.string.percent, draftBrightness),
                        style = TextStyle(
                            fontSize = (28 * scale).coerceAtLeast(20f).sp,
                            fontWeight = FontWeight.Bold,
                            fontFeatureSettings = "tnum"
                        ),
                        color = if (hueOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (target.supportsDimming) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy((6 * scale).dp)
                ) {
                    val btnSize = (42 * scale).coerceAtLeast(38f).dp
                    FilledTonalIconButton(
                        onClick = { onStepBrightness((draftBrightness - 10).coerceIn(1, 100)) },
                        enabled = usable && draftBrightness > 1,
                        modifier = Modifier.size(btnSize)
                    ) {
                        Icon(Icons.Outlined.Remove, stringResource(R.string.decrease), Modifier.size((20 * scale).dp))
                    }
                    val brightnessLabel = stringResource(R.string.hue_brightness)
                    Slider(
                        value = draftBrightness.toFloat(),
                        onValueChange = { onDraftBrightness(it.roundToInt().coerceIn(1, 100)) },
                        onValueChangeFinished = { onCommitBrightness(draftBrightness) },
                        valueRange = 1f..100f,
                        enabled = usable,
                        modifier = Modifier.weight(1f).semantics { contentDescription = brightnessLabel }
                    )
                    FilledTonalIconButton(
                        onClick = { onStepBrightness((draftBrightness + 10).coerceIn(1, 100)) },
                        enabled = usable && draftBrightness < 100,
                        modifier = Modifier.size(btnSize)
                    ) {
                        Icon(Icons.Outlined.Add, stringResource(R.string.increase), Modifier.size((20 * scale).dp))
                    }
                }
            }
        }
    }
}

@Composable private fun PhoneDimmerCard(
    target: HueTarget,
    hueOn: Boolean,
    usable: Boolean,
    draftBrightness: Int,
    onDraftBrightness: (Int) -> Unit,
    onCommitBrightness: (Int) -> Unit,
    onPowerClick: () -> Unit,
    onStepBrightness: (Int) -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FilledTonalButton(
                    onClick = onPowerClick,
                    enabled = usable,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (hueOn) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = if (hueOn) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Icon(Icons.Outlined.PowerSettingsNew, null, Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(if (hueOn) R.string.hue_turn_off else R.string.hue_turn_on),
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (target.supportsDimming) {
                    Text(
                        stringResource(R.string.percent, draftBrightness),
                        style = TextStyle(
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            fontFeatureSettings = "tnum"
                        ),
                        color = if (hueOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (target.supportsDimming) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalIconButton(
                        onClick = { onStepBrightness((draftBrightness - 10).coerceIn(1, 100)) },
                        enabled = usable && draftBrightness > 1,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Outlined.Remove, stringResource(R.string.decrease))
                    }
                    val brightnessLabel = stringResource(R.string.hue_brightness)
                    Slider(
                        value = draftBrightness.toFloat(),
                        onValueChange = { onDraftBrightness(it.roundToInt().coerceIn(1, 100)) },
                        onValueChangeFinished = { onCommitBrightness(draftBrightness) },
                        valueRange = 1f..100f,
                        enabled = usable,
                        modifier = Modifier.weight(1f).semantics { contentDescription = brightnessLabel }
                    )
                    FilledTonalIconButton(
                        onClick = { onStepBrightness((draftBrightness + 10).coerceIn(1, 100)) },
                        enabled = usable && draftBrightness < 100,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Outlined.Add, stringResource(R.string.increase))
                    }
                }
            }
        }
    }
}

@Composable private fun ColorTemperatureSection(
    mirek: Int,
    mirekRange: IntRange,
    usable: Boolean,
    compact: Boolean,
    scale: Float,
    onSelectPreset: (HueTempPreset) -> Unit,
    onDraftMirek: (Int) -> Unit,
    onCommitMirek: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(if (compact) (6 * scale).dp else 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.hue_color_temperature),
                Modifier.weight(1f),
                style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                stringResource(R.string.hue_kelvin, mirekToKelvin(mirek)),
                style = TextStyle(
                    fontSize = if (compact) (16 * scale).coerceAtLeast(13f).sp else 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFeatureSettings = "tnum"
                ),
                color = MaterialTheme.colorScheme.primary
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy((6 * scale).dp),
            verticalArrangement = Arrangement.spacedBy((4 * scale).dp)
        ) {
            HueTempPreset.entries.forEach { preset ->
                val selected = abs(mirek - preset.mirek) <= 18
                FilterChip(
                    selected = selected,
                    onClick = { onSelectPreset(preset) },
                    enabled = usable,
                    label = {
                        Text(
                            if (compact) tempPresetLabel(preset)
                            else "${tempPresetLabel(preset)} · ${preset.kelvin}K",
                            fontSize = if (compact) (14 * scale).coerceAtLeast(12f).sp else 13.sp,
                            maxLines = 1
                        )
                    }
                )
            }
        }
        Slider(
            value = mirek.toFloat(),
            onValueChange = { onDraftMirek(it.roundToInt().coerceIn(mirekRange)) },
            onValueChangeFinished = onCommitMirek,
            valueRange = mirekRange.first.toFloat()..mirekRange.last.toFloat(),
            enabled = usable,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable private fun ColorPresetsSection(
    currentXy: Pair<Double, Double>?,
    usable: Boolean,
    compact: Boolean,
    scale: Float,
    onSelectColor: (HueColorPreset) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(if (compact) (8 * scale).dp else 10.dp)) {
        Text(
            stringResource(R.string.hue_color_presets),
            style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium
        )
        HueColorPreset.entries.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy((8 * scale).dp)) {
                row.forEach { preset ->
                    val selected = currentXy != null &&
                        abs(currentXy.first - preset.x) < 0.03 &&
                        abs(currentXy.second - preset.y) < 0.03
                    FilledTonalButton(
                        onClick = { onSelectColor(preset) },
                        enabled = usable,
                        modifier = Modifier.weight(1f).height(if (compact) (48 * scale).coerceAtLeast(44f).dp else 48.dp),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = (10 * scale).dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Box(
                            Modifier
                                .size(if (compact) (14 * scale).coerceAtLeast(12f).dp else 16.dp)
                                .clip(CircleShape)
                                .background(Color(preset.argb))
                                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), CircleShape)
                        )
                        Spacer(Modifier.width((8 * scale).dp))
                        Text(
                            colorPresetLabel(preset),
                            fontSize = if (compact) (15 * scale).coerceAtLeast(12f).sp else 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable private fun MainsRelayCard(
    mainsOn: Boolean,
    usable: Boolean,
    compact: Boolean,
    scale: Float,
    onToggleMains: () -> Unit,
) {
    val container = if (mainsOn) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(if (compact) (16 * scale).dp else 20.dp),
        colors = CardDefaults.cardColors(containerColor = container)
    ) {
        if (compact) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = (10 * scale).dp, vertical = (8 * scale).dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy((8 * scale).dp)
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(R.string.hue_mains_relay_title),
                        fontSize = (14 * scale).coerceAtLeast(12f).sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        stringResource(if (mainsOn) R.string.hue_mains_powered else R.string.hue_mains_cut),
                        fontSize = (13 * scale).coerceAtLeast(11f).sp,
                        color = if (mainsOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (mainsOn) {
                    OutlinedButton(
                        onClick = onToggleMains,
                        enabled = usable,
                        modifier = Modifier.heightIn(min = (44 * scale).coerceAtLeast(40f).dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            stringResource(R.string.hue_mains_cut_action),
                            fontSize = (14 * scale).coerceAtLeast(11.5f).sp,
                            maxLines = 1
                        )
                    }
                } else {
                    Button(
                        onClick = onToggleMains,
                        enabled = usable,
                        modifier = Modifier.heightIn(min = (44 * scale).coerceAtLeast(40f).dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            stringResource(R.string.hue_mains_restore_action),
                            fontSize = (14 * scale).coerceAtLeast(11.5f).sp,
                            maxLines = 1
                        )
                    }
                }
            }
        } else {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.hue_mains_relay_title),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        stringResource(if (mainsOn) R.string.hue_mains_powered else R.string.hue_mains_cut),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (mainsOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
                Text(
                    stringResource(R.string.hue_mains_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (mainsOn) {
                    OutlinedButton(
                        onClick = onToggleMains,
                        enabled = usable,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) {
                        Text(stringResource(R.string.hue_mains_cut_action))
                    }
                } else {
                    Button(
                        onClick = onToggleMains,
                        enabled = usable,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) {
                        Text(stringResource(R.string.hue_mains_restore_action))
                    }
                }
            }
        }
    }
}

@Composable internal fun HueBridgeSettingsCard(
    state: AppState,
    repository: EntiaRepository,
    compact: Boolean = false,
) {
    val hue = state.hue
    var bridgeIpInput by rememberSaveable { mutableStateOf(hue.bridgeIp.orEmpty()) }
    LaunchedEffect(hue.bridgeIp) {
        if (!hue.bridgeIp.isNullOrBlank() && bridgeIpInput.isBlank()) {
            bridgeIpInput = hue.bridgeIp
        }
    }
    var searchedOnce by remember { mutableStateOf(false) }
    var showAddStandalone by remember { mutableStateOf(false) }
    var editingStandaloneId by remember { mutableStateOf<Long?>(null) }
    val hasRooms = remember(state.home.rooms) { state.home.rooms.any { it.id != Long.MIN_VALUE } }
    val standaloneLights = state.preferences.standaloneHueLights
    val keyboard = LocalSoftwareKeyboardController.current

    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(if (compact) 18.dp else 20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(if (compact) 12.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)
        ) {
            Text(stringResource(R.string.hue_bridge), style = MaterialTheme.typography.titleMedium)

            if (hue.paired) {
                val lightsCount = hue.targets.count { it.type == "light" }
                val groupsCount = hue.targets.count { it.type == "grouped_light" }
                val statusLabel = if (state.demo) {
                    stringResource(R.string.hue_demo_bridge, hue.bridgeIp ?: "192.168.1.42")
                } else {
                    stringResource(R.string.hue_connected_summary, hue.bridgeIp.orEmpty())
                }
                Text(
                    statusLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    stringResource(R.string.hue_targets_summary, lightsCount, groupsCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = repository::refreshHue,
                        enabled = !hue.syncing,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) {
                        Text(
                            stringResource(R.string.hue_refresh),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    OutlinedButton(
                        onClick = repository::disconnectHue,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) {
                        Text(
                            stringResource(R.string.hue_disconnect),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            } else {
                OutlinedButton(
                    onClick = {
                        searchedOnce = true
                        repository.discoverHueBridges()
                    },
                    enabled = !hue.discovering && !hue.pairing,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    if (hue.discovering) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    } else {
                        Icon(Icons.Outlined.Router, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(if (hue.discovering) R.string.hue_discovering else R.string.hue_discover))
                }

                if (hue.discoveredBridges.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        hue.discoveredBridges.forEach { bridge ->
                            FilterChip(
                                selected = bridgeIpInput.trim() == bridge.ip,
                                onClick = { bridgeIpInput = bridge.ip },
                                label = { Text(bridge.ip) }
                            )
                        }
                    }
                } else if (searchedOnce && !hue.discovering) {
                    Text(
                        stringResource(R.string.hue_no_bridges_found),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                OutlinedTextField(
                    value = bridgeIpInput,
                    onValueChange = { bridgeIpInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !hue.pairing,
                    singleLine = true,
                    label = { Text(stringResource(R.string.hue_bridge_ip)) },
                    placeholder = { Text("192.168.1.50") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = {
                        keyboard?.hide()
                        if (bridgeIpInput.isNotBlank()) repository.pairHueBridge(bridgeIpInput)
                    })
                )

                if (hue.pairing) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.hue_pairing_prompt, hue.pairingSecondsLeft),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        OutlinedButton(
                            onClick = repository::cancelHuePairing,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                } else {
                    Button(
                        onClick = {
                            keyboard?.hide()
                            repository.pairHueBridge(bridgeIpInput)
                        },
                        enabled = bridgeIpInput.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) {
                        Text(stringResource(R.string.hue_pair_bridge))
                    }
                }
            }

            if (state.preferences.hueEnabled && hasRooms) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 2.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                Text(
                    stringResource(R.string.hue_standalone_lights_header),
                    style = MaterialTheme.typography.titleSmall
                )
                if (standaloneLights.isEmpty()) {
                    Text(
                        stringResource(R.string.hue_standalone_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        standaloneLights.forEach { light ->
                            val roomName = state.home.rooms.firstOrNull { it.id == light.roomId }?.name
                            val switchName = light.entiaDeviceId?.let { state.home.devices[it]?.name }
                            val targetSummary = if (light.targets.isNotEmpty()) {
                                stringResource(R.string.hue_targets_selected, light.targets.size)
                            } else {
                                null
                            }
                            val subtitle = listOfNotNull(roomName, switchName, targetSummary).joinToString(" · ")
                            Surface(
                                onClick = { editingStandaloneId = light.id },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        lightGlyph(light.icon),
                                        null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            light.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (subtitle.isNotBlank()) {
                                            Text(
                                                subtitle,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = { repository.hideDevice(light.id, true) },
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Icon(
                                            Icons.Outlined.DeleteOutline,
                                            contentDescription = stringResource(R.string.remove_device_confirm),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                OutlinedButton(
                    onClick = { showAddStandalone = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.hue_add_standalone),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            hue.error?.let { err ->
                Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (showAddStandalone) {
        AddStandaloneHueLightDialog(
            state = state,
            repository = repository,
            initialRoomId = state.selectedRoom,
            onDismiss = { showAddStandalone = false },
        )
    }
    editingStandaloneId?.let { id ->
        state.home.devices[id]?.let { liveDevice ->
            DeviceEditor(
                device = liveDevice,
                repository = repository,
                onDismiss = { editingStandaloneId = null },
            )
        }
    }
}

@Composable internal fun AddStandaloneHueLightDialog(
    state: AppState,
    repository: EntiaRepository,
    initialRoomId: Long? = null,
    onDismiss: () -> Unit,
) {
    val rooms = remember(state.home.rooms) { state.home.rooms.filter { it.id != Long.MIN_VALUE } }
    var selectedRoomId by remember(rooms, initialRoomId) {
        mutableStateOf(
            initialRoomId?.takeIf { id -> rooms.any { it.id == id } } ?: rooms.firstOrNull()?.id
        )
    }
    var binding by remember { mutableStateOf(HueBinding()) }
    var selectedEntiaId by remember { mutableStateOf<Long?>(null) }
    var name by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf(LightIcon.Bulb) }
    val entiaSwitches = remember(state.home, selectedRoomId) {
        entiaSwitchOptions(state.home, selectedRoomId)
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val window = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current.density
    val compactWindow = window.height / density <= 480f || window.width / density < 360f

    val selectedRoomName = rooms.firstOrNull { it.id == selectedRoomId }?.name.orEmpty()
    val defaultName = binding.targets.joinToString(", ") { it.name.ifBlank { it.id } }.take(80)
    val fallbackName = stringResource(R.string.text_hue_standalone_default)
    val canSave = selectedRoomId != null && (binding.targets.isNotEmpty() || name.isNotBlank())
    val saveLight = {
        val targetRoomId = selectedRoomId
        if (targetRoomId != null && canSave) {
            keyboard?.hide()
            val finalName = name.trim().ifBlank { defaultName }.ifBlank { fallbackName }
            repository.addStandaloneHueLight(targetRoomId, finalName, icon, binding.targets, selectedEntiaId)
            onDismiss()
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
                if (compactWindow) {
                    var section by remember { mutableStateOf("menu") }
                    Column(Modifier.fillMaxSize()) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            FilledTonalIconButton(
                                onClick = {
                                    keyboard?.hide()
                                    if (section == "menu") onDismiss() else section = "menu"
                                },
                                modifier = Modifier.width(76.dp).height(56.dp),
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back), Modifier.size(30.dp))
                            }
                            Text(
                                stringResource(R.string.hue_add_standalone_title),
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Button(
                                onClick = saveLight,
                                enabled = canSave,
                                modifier = Modifier.height(56.dp),
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                Text(stringResource(R.string.save), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                        when (section) {
                            "room" -> PanelMenu(
                                items = rooms.map { it.id.toString() to it.name },
                                onChoose = { key ->
                                    selectedRoomId = key.toLongOrNull()
                                    section = "menu"
                                },
                                modifier = Modifier.weight(1f),
                                selected = selectedRoomId?.toString(),
                            )
                            "hue" -> Column(
                                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        stringResource(R.string.hue_target),
                                        Modifier.weight(1f),
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (state.hue.paired && !state.demo) {
                                        IconButton(onClick = repository::refreshHue, enabled = !state.hue.syncing) {
                                            Icon(Icons.Outlined.Refresh, stringResource(R.string.hue_refresh))
                                        }
                                    }
                                    TextButton(onClick = { section = "menu" }) {
                                        Text(stringResource(R.string.apply))
                                    }
                                }
                                if (state.hue.targets.isNotEmpty()) {
                                    val entries = state.hue.targets.map { t ->
                                        "${t.type}:${t.id}" to "${t.name} (${hueKindLabel(t.kind)})"
                                    }
                                    val selectedIcons = state.hue.targets
                                        .filter { binding.hasTarget(it.id, it.type) }
                                        .associate { "${it.type}:${it.id}" to Icons.Outlined.Check }
                                    PanelMenu(
                                        items = entries,
                                        onChoose = { key ->
                                            val chosen = state.hue.targets.firstOrNull { "${it.type}:${it.id}" == key }
                                            if (chosen != null) {
                                                binding = binding.toggleTarget(chosen.id, chosen.type, chosen.name)
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        selected = if (binding.targets.size == 1) "${binding.targetType}:${binding.targetId}" else null,
                                        icons = selectedIcons,
                                    )
                                } else {
                                    Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                                        Text(
                                            stringResource(if (state.hue.paired) R.string.hue_no_targets else R.string.hue_pair_hint),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                            "name" -> Column(
                                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
                                verticalArrangement = Arrangement.Center
                            ) {
                                OutlinedTextField(
                                    value = name,
                                    onValueChange = { name = it.take(80) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = { Text(stringResource(R.string.device_name)) },
                                    placeholder = { Text(defaultName.ifBlank { fallbackName }) },
                                    supportingText = { Text(stringResource(R.string.hue_standalone_name_hint)) },
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = {
                                        keyboard?.hide()
                                        section = "menu"
                                    })
                                )
                            }
                            "icons" -> PanelMenu(
                                items = LightIcon.entries.map { it.name to stringResource(iconLabel(it)) },
                                onChoose = { key ->
                                    icon = LightIcon.valueOf(key)
                                    section = "menu"
                                },
                                modifier = Modifier.weight(1f),
                                selected = icon.name,
                                icons = LightIcon.entries.associate { it.name to lightGlyph(it) },
                            )
                            "switch" -> PanelMenu(
                                items = entiaSwitchPanelItems(stringResource(R.string.hue_physical_switch_none), entiaSwitches),
                                onChoose = { key ->
                                    selectedEntiaId = key.toLongOrNull()
                                    section = "menu"
                                },
                                modifier = Modifier.weight(1f),
                                selected = selectedEntiaId?.toString() ?: "none",
                            )
                            else -> {
                                val roomSummary = selectedRoomName.ifBlank { stringResource(R.string.unknown) }
                                val targetsSummary = if (binding.targets.isEmpty()) stringResource(R.string.hue_none_selected)
                                else stringResource(R.string.hue_targets_selected, binding.targets.size)
                                val switchSummary = entiaSwitches.firstOrNull { it.id == selectedEntiaId }?.label
                                    ?: stringResource(R.string.hue_physical_switch_none)
                                val nameSummary = name.trim().ifBlank { defaultName }.ifBlank { fallbackName }
                                val iconSummary = stringResource(iconLabel(icon))
                                PanelMenu(
                                    items = buildList {
                                        add("room" to "${stringResource(R.string.hue_standalone_room)}: $roomSummary")
                                        add("hue" to "${stringResource(R.string.hue_targets_menu)}: $targetsSummary")
                                        if (entiaSwitches.isNotEmpty()) {
                                            add("switch" to "${stringResource(R.string.hue_physical_switch)}: $switchSummary")
                                        }
                                        add("name" to "${stringResource(R.string.device_name)}: $nameSummary")
                                        add("icons" to "${stringResource(R.string.light_icon)}: $iconSummary")
                                    },
                                    onChoose = { section = it },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                } else {
                    Column(
                        Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TextButton(onDismiss, Modifier.heightIn(min = 48.dp)) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.back))
                        }
                        Text(stringResource(R.string.hue_add_standalone_title), style = MaterialTheme.typography.titleLarge)
                        Text(
                            stringResource(R.string.hue_add_standalone_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(stringResource(R.string.hue_standalone_room), style = MaterialTheme.typography.titleMedium)
                        if (rooms.isEmpty()) {
                            Text(
                                stringResource(R.string.no_rooms),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                rooms.forEach { room ->
                                    val selected = selectedRoomId == room.id
                                    FilterChip(
                                        selected = selected,
                                        onClick = { selectedRoomId = room.id },
                                        label = { Text(room.name) },
                                        leadingIcon = if (selected) ({ Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)) }) else null,
                                        modifier = Modifier.heightIn(min = 44.dp)
                                    )
                                }
                            }
                        }

                        Card(
                            Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        stringResource(R.string.hue_target),
                                        Modifier.weight(1f),
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                    if (state.hue.paired && !state.demo) {
                                        IconButton(onClick = repository::refreshHue, enabled = !state.hue.syncing) {
                                            Icon(Icons.Outlined.Refresh, stringResource(R.string.hue_refresh))
                                        }
                                    }
                                }
                                if (state.hue.targets.isNotEmpty()) {
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        state.hue.targets.forEach { target ->
                                            val selected = binding.hasTarget(target.id, target.type)
                                            FilterChip(
                                                selected = selected,
                                                onClick = { binding = binding.toggleTarget(target.id, target.type, target.name) },
                                                label = { Text("${target.name} (${hueKindLabel(target.kind)})") },
                                                leadingIcon = if (selected) ({ Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)) }) else null,
                                                modifier = Modifier.heightIn(min = 44.dp)
                                            )
                                        }
                                    }
                                } else {
                                    Text(
                                        stringResource(if (state.hue.paired) R.string.hue_no_targets else R.string.hue_pair_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        if (entiaSwitches.isNotEmpty()) {
                            Card(
                                Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(18.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                            ) {
                                Box(Modifier.fillMaxWidth().padding(14.dp)) {
                                    PhysicalEntiaSwitchChips(
                                        options = entiaSwitches,
                                        selectedId = selectedEntiaId,
                                        onSelect = { selectedEntiaId = it },
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it.take(80) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(stringResource(R.string.device_name)) },
                            placeholder = { Text(defaultName.ifBlank { fallbackName }) },
                            supportingText = { Text(stringResource(R.string.hue_standalone_name_hint)) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { keyboard?.hide() })
                        )

                        Text(stringResource(R.string.light_icon), style = MaterialTheme.typography.titleMedium)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            LightIcon.entries.forEach { choice ->
                                FilterChip(
                                    selected = icon == choice,
                                    onClick = { icon = choice },
                                    label = { Text(stringResource(iconLabel(choice))) },
                                    leadingIcon = { Icon(lightGlyph(choice), null, Modifier.size(20.dp)) },
                                    modifier = Modifier.heightIn(min = 48.dp)
                                )
                            }
                        }

                        Button(
                            onClick = saveLight,
                            enabled = canSave,
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

@Composable internal fun PanelHueSettings(
    state: AppState,
    repository: EntiaRepository,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.philips_hue), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.philips_hue_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(
                    checked = state.preferences.hueEnabled,
                    onCheckedChange = repository::setHueEnabled
                )
            }
        }
        if (state.preferences.hueEnabled) {
            HueBridgeSettingsCard(state = state, repository = repository, compact = true)
        }
    }
}
