package si.hafnar.entalt.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import si.hafnar.entalt.R
import si.hafnar.entalt.data.*
import si.hafnar.entalt.domain.*

/** Panel controls use the available rectangle, rather than a scrolling phone card. */
@Composable internal fun PanelDeviceControl(control: Control, state: AppState, repository: EntiaRepository,
    modifier: Modifier = Modifier) {
    val device = control.device
    val linkedEntiaId = if (state.preferences.hueEnabled && control.kind == Attr.LIGHT) {
        state.preferences.linkedEntiaSwitchId(device.id)
    } else null
    val linkedSwitch = linkedEntiaId?.let { state.home.devices[it] }
    val command = state.commands[device.id] ?: linkedEntiaId?.let { state.commands[it] }
    val enabled = device.available && (linkedSwitch?.available ?: true) && state.foreground && !state.needsSignIn && command?.pending != true
    var editor by remember(device.id) { mutableStateOf(false) }
    var showHueWindow by remember(device.id) { mutableStateOf(false) }
    val editLabel = stringResource(R.string.edit_device)
    val powerLabel = stringResource(R.string.device_power, device.name)
    Column(modifier.fillMaxSize().combinedClickable(onClick = {}, onLongClick = { editor = true }, onLongClickLabel = editLabel)
        .padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when (control.kind) {
                Attr.TARGET -> PanelClimate(device, state, repository, enabled)
                Attr.HRV_SPEED -> PanelVentilation(device, state, repository, enabled)
                Attr.POSITION -> PanelBlind(device, state, repository, enabled)
                Attr.LIGHT -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val resolved = resolveLightState(state.home, state.preferences, state.hue, device)
                    val binding = resolved.binding
                    val hueTarget = resolved.target
                    val mainsOn = resolved.mainsOn
                    val lightOn = resolved.on
                    Icon(
                        controlIcon(control),
                        null,
                        tint = if (lightOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Column(Modifier.weight(1f)) {
                        Text(device.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val subtitle = when {
                            binding != null && !mainsOn -> stringResource(R.string.hue_mains_off)
                            binding != null && hueTarget?.on == true && hueTarget.brightness != null ->
                                "${stringResource(R.string.hue_badge)} · ${stringResource(R.string.percent, hueTarget.brightness)}"
                            binding != null && hueTarget != null && !hueTarget.on ->
                                "${stringResource(R.string.hue_badge)} · ${stringResource(R.string.off)}"
                            binding != null -> stringResource(R.string.hue_badge)
                            else -> stringResource(if (lightOn) R.string.on else R.string.off)
                        }
                        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (binding != null) {
                        IconButton(onClick = { showHueWindow = true }) {
                            Icon(Icons.Outlined.Tune, stringResource(R.string.hue_controls, device.name))
                        }
                    }
                    Switch(
                        checked = lightOn,
                        onCheckedChange = {
                            if (binding != null) repository.toggleHueLight(device.id)
                            else if (!device.isStandaloneHue) repository.command(device.id, Attr.LIGHT, if (it) 1 else 0)
                        },
                        enabled = enabled,
                        modifier = Modifier.semantics { contentDescription = powerLabel }
                    )
                }
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(device.temperature(control.kind)?.let { stringResource(R.string.celsius, it) } ?: "—",
                        style = MaterialTheme.typography.displaySmall)
                    Text(controlDisplayName(control, state), textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (command != null) Text(
            stringResource(when (command.phase) {
                CommandPhase.Sending -> R.string.command_sending
                CommandPhase.Awaiting -> R.string.command_awaiting
                CommandPhase.Confirmed -> R.string.command_observed
                CommandPhase.Failed -> R.string.command_failed
                else -> R.string.command_unknown
            }), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (command.phase in setOf(CommandPhase.Failed, CommandPhase.Unconfirmed)) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
    if (editor) DeviceEditor(device, repository) { editor = false }
    if (showHueWindow) {
        HueControlWindow(
            device = device,
            state = state,
            repository = repository,
            onOpenEditor = { editor = true },
            onDismiss = { showHueWindow = false }
        )
    }
}

@Composable private fun PanelClimate(device: Device, state: AppState, repository: EntiaRepository, enabled: Boolean) {
    val target = device.value(Attr.TARGET)?.takeIf { it in 20..60 }
    var editing by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(false) }
    val allModes = listOf("0" to stringResource(R.string.off), "2" to stringResource(R.string.heat), "1" to stringResource(R.string.cool))
    val selectableModes = if (state.preferences.hideCooling) allModes.filter { it.first != "1" } else allModes
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton({ mode = true }, Modifier.weight(1f).fillMaxHeight(), enabled = Attr.MODE in device.attributes,
            contentPadding = PaddingValues(0.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                BoxWithConstraints {
                    Text(device.temperature(Attr.INSIDE)?.let { stringResource(R.string.celsius, it) } ?: "—",
                        style = if (maxWidth < 110.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineLarge,
                        textAlign = TextAlign.Center)
                }
                Text(allModes.firstOrNull { it.first == device.value(Attr.MODE)?.toString() }?.second ?: stringResource(R.string.unknown),
                    style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
                Icon(Icons.Outlined.ExpandMore, stringResource(R.string.climate), Modifier.size(16.dp))
            }
        }
        Row(Modifier.widthIn(min = 144.dp).weight(1.7f), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton({ target?.let { repository.command(device.id, Attr.TARGET, it - 1) } },
                Modifier.size(48.dp), enabled = enabled && target != null && target > 20) {
                Icon(Icons.Outlined.Remove, stringResource(R.string.decrease))
            }
            TextButton({ editing = true }, Modifier.weight(1f).heightIn(min = 48.dp), enabled = enabled && target != null,
                contentPadding = PaddingValues(0.dp)) {
                Text(target?.let { stringResource(R.string.panel_temperature, it / 2.0) } ?: "—",
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge)
            }
            FilledTonalIconButton({ target?.let { repository.command(device.id, Attr.TARGET, it + 1) } },
                Modifier.size(48.dp), enabled = enabled && target != null && target < 60) {
                Icon(Icons.Outlined.Add, stringResource(R.string.increase))
            }
        }
    }
    if (mode) PanelChoiceDialog(stringResource(R.string.climate), selectableModes, device.value(Attr.MODE)?.toString(),
        enabled && device.value(Attr.MODE) in 0..2, { mode = false }) {
        repository.command(device.id, Attr.MODE, it.toInt()); mode = false
    }
    if (editing) ValueEditor(stringResource(R.string.set_temperature), target ?: 40, 20..60, enabled && target != null,
        valueText = { stringResource(R.string.celsius, it / 2.0) }, onDismiss = { editing = false },
        onApply = { repository.command(device.id, Attr.TARGET, it); editing = false })
}

@Composable private fun PanelVentilation(device: Device, state: AppState, repository: EntiaRepository, enabled: Boolean) {
    val speed = device.value(Attr.HRV_SPEED)?.takeIf { it in 0..3 }
    val labels = listOf(stringResource(R.string.off), stringResource(R.string.low), stringResource(R.string.medium), stringResource(R.string.high))
    var mode by remember { mutableStateOf(false) }
    val modes = listOf("0" to stringResource(R.string.bypass), "1" to stringResource(R.string.heat_recovery))
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
        Text(speed?.let { labels[it] } ?: stringResource(R.string.unknown), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            labels.forEachIndexed { value, label ->
                FilledTonalButton({ repository.command(device.id, Attr.HRV_SPEED, value) }, Modifier.weight(1f).heightIn(min = 48.dp)
                    .semantics { contentDescription = label; selected = speed == value },
                    enabled = enabled && speed != null, contentPadding = PaddingValues(2.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = if (speed == value) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Text(if (value == 0) label else value.toString())
                }
            }
        }
        if (Attr.HRV_MODE in device.attributes) TextButton({ mode = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp),
            enabled = enabled && device.value(Attr.HRV_MODE) in 0..1, contentPadding = PaddingValues(4.dp)) {
            Text(modes.firstOrNull { it.first == device.value(Attr.HRV_MODE)?.toString() }?.second ?: stringResource(R.string.unknown),
                textAlign = TextAlign.Center)
            Icon(Icons.Outlined.ExpandMore, stringResource(R.string.ventilation_mode))
        }
    }
    if (mode) PanelChoiceDialog(stringResource(R.string.ventilation_mode), modes, device.value(Attr.HRV_MODE)?.toString(),
        enabled, { mode = false }) { repository.command(device.id, Attr.HRV_MODE, it.toInt()); mode = false }
}

@Composable private fun PanelBlind(device: Device, state: AppState, repository: EntiaRepository, enabled: Boolean) {
    ShadeControlTile(Control(device, Attr.POSITION), state, repository, panel = true)
}
