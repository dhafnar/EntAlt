@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package si.hafnar.entalt.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import si.hafnar.entalt.R
import si.hafnar.entalt.data.*
import si.hafnar.entalt.domain.*

@Composable private fun panelScale(panel: Boolean): Float {
    if (!panel) return 1f
    val window = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current.density
    val minDp = min(window.width, window.height) / density
    return (minDp / 480f).coerceIn(0.66f, 1.15f)
}

@Composable private fun WindowBlindGraphic(
    closedPercent: Int,
    panel: Boolean,
    scale: Float
) {
    val fraction = (closedPercent / 100f).coerceIn(0f, 1f)
    val frameWidth = if (panel) (34 * scale).dp else 36.dp
    val frameHeight = if (panel) (38 * scale).dp else 40.dp
    val sillWidth = if (panel) (40 * scale).dp else 42.dp
    val frameColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
    val glassColor = MaterialTheme.colorScheme.surfaceContainerLowest
    val blindFill = MaterialTheme.colorScheme.secondaryContainer
    val blindAccent = MaterialTheme.colorScheme.secondary

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Box(
            modifier = Modifier
                .width(frameWidth)
                .height(frameHeight)
                .clip(RoundedCornerShape(6.dp))
                .background(glassColor)
                .border(1.5.dp, frameColor, RoundedCornerShape(6.dp))
        ) {
            // Subtle window mullions behind the blind
            Box(
                Modifier
                    .align(Alignment.Center)
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(frameColor.copy(alpha = 0.35f))
            )
            Box(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(frameColor.copy(alpha = 0.25f))
            )
            // Top-down blind inside the window frame
            if (fraction > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(fraction)
                        .background(blindFill)
                ) {
                    val slats = (closedPercent / 18).coerceIn(0, 5)
                    if (slats > 0) {
                        Column(
                            Modifier.fillMaxSize().padding(vertical = 2.dp),
                            verticalArrangement = Arrangement.SpaceEvenly
                        ) {
                            repeat(slats) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(1.dp)
                                        .background(blindAccent.copy(alpha = 0.45f))
                                )
                            }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(2.5.dp)
                            .background(blindAccent)
                    )
                }
            }
        }
        // Subtle window sill
        Box(
            modifier = Modifier
                .width(sillWidth)
                .height(2.dp)
                .background(frameColor, RoundedCornerShape(1.dp))
        )
    }
}

@Composable private fun RepeatStepperButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onStep: () -> Int?,
    onCommit: (Int) -> Unit,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(18.dp),
    iconSize: androidx.compose.ui.unit.Dp = 30.dp
) {
    val scope = rememberCoroutineScope()
    val latestEnabled by rememberUpdatedState(enabled)
    val latestStep by rememberUpdatedState(onStep)
    val latestCommit by rememberUpdatedState(onCommit)
    val interactionSource = remember { MutableInteractionSource() }

    Surface(
        modifier = modifier
            .clip(shape)
            .indication(interactionSource, ripple())
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription
                if (!enabled) disabled()
                onClick(label = contentDescription) {
                    if (!latestEnabled) return@onClick false
                    val next = latestStep()
                    if (next != null) latestCommit(next)
                    true
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!latestEnabled) return@awaitEachGesture
                    down.consume()
                    val press = PressInteraction.Press(down.position)
                    scope.launch { interactionSource.emit(press) }
                    var lastValue: Int? = null
                    var held = false
                    val repeatJob = scope.launch {
                        delay(350L)
                        held = true
                        while (true) {
                            val stepped = latestStep()
                            if (stepped != null) lastValue = stepped
                            delay(70L)
                        }
                    }
                    val up = waitForUpOrCancellation()
                    repeatJob.cancel()
                    scope.launch {
                        interactionSource.emit(
                            if (up != null) PressInteraction.Release(press) else PressInteraction.Cancel(press)
                        )
                    }
                    if (up != null) {
                        up.consume()
                        if (!held) {
                            val stepped = latestStep()
                            if (stepped != null) latestCommit(stepped)
                        } else {
                            lastValue?.let(latestCommit)
                        }
                    }
                }
            },
        shape = shape,
        color = if (enabled) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (enabled) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(iconSize))
        }
    }
}

@Composable private fun VerticalBlindPillSlider(
    closedPercent: Int,
    enabled: Boolean,
    scale: Float,
    description: String,
    onDraftChange: (Int) -> Unit,
    onCommit: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val latestEnabled by rememberUpdatedState(enabled)
    val latestOnDraft by rememberUpdatedState(onDraftChange)
    val latestOnCommit by rememberUpdatedState(onCommit)
    val currentDraft by rememberUpdatedState(closedPercent)
    val fraction = (closedPercent / 100f).coerceIn(0f, 1f)
    val trackShape = RoundedCornerShape((28 * scale).dp)

    BoxWithConstraints(
        modifier = modifier
            .clip(trackShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, trackShape)
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!latestEnabled || size.height <= 0) return@awaitEachGesture
                    down.consume()
                    var draft = ((down.position.y / size.height.toFloat()) * 100f).roundToInt().coerceIn(0, 100)
                    latestOnDraft(draft)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.firstOrNull() ?: break
                        if (change.changedToUpIgnoreConsumed()) {
                            change.consume()
                            latestOnCommit(draft)
                            break
                        } else if (change.pressed) {
                            change.consume()
                            draft = ((change.position.y / size.height.toFloat()) * 100f).roundToInt().coerceIn(0, 100)
                            latestOnDraft(draft)
                        } else {
                            latestOnCommit(currentDraft)
                            break
                        }
                    }
                }
            }
    ) {
        val fillHeight = maxHeight * fraction
        val iconTopLimit = (14 * scale + 24 * scale).dp
        if (fraction > 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(fraction)
                    .background(MaterialTheme.colorScheme.primary)
            ) {
                if (fillHeight >= iconTopLimit + 18.dp) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = (8 * scale).dp)
                            .width((28 * scale).dp)
                            .height(4.dp)
                            .background(
                                MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.55f),
                                RoundedCornerShape(2.dp)
                            )
                    )
                }
            }
        }
        Icon(
            Icons.Outlined.Blinds,
            contentDescription = null,
            tint = if (fillHeight >= iconTopLimit) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = (14 * scale).dp)
                .size((24 * scale).dp)
        )
        Icon(
            Icons.Outlined.BlindsClosed,
            contentDescription = null,
            tint = if (maxHeight - fillHeight <= (14 * scale + 12 * scale).dp) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = (14 * scale).dp)
                .size((24 * scale).dp)
        )
    }
}

@Composable private fun BlindPresetSlot(
    slotNumber: Int,
    savedPercent: Int?,
    currentPercent: Int,
    enabled: Boolean,
    scale: Float,
    onApply: (Int) -> Unit,
    onSaveCurrent: () -> Unit,
    modifier: Modifier = Modifier
) {
    val active = savedPercent != null && savedPercent == currentPercent
    val shape = RoundedCornerShape((16 * scale).dp)
    val containerColor = when {
        active -> MaterialTheme.colorScheme.primaryContainer
        savedPercent != null -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    val contentColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val slotHeight = (56 * scale).coerceAtLeast(48f).dp

    Surface(
        modifier = modifier
            .height(slotHeight)
            .clip(shape)
            .combinedClickable(
                enabled = enabled,
                onClick = {
                    if (savedPercent != null) onApply(savedPercent) else onSaveCurrent()
                },
                onLongClick = onSaveCurrent,
                onLongClickLabel = stringResource(R.string.save)
            ),
        shape = shape,
        color = containerColor,
        contentColor = contentColor
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = (12 * scale).dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy((8 * scale).dp, Alignment.CenterHorizontally)
        ) {
            if (savedPercent != null) {
                Icon(
                    Icons.Outlined.Bookmark,
                    null,
                    Modifier.size((18 * scale).dp),
                    tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.secondary
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(R.string.percent, savedPercent),
                        fontSize = (17 * scale).sp,
                        fontWeight = FontWeight.Bold,
                        style = TextStyle(fontFeatureSettings = "tnum"),
                        maxLines = 1
                    )
                    Text(
                        stringResource(R.string.blind_slot_label, slotNumber),
                        fontSize = (11 * scale).sp,
                        color = if (active) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                Icon(
                    Icons.Outlined.BookmarkAdd,
                    null,
                    Modifier.size((18 * scale).dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.save_slot, slotNumber),
                    fontSize = (15 * scale).sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable internal fun ShadeControlTile(
    control: Control,
    state: AppState,
    repository: EntiaRepository,
    panel: Boolean,
    onDragStateChange: (Boolean) -> Unit = {}
) {
    val device = control.device
    val command = state.commands[device.id]
    val raw = device.value(Attr.POSITION)?.takeIf { it in 0..100 }
    val moving = device.value(Attr.MOVING) == 1
    val tilt = state.tilts[device.id]
    // 0 = fully open, 100 = fully closed (matches Entia raw position directly)
    val closedPercent = raw ?: 0
    val usable = device.available && state.foreground && !state.needsSignIn
    val enabled = usable && command?.pending != true && !moving && raw != null
    val scale = panelScale(panel)

    var editingDevice by remember(device.id) { mutableStateOf(false) }
    var showBlindWindow by remember(device.id) { mutableStateOf(false) }
    var showTiltEditor by remember(device.id) { mutableStateOf(false) }

    val shape = RoundedCornerShape(if (panel) (18 * scale).dp else 20.dp)
    val tileHeight = if (panel) (62 * scale).coerceAtLeast(48f).dp else 68.dp
    val editLabel = stringResource(R.string.edit_device)

    if (editingDevice) DeviceEditor(device, repository) { editingDevice = false }

    // Room-level tile is strictly read-only/navigation: clicking only opens the blind window.
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(tileHeight)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainer, shape)
                .combinedClickable(
                    onClick = { showBlindWindow = true },
                    onLongClick = { editingDevice = true },
                    onLongClickLabel = editLabel
                )
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = (14 * scale).dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                WindowBlindGraphic(closedPercent = closedPercent, panel = panel, scale = scale)
                Spacer(modifier = Modifier.width((12 * scale).dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        device.name,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = if (panel) (17 * scale).sp else 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                    if (moving) {
                        Text(
                            stringResource(R.string.moving),
                            fontSize = if (panel) (12 * scale).sp else 12.sp,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    } else if (tilt?.directionKnown == true && tilt.tilt != null) {
                        Text(
                            stringResource(R.string.derived_tilt, tilt.tilt.toString()),
                            fontSize = if (panel) (12 * scale).sp else 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Text(
                    if (raw == null) stringResource(R.string.unknown)
                    else stringResource(R.string.percent, closedPercent),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = TextStyle(
                        fontSize = if (panel) (18 * scale).sp else 16.sp,
                        fontFeatureSettings = "tnum",
                        fontWeight = FontWeight.Bold
                    )
                )
                Spacer(modifier = Modifier.width((6 * scale).dp))
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = stringResource(R.string.blinds),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size((22 * scale).dp)
                )
            }
        }
        CommandOutcome(command)
    }

    if (showBlindWindow) {
        var draftClosed by remember(device.id) { mutableIntStateOf(closedPercent) }
        var adjusting by remember(device.id) { mutableStateOf(false) }
        val latestClosed by rememberUpdatedState(closedPercent)
        LaunchedEffect(closedPercent) {
            if (!adjusting) draftClosed = closedPercent
        }
        val winScale = panelScale(true)
        val slots = state.preferences.blindPresets[device.id] ?: BlindSlots()
        val positionDesc = stringResource(R.string.set_position)
        val decreaseDesc = stringResource(R.string.decrease)
        val increaseDesc = stringResource(R.string.increase)

        Dialog(onDismissRequest = { showBlindWindow = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                BoxWithConstraints(
                    Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                        .padding(horizontal = (14 * winScale).dp, vertical = (10 * winScale).dp)
                ) {
                    val compactHeight = maxHeight < 520.dp
                    Column(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy((10 * winScale).dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = (56 * winScale).coerceAtLeast(48f).dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            FilledTonalIconButton(
                                onClick = { showBlindWindow = false },
                                modifier = Modifier.width((76 * winScale).coerceAtLeast(60f).dp).height((54 * winScale).coerceAtLeast(48f).dp),
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back), Modifier.size((28 * winScale).dp))
                            }
                            Text(
                                device.name,
                                Modifier.weight(1f),
                                fontSize = (20 * winScale).sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            IconButton(
                                onClick = { showBlindWindow = false; editingDevice = true },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Outlined.Edit, stringResource(R.string.edit_device))
                            }
                        }

                        val stepperButtons: @Composable (Modifier) -> Unit = { rowMod ->
                            Row(
                                rowMod,
                                horizontalArrangement = Arrangement.spacedBy((12 * winScale).dp, Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val stepSize = if (compactHeight) (54 * winScale).coerceAtLeast(48f).dp else 56.dp
                                RepeatStepperButton(
                                    icon = Icons.Outlined.Remove,
                                    contentDescription = decreaseDesc,
                                    enabled = enabled && draftClosed > 0,
                                    onStep = {
                                        adjusting = true
                                        val next = (draftClosed - 1).coerceAtLeast(0)
                                        val changed = next != draftClosed
                                        draftClosed = next
                                        if (changed) next else null
                                    },
                                    onCommit = { target ->
                                        adjusting = false
                                        repository.command(device.id, Attr.POSITION, target)
                                    },
                                    modifier = Modifier.size(stepSize),
                                    shape = RoundedCornerShape(16.dp),
                                    iconSize = (28 * winScale).dp
                                )
                                RepeatStepperButton(
                                    icon = Icons.Outlined.Add,
                                    contentDescription = increaseDesc,
                                    enabled = enabled && draftClosed < 100,
                                    onStep = {
                                        adjusting = true
                                        val next = (draftClosed + 1).coerceAtMost(100)
                                        val changed = next != draftClosed
                                        draftClosed = next
                                        if (changed) next else null
                                    },
                                    onCommit = { target ->
                                        adjusting = false
                                        repository.command(device.id, Attr.POSITION, target)
                                    },
                                    modifier = Modifier.size(stepSize),
                                    shape = RoundedCornerShape(16.dp),
                                    iconSize = (28 * winScale).dp
                                )
                            }
                        }

                        val presetRow: @Composable () -> Unit = {
                            Column(
                                Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy((4 * winScale).dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy((10 * winScale).dp)
                                ) {
                                    BlindPresetSlot(
                                        slotNumber = 1,
                                        savedPercent = slots.slot1,
                                        currentPercent = draftClosed,
                                        enabled = enabled,
                                        scale = winScale,
                                        onApply = { target ->
                                            draftClosed = target
                                            repository.command(device.id, Attr.POSITION, target)
                                        },
                                        onSaveCurrent = { repository.setBlindPreset(device.id, 1, draftClosed) },
                                        modifier = Modifier.weight(1f)
                                    )
                                    BlindPresetSlot(
                                        slotNumber = 2,
                                        savedPercent = slots.slot2,
                                        currentPercent = draftClosed,
                                        enabled = enabled,
                                        scale = winScale,
                                        onApply = { target ->
                                            draftClosed = target
                                            repository.command(device.id, Attr.POSITION, target)
                                        },
                                        onSaveCurrent = { repository.setBlindPreset(device.id, 2, draftClosed) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Text(
                                    stringResource(R.string.blind_slot_hint),
                                    fontSize = (11 * winScale).sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        if (compactHeight) {
                            // 480x480 / compact square layout: vertical pill slider on left, readout + +/- + save slots on right
                            Row(
                                Modifier.weight(1f).fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy((14 * winScale).dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                VerticalBlindPillSlider(
                                    closedPercent = draftClosed,
                                    enabled = enabled,
                                    scale = winScale,
                                    description = positionDesc,
                                    onDraftChange = {
                                        adjusting = true
                                        draftClosed = it
                                    },
                                    onCommit = { target ->
                                        adjusting = false
                                        if (target != latestClosed) {
                                            repository.command(device.id, Attr.POSITION, target)
                                        }
                                    },
                                    modifier = Modifier.width((84 * winScale).coerceAtLeast(68f).dp).fillMaxHeight()
                                )
                                Column(
                                    Modifier.weight(1f).fillMaxHeight(),
                                    verticalArrangement = Arrangement.SpaceEvenly,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            stringResource(R.string.percent, draftClosed),
                                            style = TextStyle(
                                                fontSize = (36 * winScale).sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFeatureSettings = "tnum"
                                            ),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        if (moving) {
                                            Text(
                                                stringResource(R.string.moving),
                                                fontSize = (12 * winScale).sp,
                                                color = MaterialTheme.colorScheme.tertiary
                                            )
                                        }
                                    }
                                    stepperButtons(Modifier.fillMaxWidth())
                                    presetRow()
                                    if (tilt?.directionKnown == true && tilt.pending == null) {
                                        TextButton(
                                            onClick = { showBlindWindow = false; showTiltEditor = true },
                                            enabled = enabled,
                                            modifier = Modifier.heightIn(min = 36.dp)
                                        ) {
                                            Text(
                                                stringResource(R.string.derived_tilt, tilt.tilt?.toString() ?: "—"),
                                                fontSize = (12 * winScale).sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    CommandOutcome(command)
                                }
                            }
                        } else {
                            // Phone / tall layout: percentage -> centered vertical pill slider -> -/+ -> save slots
                            Column(
                                Modifier.weight(1f).fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        stringResource(R.string.percent, draftClosed),
                                        style = TextStyle(
                                            fontSize = 36.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFeatureSettings = "tnum"
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (moving) {
                                        Text(
                                            stringResource(R.string.moving),
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.tertiary
                                        )
                                    }
                                }

                                Box(
                                    Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    VerticalBlindPillSlider(
                                        closedPercent = draftClosed,
                                        enabled = enabled,
                                        scale = 1f,
                                        description = positionDesc,
                                        onDraftChange = {
                                            adjusting = true
                                            draftClosed = it
                                        },
                                        onCommit = { target ->
                                            adjusting = false
                                            if (target != latestClosed) {
                                                repository.command(device.id, Attr.POSITION, target)
                                            }
                                        },
                                        modifier = Modifier.width(96.dp).fillMaxHeight().heightIn(max = 360.dp)
                                    )
                                }

                                Column(
                                    Modifier.widthIn(max = 380.dp).fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    stepperButtons(Modifier.fillMaxWidth())
                                    presetRow()
                                    if (tilt?.directionKnown == true && tilt.pending == null) {
                                        TextButton(
                                            onClick = { showBlindWindow = false; showTiltEditor = true },
                                            enabled = enabled,
                                            modifier = Modifier.heightIn(min = 40.dp)
                                        ) {
                                            Text(
                                                stringResource(R.string.derived_tilt, tilt.tilt?.toString() ?: "—"),
                                                fontSize = 13.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    CommandOutcome(command)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showTiltEditor) {
        ValueEditor(
            title = stringResource(R.string.set_tilt),
            initial = tilt?.tilt ?: 0,
            range = 0..100,
            enabled = enabled && tilt?.directionKnown == true && tilt.pending == null,
            valueText = { stringResource(R.string.percent, it) },
            onDismiss = { showTiltEditor = false },
            onApply = { value ->
                repository.tilt(device.id, value)
                showTiltEditor = false
            }
        )
    }
}

@Composable internal fun TemperatureControlStrip(
    control: Control,
    state: AppState,
    repository: EntiaRepository,
    panel: Boolean
) {
    val device = control.device
    val command = state.commands[device.id]
    val rawTarget = device.value(Attr.TARGET)?.takeIf { it in 20..60 }
    val target = rawTarget?.div(2.0) ?: 22.0
    val current = device.temperature(Attr.INSIDE) ?: device.temperature(Attr.TEMPERATURE)
    val usable = device.available && state.foreground && !state.needsSignIn
    val enabled = usable && command?.pending != true
    val activity = state.home.activity(device)
    val isActive = activity.startsWith("Heating") || activity.startsWith("Cooling")
    val scale = panelScale(panel)

    var editingTarget by remember(device.id) { mutableStateOf(false) }
    var editingDevice by remember(device.id) { mutableStateOf(false) }
    val editLabel = stringResource(R.string.edit_device)

    if (editingDevice) DeviceEditor(device, repository) { editingDevice = false }
    if (editingTarget) {
        ValueEditor(
            title = stringResource(R.string.set_temperature),
            initial = rawTarget ?: 44,
            range = 20..60,
            enabled = enabled && rawTarget != null,
            valueText = { stringResource(R.string.celsius, it / 2.0) },
            onDismiss = { editingTarget = false },
            onApply = {
                repository.command(device.id, Attr.TARGET, it)
                editingTarget = false
            }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(if (panel) (20 * scale).dp else 20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(if (panel) (12 * scale).dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(if (panel) (10 * scale).dp else 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val btnSize = if (panel) (50 * scale).coerceAtLeast(38f).dp else 50.dp
                FilledTonalIconButton(
                    onClick = { rawTarget?.let { if (it > 20) repository.command(device.id, Attr.TARGET, it - 1) } },
                    enabled = enabled && rawTarget != null && rawTarget > 20,
                    modifier = Modifier.size(btnSize)
                ) {
                    Icon(
                        Icons.Outlined.Remove,
                        stringResource(R.string.decrease),
                        modifier = Modifier.size(if (panel) (24 * scale).dp else 24.dp)
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .combinedClickable(
                            onClick = { if (enabled && rawTarget != null) editingTarget = true },
                            onLongClick = { editingDevice = true },
                            onLongClickLabel = editLabel
                        )
                        .padding(vertical = (2 * scale).dp, horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (rawTarget != null) stringResource(R.string.panel_temperature, target)
                        else stringResource(R.string.unknown),
                        style = TextStyle(
                            fontSize = if (panel) (28 * scale).sp else 26.sp,
                            fontFeatureSettings = "tnum",
                            fontWeight = FontWeight.Bold
                        ),
                        color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                    val statusParts = buildList {
                        if (current != null) add(stringResource(R.string.celsius, current))
                        if (Attr.MODE in device.attributes) add(stringResource(activityLabel(activity)))
                    }
                    if (statusParts.isNotEmpty()) {
                        Text(
                            text = statusParts.joinToString(" · "),
                            style = TextStyle(
                                fontSize = if (panel) (14 * scale).sp else 13.sp,
                                fontFeatureSettings = "tnum",
                                fontWeight = FontWeight.Medium
                            ),
                            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                FilledTonalIconButton(
                    onClick = { rawTarget?.let { if (it < 60) repository.command(device.id, Attr.TARGET, it + 1) } },
                    enabled = enabled && rawTarget != null && rawTarget < 60,
                    modifier = Modifier.size(btnSize)
                ) {
                    Icon(
                        Icons.Outlined.Add,
                        stringResource(R.string.increase),
                        modifier = Modifier.size(if (panel) (24 * scale).dp else 24.dp)
                    )
                }
            }

            if (Attr.MODE in device.attributes) {
                val currentMode = device.value(Attr.MODE)
                val modes = buildList {
                    add(0 to stringResource(R.string.off))
                    add(2 to stringResource(R.string.heat))
                    if (!state.preferences.hideCooling) add(1 to stringResource(R.string.cool))
                }
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth().height(if (panel) (42 * scale).coerceAtLeast(32f).dp else 42.dp)
                ) {
                    modes.forEachIndexed { index, (modeValue, label) ->
                        SegmentedButton(
                            selected = currentMode == modeValue,
                            onClick = { repository.command(device.id, Attr.MODE, modeValue) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                            enabled = enabled && currentMode in 0..2
                        ) {
                            Text(
                                label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = if (panel) (15 * scale).sp else 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            CommandOutcome(command)
        }
    }
}

@Composable internal fun controlDisplayName(control: Control, state: AppState): String {
    val device = control.device
    if (device.id !in state.preferences.deviceNames) {
        if (control.kind == Attr.OUTSIDE &&
            (Attr.INSIDE in device.attributes || Attr.TEMPERATURE in device.attributes || isGenericOutsideLabel(device.name))
        ) {
            return stringResource(R.string.text_outside_sensor)
        }
        if (control.kind == Attr.TEMPERATURE && isExplicitOutsideLabel(device.name)) {
            return stringResource(R.string.text_outside_sensor)
        }
    }
    return device.name
}

@Composable internal fun TemperatureSensorTile(
    control: Control,
    state: AppState,
    repository: EntiaRepository,
    panel: Boolean
) {
    val device = control.device
    var editing by remember(device.id) { mutableStateOf(false) }
    val editLabel = stringResource(R.string.edit_device)
    val reading = device.temperature(control.kind)?.let { stringResource(R.string.celsius, it) }
        ?: stringResource(R.string.unknown)
    val scale = panelScale(panel)

    if (editing) DeviceEditor(device, repository) { editing = false }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (panel) (76 * scale).coerceAtLeast(48f).dp else 64.dp)
            .combinedClickable(
                onClick = { editing = true },
                onLongClick = { editing = true },
                onLongClickLabel = editLabel
            ),
        shape = RoundedCornerShape(if (panel) (20 * scale).dp else 20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = (16 * scale).dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy((12 * scale).dp)
        ) {
            Icon(
                controlIcon(control),
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(if (panel) (28 * scale).dp else 24.dp)
            )
            Text(
                controlDisplayName(control, state),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = if (panel) (19 * scale).sp else 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                reading,
                style = TextStyle(
                    fontSize = if (panel) (24 * scale).sp else 20.sp,
                    fontFeatureSettings = "tnum",
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable internal fun DeviceControl(
    control: Control,
    state: AppState,
    repository: EntiaRepository,
    panel: Boolean
) {
    val device = control.device
    val linkedEntiaId = if (state.preferences.hueEnabled && control.kind == Attr.LIGHT) {
        state.preferences.linkedEntiaSwitchId(device.id)
    } else null
    val linkedSwitch = linkedEntiaId?.let { state.home.devices[it] }
    val command = state.commands[device.id] ?: linkedEntiaId?.let { state.commands[it] }
    val usable = device.available && (linkedSwitch?.available ?: true) && state.foreground && !state.needsSignIn
    val enabled = usable && command?.pending != true
    var editing by remember(device.id) { mutableStateOf(false) }
    val editLabel = stringResource(R.string.edit_device)
    val scale = panelScale(panel)
    if (editing) DeviceEditor(device, repository) { editing = false }
    if (control.kind == Attr.LIGHT) {
        val isHueManaged = state.preferences.hueEnabled && state.preferences.hueBindingFor(device.id) != null
        var showHueWindow by remember(device.id) { mutableStateOf(false) }
        if (showHueWindow) {
            HueControlWindow(
                device = device,
                state = state,
                repository = repository,
                onOpenEditor = { editing = true },
                onDismiss = { showHueWindow = false }
            )
        }
        LightControlPill(
            control = control,
            state = state,
            enabled = enabled,
            panel = panel,
            onEdit = { editing = true },
            onOpenHue = if (isHueManaged) ({ showHueWindow = true }) else null,
            onToggle = {
                if (isHueManaged) repository.toggleHueLight(device.id)
                else if (!device.isStandaloneHue) repository.command(device.id, Attr.LIGHT, if (device.value(Attr.LIGHT) == 1) 0 else 1)
            }
        )
        return
    }
    if (control.kind in setOf(Attr.TEMPERATURE, Attr.INSIDE, Attr.OUTSIDE)) {
        TemperatureSensorTile(control, state, repository, panel)
        return
    }
    if (control.kind == Attr.POSITION) {
        ShadeControlTile(control, state, repository, panel)
        return
    }
    if (control.kind == Attr.TARGET) {
        TemperatureControlStrip(control, state, repository, panel)
        return
    }
    Card(
        Modifier.fillMaxWidth().combinedClickable(
            onClick = { editing = true },
            onLongClick = { editing = true },
            onLongClickLabel = editLabel
        ),
        shape = RoundedCornerShape(if (panel) (24 * scale).dp else 20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            Modifier.padding(if (panel) (16 * scale).dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (panel) (14 * scale).dp else 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    controlIcon(control),
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(if (panel) (30 * scale).dp else 24.dp)
                )
                Spacer(Modifier.width((12 * scale).dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        device.name,
                        fontSize = if (panel) (22 * scale).sp else 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    val subtitle = if (control.kind == Attr.HRV_SPEED) {
                        val speed = device.value(Attr.HRV_SPEED)?.takeIf { it in 0..3 } ?: 0
                        val speedLabel = stringResource(
                            when (speed) {
                                1 -> R.string.low
                                2 -> R.string.medium
                                3 -> R.string.high
                                else -> R.string.off
                            }
                        )
                        "${controlLabel(control.kind)} · $speedLabel"
                    } else {
                        controlLabel(control.kind)
                    }
                    Text(
                        subtitle,
                        fontSize = if (panel) (15 * scale).sp else 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (!device.available) {
                Text(stringResource(R.string.unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else when (control.kind) {
                Attr.HRV_SPEED -> VentilationControl(device, state, repository, enabled, panel)
                else -> Text(stringResource(R.string.read_only_device), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            CommandOutcome(command)
        }
    }
}

@Composable private fun LightControlPill(
    control: Control,
    state: AppState,
    enabled: Boolean,
    panel: Boolean,
    onEdit: () -> Unit,
    onOpenHue: (() -> Unit)? = null,
    onToggle: () -> Unit
) {
    val device = control.device
    val resolved = resolveLightState(state.home, state.preferences, state.hue, device)
    val binding = resolved.binding
    val hueTarget = resolved.target
    val mainsOn = resolved.mainsOn
    val on = resolved.on
    val status = stringResource(if (on) R.string.on else R.string.off)
    val powerLabel = stringResource(R.string.device_power, device.name)
    val editLabel = stringResource(R.string.edit_device)
    val scale = panelScale(panel)
    val hueSubtitle = if (binding != null) {
        when {
            !mainsOn -> stringResource(R.string.hue_mains_off)
            hueTarget != null && hueTarget.on && hueTarget.supportsDimming && hueTarget.brightness != null ->
                if (panel) stringResource(R.string.percent, hueTarget.brightness)
                else "${stringResource(R.string.hue_badge)} · ${stringResource(R.string.percent, hueTarget.brightness)}"
            hueTarget != null && !hueTarget.on ->
                "${stringResource(R.string.hue_badge)} · ${stringResource(R.string.off)}"
            else -> stringResource(R.string.hue_badge)
        }
    } else null

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(
                if (binding != null) {
                    if (panel) (62 * scale).coerceAtLeast(48f).dp else 68.dp
                } else {
                    if (panel) (56 * scale).coerceAtLeast(44f).dp else 64.dp
                }
            )
            .combinedClickable(
                onClick = { if (enabled) onToggle() },
                onLongClick = onEdit,
                onLongClickLabel = editLabel
            )
            .semantics {
                role = Role.Switch
                contentDescription = powerLabel
                stateDescription = status
                toggleableState = if (on) ToggleableState.On else ToggleableState.Off
            },
        shape = RoundedCornerShape(if (panel) (18 * scale).dp else 20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
            contentColor = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
        )
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(start = (14 * scale).dp, end = if (onOpenHue != null) (4 * scale).dp else (14 * scale).dp),
            horizontalArrangement = Arrangement.spacedBy((8 * scale).dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                controlIcon(control),
                null,
                tint = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(if (panel) (24 * scale).dp else 24.dp)
            )
            Column(Modifier.weight(1f)) {
                Text(
                    device.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = if (panel) (17 * scale).sp else 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (hueSubtitle != null) {
                    Text(
                        hueSubtitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = if (panel) (12 * scale).coerceAtLeast(10.5f).sp else 12.sp,
                        color = if (!mainsOn) MaterialTheme.colorScheme.error
                        else if (on) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (onOpenHue != null) {
                IconButton(
                    onClick = onOpenHue,
                    modifier = Modifier.size(if (panel) (38 * scale).coerceAtLeast(36f).dp else 44.dp)
                ) {
                    Icon(
                        Icons.Outlined.Tune,
                        contentDescription = stringResource(R.string.hue_controls, device.name),
                        tint = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(if (panel) (20 * scale).coerceAtLeast(18f).dp else 22.dp)
                    )
                }
            }
        }
    }
}

@Composable private fun CommandOutcome(command: Command?) {
    command?.let {
        if (it.phase == CommandPhase.Confirmed) return@let
        Text(
            stringResource(
                when (it.phase) {
                    CommandPhase.Sending -> R.string.command_sending
                    CommandPhase.Awaiting -> R.string.command_awaiting
                    CommandPhase.Confirmed -> R.string.command_observed
                    CommandPhase.Unconfirmed -> R.string.command_unknown
                    CommandPhase.Failed -> R.string.command_failed
                }
            ),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = if (it.phase == CommandPhase.Failed || it.phase == CommandPhase.Unconfirmed)
                MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        )
        it.error?.let { message -> Text(message, style = MaterialTheme.typography.bodySmall) }
    }
}

private fun activityLabel(activity: String) = when (activity) {
    "Off" -> R.string.off
    "Idle" -> R.string.idle
    "Heating" -> R.string.heating
    "Cooling" -> R.string.cooling
    "Heating · estimated" -> R.string.heating_estimated
    "Cooling · estimated" -> R.string.cooling_estimated
    "Idle · estimated" -> R.string.idle_estimated
    else -> R.string.unknown
}

@Composable internal fun VentilationControl(
    device: Device,
    state: AppState,
    repository: EntiaRepository,
    enabled: Boolean,
    panel: Boolean = false
) {
    val speed = device.value(Attr.HRV_SPEED)?.takeIf { it in 0..3 } ?: 0
    val hasMode = Attr.HRV_MODE in device.attributes
    val scale = panelScale(panel)
    val speedLabels = listOf(
        stringResource(R.string.off),
        stringResource(R.string.low),
        stringResource(R.string.medium),
        stringResource(R.string.high)
    )

    Column(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(if (panel) (12 * scale).dp else 12.dp)
    ) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth().height(if (panel) (54 * scale).coerceAtLeast(38f).dp else 48.dp)
        ) {
            listOf("0", "1", "2", "3").forEachIndexed { index, shortLabel ->
                val fullLabel = speedLabels[index]
                SegmentedButton(
                    selected = speed == index,
                    onClick = { repository.command(device.id, Attr.HRV_SPEED, index) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = 4),
                    enabled = enabled,
                    modifier = Modifier.semantics { contentDescription = fullLabel }
                ) {
                    Text(
                        if (index == 0 && !panel) fullLabel else shortLabel,
                        maxLines = 1,
                        fontSize = if (panel) (19 * scale).sp else 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (hasMode) {
            val mode = device.value(Attr.HRV_MODE)
            val modes = listOf(
                0 to stringResource(R.string.bypass),
                1 to stringResource(R.string.heat_recovery)
            )
            Text(
                stringResource(R.string.ventilation_mode),
                fontSize = if (panel) (16 * scale).sp else 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth().height(if (panel) (54 * scale).coerceAtLeast(38f).dp else 48.dp)
            ) {
                modes.forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = mode == value,
                        onClick = { repository.command(device.id, Attr.HRV_MODE, value) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                        enabled = enabled && mode in 0..1
                    ) {
                        Text(
                            label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = if (panel) (17 * scale).sp else 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

/** Full-window editor keeps the value and large controls prominent on square/compact panels. */
@Composable internal fun ValueEditor(
    title: String,
    initial: Int,
    range: IntRange,
    enabled: Boolean,
    valueText: @Composable (Int) -> String,
    onDismiss: () -> Unit,
    onApply: (Int) -> Unit
) {
    var value by remember { mutableIntStateOf(initial.coerceIn(range)) }
    val scale = panelScale(true)
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .imePadding()
                    .padding(horizontal = (14 * scale).dp, vertical = (10 * scale).dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = (64 * scale).coerceAtLeast(52f).dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalIconButton(
                        onClick = onDismiss,
                        modifier = Modifier.width((76 * scale).coerceAtLeast(60f).dp).height((56 * scale).coerceAtLeast(48f).dp),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back), Modifier.size((30 * scale).dp))
                    }
                    Text(
                        title,
                        Modifier.weight(1f),
                        fontSize = (20 * scale).sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Button(
                        { onApply(value) },
                        enabled = enabled,
                        modifier = Modifier.height((56 * scale).coerceAtLeast(48f).dp),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Text(
                            stringResource(R.string.apply),
                            fontSize = (18 * scale).sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Row(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = (8 * scale).dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy((12 * scale).dp)
                ) {
                    val btnSize = (68 * scale).coerceAtLeast(48f).dp
                    FilledTonalIconButton({ value-- }, Modifier.size(btnSize), enabled = enabled && value > range.first) {
                        Icon(Icons.Outlined.Remove, stringResource(R.string.decrease), Modifier.size((32 * scale).dp))
                    }
                    Text(
                        valueText(value),
                        Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = TextStyle(
                            fontSize = (48 * scale).sp,
                            fontWeight = FontWeight.Bold,
                            fontFeatureSettings = "tnum"
                        )
                    )
                    FilledTonalIconButton({ value++ }, Modifier.size(btnSize), enabled = enabled && value < range.last) {
                        Icon(Icons.Outlined.Add, stringResource(R.string.increase), Modifier.size((32 * scale).dp))
                    }
                }
                Slider(
                    value.toFloat(),
                    { value = it.roundToInt() },
                    valueRange = range.first.toFloat()..range.last.toFloat(),
                    steps = (range.last - range.first - 1).coerceAtLeast(0),
                    enabled = enabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((60 * scale).coerceAtLeast(48f).dp)
                        .padding(horizontal = (6 * scale).dp)
                        .semantics { contentDescription = title }
                )
            }
        }
    }
}
