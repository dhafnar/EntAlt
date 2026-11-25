package si.hafnar.entalt.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import si.hafnar.entalt.R
import si.hafnar.entalt.data.EntiaRepository
import si.hafnar.entalt.data.HueBinding
import si.hafnar.entalt.domain.Device
import si.hafnar.entalt.domain.LightIcon

private val TableLamp = ImageVector.Builder("Table lamp", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(8f, 3f); lineTo(16f, 3f); lineTo(20f, 12f); lineTo(4f, 12f); close()
        moveTo(11f, 12f); lineTo(13f, 12f); lineTo(13f, 20f); lineTo(11f, 20f); close()
        moveTo(7f, 20f); lineTo(17f, 20f); lineTo(17f, 22f); lineTo(7f, 22f); close()
    }
}.build()

private val FloorLamp = ImageVector.Builder("Floor lamp", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(8f, 2f); lineTo(16f, 2f); lineTo(18f, 8f); lineTo(6f, 8f); close()
        moveTo(11f, 8f); lineTo(13f, 8f); lineTo(13f, 20f); lineTo(11f, 20f); close()
        moveTo(7f, 20f); lineTo(17f, 20f); lineTo(17f, 22f); lineTo(7f, 22f); close()
    }
}.build()

private val WallLamp = ImageVector.Builder("Wall lamp", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(3f, 6f); lineTo(5f, 6f); lineTo(5f, 18f); lineTo(3f, 18f); close()
        moveTo(5f, 13f); lineTo(14f, 13f); lineTo(14f, 15f); lineTo(5f, 15f); close()
        moveTo(12f, 11f); lineTo(14f, 11f); lineTo(14f, 13f); lineTo(12f, 13f); close()
        moveTo(10f, 4f); lineTo(16f, 4f); lineTo(19f, 11f); lineTo(7f, 11f); close()
    }
}.build()

private val Chandelier = ImageVector.Builder("Chandelier", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(9f, 2f); lineTo(15f, 2f); lineTo(15f, 4f); lineTo(9f, 4f); close()
        moveTo(11f, 4f); lineTo(13f, 4f); lineTo(13f, 18f); lineTo(11f, 18f); close()
        moveTo(4f, 13f); lineTo(20f, 13f); lineTo(20f, 15f); lineTo(4f, 15f); close()
        moveTo(3f, 8f); lineTo(8f, 8f); lineTo(7f, 13f); lineTo(4f, 13f); close()
        moveTo(16f, 8f); lineTo(21f, 8f); lineTo(20f, 13f); lineTo(17f, 13f); close()
        moveTo(10f, 18f); lineTo(14f, 18f); lineTo(12f, 22f); close()
    }
}.build()

internal fun lightGlyph(icon: LightIcon): ImageVector = when (icon) {
    LightIcon.Bulb -> Icons.Outlined.Lightbulb
    LightIcon.Ceiling -> Icons.Outlined.WbIncandescent
    LightIcon.Pendant -> Icons.Outlined.Light
    LightIcon.TableLamp -> TableLamp
    LightIcon.FloorLamp -> FloorLamp
    LightIcon.WallLamp -> WallLamp
    LightIcon.Chandelier -> Chandelier
    LightIcon.Spotlight -> Icons.Outlined.Highlight
    LightIcon.Strip -> Icons.Outlined.Fluorescent
    LightIcon.Downlight -> Icons.Outlined.Tungsten
    LightIcon.Nightlight -> Icons.Outlined.Nightlight
    LightIcon.Outdoor -> Icons.Outlined.LightMode
}

internal fun iconLabel(icon: LightIcon) = when (icon) {
    LightIcon.Bulb -> R.string.icon_bulb
    LightIcon.Ceiling -> R.string.icon_ceiling
    LightIcon.Pendant -> R.string.icon_pendant
    LightIcon.TableLamp -> R.string.icon_table_lamp
    LightIcon.FloorLamp -> R.string.icon_floor_lamp
    LightIcon.WallLamp -> R.string.icon_wall_lamp
    LightIcon.Chandelier -> R.string.icon_chandelier
    LightIcon.Spotlight -> R.string.icon_spotlight
    LightIcon.Strip -> R.string.icon_strip
    LightIcon.Downlight -> R.string.icon_downlight
    LightIcon.Nightlight -> R.string.icon_nightlight
    LightIcon.Outdoor -> R.string.icon_outdoor
}

@Composable internal fun DeviceEditor(device: Device, repository: EntiaRepository, onDismiss: () -> Unit) {
    val appState by repository.state.collectAsState()
    val showHueOption = appState.preferences.hueEnabled && device.isLight
    val initialStandalone = appState.preferences.standaloneHueLights.firstOrNull { it.id == device.id }
    val initialBinding = appState.preferences.hueBindingFor(device.id)
    var name by remember(device.id) { mutableStateOf(device.name) }
    var icon by remember(device.id) { mutableStateOf(device.lightIcon) }
    var hueManaged by remember(device.id) { mutableStateOf(device.isStandaloneHue || device.id in appState.preferences.hueBindings) }
    var hueBinding by remember(device.id) { mutableStateOf(initialBinding ?: HueBinding()) }
    var entiaDeviceId by remember(device.id) { mutableStateOf(initialStandalone?.entiaDeviceId) }
    var confirmRemove by remember(device.id) { mutableStateOf(false) }
    val entiaSwitches = remember(appState.home, device.roomId, entiaDeviceId) {
        if (device.isStandaloneHue) entiaSwitchOptions(appState.home, device.roomId, includeId = entiaDeviceId)
        else emptyList()
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val window = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current.density
    val compactWindow = window.height / density <= 480f || window.width / density < 360f

    val saveChanges = {
        keyboard?.hide()
        repository.customizeDevice(device.id, name, icon)
        if (showHueOption) {
            if (device.isStandaloneHue) {
                repository.setStandaloneHueLight(device.id, hueBinding, entiaDeviceId)
            } else {
                repository.setHueBinding(device.id, if (hueManaged) hueBinding else null)
            }
        }
        onDismiss()
    }

    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
                if (compactWindow) {
                    var section by remember { mutableStateOf("menu") }
                    if (confirmRemove) {
                        Column(
                            Modifier.fillMaxSize().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                stringResource(R.string.confirm_remove_device_title, device.name),
                                style = MaterialTheme.typography.titleLarge,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                stringResource(
                                    if (device.isStandaloneHue) R.string.confirm_remove_standalone_message
                                    else R.string.confirm_remove_device_message,
                                    device.name
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(
                                    onClick = { confirmRemove = false },
                                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Text(stringResource(R.string.cancel))
                                }
                                Button(
                                    onClick = {
                                        repository.hideDevice(device.id, true)
                                        onDismiss()
                                    },
                                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Text(stringResource(R.string.remove_device_confirm))
                                }
                            }
                        }
                        return@Box
                    }
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
                                stringResource(R.string.edit_device),
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.titleLarge
                            )
                            Button(
                                saveChanges,
                                modifier = Modifier.height(56.dp),
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                Text(stringResource(R.string.save), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                        when (section) {
                            "name" -> Column(
                                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
                                verticalArrangement = Arrangement.Center
                            ) {
                                OutlinedTextField(
                                    name,
                                    { name = it.take(80) },
                                    Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = { Text(stringResource(R.string.device_name)) },
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = {
                                        keyboard?.hide()
                                        section = "menu"
                                    })
                                )
                            }
                            "icons" -> PanelMenu(
                                LightIcon.entries.map { it.name to stringResource(iconLabel(it)) },
                                {
                                    icon = LightIcon.valueOf(it)
                                    section = "menu"
                                },
                                Modifier.weight(1f),
                                selected = icon.name,
                                icons = LightIcon.entries.associate { it.name to lightGlyph(it) }
                            )
                            "hue" -> Column(
                                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (!device.isStandaloneHue) {
                                    Card(
                                        Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(16.dp),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                                    ) {
                                        Row(
                                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                stringResource(R.string.managed_by_hue),
                                                Modifier.weight(1f),
                                                style = MaterialTheme.typography.titleSmall,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (appState.hue.paired && !appState.demo) {
                                                IconButton(onClick = repository::refreshHue, enabled = !appState.hue.syncing) {
                                                    Icon(Icons.Outlined.Refresh, stringResource(R.string.hue_refresh))
                                                }
                                            }
                                            Switch(checked = hueManaged, onCheckedChange = { hueManaged = it })
                                        }
                                    }
                                } else {
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
                                        if (appState.hue.paired && !appState.demo) {
                                            IconButton(onClick = repository::refreshHue, enabled = !appState.hue.syncing) {
                                                Icon(Icons.Outlined.Refresh, stringResource(R.string.hue_refresh))
                                            }
                                        }
                                    }
                                }
                                if (device.isStandaloneHue || hueManaged) {
                                    if (appState.hue.targets.isNotEmpty()) {
                                        val entries = appState.hue.targets.map { t ->
                                            "${t.type}:${t.id}" to "${t.name} (${hueKindLabel(t.kind)})"
                                        }
                                        val selectedIcons = appState.hue.targets
                                            .filter { hueBinding.hasTarget(it.id, it.type) }
                                            .associate { "${it.type}:${it.id}" to Icons.Outlined.Check }
                                        PanelMenu(
                                            items = entries,
                                            onChoose = { key ->
                                                val chosen = appState.hue.targets.firstOrNull { "${it.type}:${it.id}" == key }
                                                if (chosen != null) {
                                                    hueBinding = hueBinding.toggleTarget(chosen.id, chosen.type, chosen.name)
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
                                            selected = if (hueBinding.targets.size == 1) "${hueBinding.targetType}:${hueBinding.targetId}" else null,
                                            icons = selectedIcons
                                        )
                                    } else {
                                        Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                                            Text(
                                                stringResource(if (appState.hue.paired) R.string.hue_no_targets else R.string.hue_pair_hint),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                }
                            }
                            "switch" -> PanelMenu(
                                items = entiaSwitchPanelItems(stringResource(R.string.hue_physical_switch_none), entiaSwitches),
                                onChoose = { key ->
                                    entiaDeviceId = key.toLongOrNull()
                                    section = "menu"
                                },
                                modifier = Modifier.weight(1f),
                                selected = entiaDeviceId?.toString() ?: "none",
                            )
                            else -> PanelMenu(
                                buildList {
                                    add("name" to stringResource(R.string.device_name))
                                    if (device.isLight) add("icons" to stringResource(R.string.light_icon))
                                    if (showHueOption) {
                                        add("hue" to stringResource(if (device.isStandaloneHue) R.string.hue_targets_menu else R.string.managed_by_hue))
                                    }
                                    if (device.isStandaloneHue && showHueOption && entiaSwitches.isNotEmpty()) {
                                        val swLabel = entiaSwitches.firstOrNull { it.id == entiaDeviceId }?.label
                                            ?: stringResource(R.string.hue_physical_switch_none)
                                        add("switch" to "${stringResource(R.string.hue_physical_switch)}: $swLabel")
                                    }
                                    if (!device.isStandaloneHue) add("original" to stringResource(R.string.original_name))
                                    add("remove" to stringResource(R.string.remove_local_device))
                                },
                                {
                                    when (it) {
                                        "original" -> name = device.serverName
                                        "remove" -> confirmRemove = true
                                        else -> section = it
                                    }
                                },
                                Modifier.weight(1f)
                            )
                        }
                    }
                    return@Box
                }
                Column(
                    Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TextButton(onDismiss, Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.back))
                    }
                    Text(stringResource(R.string.edit_device), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.local_device_changes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        name,
                        { name = it.take(80) },
                        Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.device_name)) },
                        placeholder = { Text(device.serverName) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { keyboard?.hide() })
                    )
                    if (!device.isStandaloneHue) {
                        TextButton({ name = device.serverName }, Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.original_name))
                        }
                    }
                    if (device.isLight) {
                        Text(stringResource(R.string.light_icon), style = MaterialTheme.typography.titleMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            LightIcon.entries.forEach { choice ->
                                FilterChip(
                                    icon == choice,
                                    { icon = choice },
                                    label = { Text(stringResource(iconLabel(choice))) },
                                    leadingIcon = { Icon(lightGlyph(choice), null, Modifier.size(20.dp)) },
                                    modifier = Modifier.heightIn(min = 48.dp)
                                )
                            }
                        }
                    }
                    if (showHueOption) {
                        Card(
                            Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (!device.isStandaloneHue) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(stringResource(R.string.managed_by_hue), style = MaterialTheme.typography.titleMedium)
                                            Text(
                                                stringResource(R.string.managed_by_hue_description),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Switch(checked = hueManaged, onCheckedChange = { hueManaged = it })
                                    }
                                }
                                if (device.isStandaloneHue || hueManaged) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            stringResource(R.string.hue_target),
                                            Modifier.weight(1f),
                                            style = MaterialTheme.typography.labelLarge
                                        )
                                        if (appState.hue.paired && !appState.demo) {
                                            IconButton(onClick = repository::refreshHue, enabled = !appState.hue.syncing) {
                                                Icon(Icons.Outlined.Refresh, stringResource(R.string.hue_refresh))
                                            }
                                        }
                                    }
                                    if (appState.hue.targets.isNotEmpty()) {
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            appState.hue.targets.forEach { target ->
                                                val selected = hueBinding.hasTarget(target.id, target.type)
                                                FilterChip(
                                                    selected = selected,
                                                    onClick = { hueBinding = hueBinding.toggleTarget(target.id, target.type, target.name) },
                                                    label = { Text("${target.name} (${hueKindLabel(target.kind)})") },
                                                    leadingIcon = if (selected) ({ Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)) }) else null,
                                                    modifier = Modifier.heightIn(min = 44.dp)
                                                )
                                            }
                                        }
                                    } else {
                                        Text(
                                            stringResource(if (appState.hue.paired) R.string.hue_no_targets else R.string.hue_pair_hint),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (device.isStandaloneHue && entiaSwitches.isNotEmpty()) {
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                        PhysicalEntiaSwitchChips(
                                            options = entiaSwitches,
                                            selectedId = entiaDeviceId,
                                            onSelect = { entiaDeviceId = it },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Button(
                        saveChanges,
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) {
                        Text(stringResource(R.string.save))
                    }
                    OutlinedButton(
                        {
                            keyboard?.hide()
                            confirmRemove = true
                        },
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) {
                        Text(stringResource(R.string.remove_local_device))
                    }
                    if (!device.isStandaloneHue) {
                        Text(
                            stringResource(R.string.restore_hidden_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
    if (!compactWindow && confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.confirm_remove_device_title, device.name)) },
            text = {
                Text(
                    stringResource(
                        if (device.isStandaloneHue) R.string.confirm_remove_standalone_message
                        else R.string.confirm_remove_device_message,
                        device.name
                    )
                )
            },
            confirmButton = {
                Button(onClick = {
                    repository.hideDevice(device.id, true)
                    confirmRemove = false
                    onDismiss()
                }) {
                    Text(stringResource(R.string.remove_device_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
