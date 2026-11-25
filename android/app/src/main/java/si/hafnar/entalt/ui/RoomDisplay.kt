package si.hafnar.entalt.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.min
import kotlinx.coroutines.delay
import si.hafnar.entalt.R
import si.hafnar.entalt.data.AppState
import si.hafnar.entalt.domain.Room

/** Ambient 1:1 wall-panel clock face. Shows one room, no mode/room toggle. */
@Composable internal fun RoomDisplay(
    state: AppState,
    onSettings: () -> Unit,
    roomId: Long? = null,
    onClimate: (Long) -> Unit = {},
    onVentilation: () -> Unit = {},
    onReconnect: () -> Unit = {}
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = LocalView.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = System.currentTimeMillis()
                delay(60_000 - now % 60_000)
            }
        }
    }
    DisposableEffect(view) {
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        val window = context.activity()?.window
        val insets = ViewCompat.getRootWindowInsets(view)
        val statusWasVisible = insets?.isVisible(WindowInsetsCompat.Type.statusBars()) ?: true
        val navigationWasVisible = insets?.isVisible(WindowInsetsCompat.Type.navigationBars()) ?: true
        val controller = window?.let { WindowInsetsControllerCompat(it, view) }
        val previousBehavior = controller?.systemBarsBehavior
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            view.keepScreenOn = previous
            if (statusWasVisible) controller?.show(WindowInsetsCompat.Type.statusBars())
            if (navigationWasVisible) controller?.show(WindowInsetsCompat.Type.navigationBars())
            if (previousBehavior != null) controller?.systemBarsBehavior = previousBehavior
        }
    }
    val zone = TimeZone.getDefault().id
    val clock = remember(locale, zone) { SimpleDateFormat("HH:mm", locale) }
    val number = remember(locale) {
        NumberFormat.getNumberInstance(locale).apply {
            maximumFractionDigits = 1
            minimumFractionDigits = 0
        }
    }
    val room = state.home.displayRoom(roomId ?: state.preferences.displayRoom)
    val indoor = room?.let { state.home.indoorTemperature(it.id) }
    val outdoor = state.home.outdoorTemperature(room?.id)
    val insideText = indoor?.let { stringResource(R.string.display_temperature, number.format(it)) } ?: "—"
    val outsideText = outdoor?.let { stringResource(R.string.display_temperature, number.format(it)) } ?: "—"
    var showControls by remember { mutableStateOf(false) }
    val childLock = state.preferences.childLock
    var holding by remember { mutableStateOf(false) }
    var holdProgress by remember { mutableFloatStateOf(0f) }
    var showLockHint by remember { mutableStateOf(false) }
    LaunchedEffect(childLock) {
        if (!childLock) {
            holding = false
            holdProgress = 0f
            showLockHint = false
        }
    }
    LaunchedEffect(holding) {
        if (!holding) {
            holdProgress = 0f
            return@LaunchedEffect
        }
        val started = System.currentTimeMillis()
        val unlockMs = 1500f
        while (holding) {
            val progress = ((System.currentTimeMillis() - started) / unlockMs).coerceIn(0f, 1f)
            holdProgress = progress
            if (progress >= 1f) {
                holding = false
                holdProgress = 0f
                showLockHint = false
                if (!state.demo && (!state.restReachable || !state.socketConnected)) onReconnect()
                showControls = true
                break
            }
            delay(16L)
        }
    }
    LaunchedEffect(showLockHint, holding) {
        if (showLockHint && !holding) {
            delay(2500L)
            showLockHint = false
        }
    }
    val controlsLabel = stringResource(R.string.display_controls)
    val white = Color(0xFFF8F7F3)
    val soft = Color(0xFFBDBAB3)
    val muted = Color(0xFF8F8D87)
    val interactionModifier = if (childLock) {
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                holding = true
                showLockHint = true
                waitForUpOrCancellation()
                holding = false
            }
        }
    } else {
        Modifier.clickable(onClickLabel = controlsLabel) {
            if (!state.demo && (!state.restReachable || !state.socketConnected)) onReconnect()
            showControls = true
        }
    }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .testTag("clock_face")
            .background(Color(0xFF080909))
            .then(interactionModifier)
    ) {
        val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        val minDim = min(maxWidth.value, maxHeight.value)
        val effectiveDim = (minDim / scale).coerceIn(200f, 520f)
        val short = effectiveDim < 280f
        val compactWidth = minDim < 360f
        val heatingRooms = remember(state.home) { state.home.heatingIndicatorRooms }
        val hasHeatingRow = state.preferences.showHeatingRooms && heatingRooms.isNotEmpty()

        val clockSize = (effectiveDim * if (hasHeatingRow) 0.32f else 0.35f).sp
        val readingSize = (effectiveDim * if (hasHeatingRow) 0.105f else 0.1125f).sp
        val labelSize = (effectiveDim * 0.042f).coerceIn(12f, 18f).sp
        val circleSize = (minDim * 0.062f).coerceIn(20f, 29f).dp
        val circleFont = (effectiveDim * 0.033f).coerceIn(10f, 14f).sp
        val circleSpacing = (minDim * 0.020f).coerceIn(5f, 10f).dp
        val barHeight = (minDim * 0.085f).coerceIn(30f, 44f).dp

        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = if (short || compactWidth) 10.dp else 16.dp, vertical = if (short) 8.dp else 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceEvenly
            ) {
                AnimatedClock(clock.format(Date(now)), clockSize, white)

                DayOfWeekIndicators(
                    now = now,
                    locale = locale,
                    activeColor = white,
                    circleSize = circleSize,
                    fontSize = circleFont,
                    spacing = circleSpacing
                )

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        Modifier.widthIn(max = (minDim * 0.96f).dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(if (compactWidth) 8.dp else 14.dp)
                    ) {
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                room?.name ?: stringResource(R.string.indoors),
                                fontSize = labelSize,
                                fontWeight = FontWeight.Medium,
                                color = muted,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(4.dp))
                            FadingReading(insideText, white, readingSize)
                        }
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                stringResource(R.string.outdoors),
                                fontSize = labelSize,
                                fontWeight = FontWeight.Medium,
                                color = muted,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(4.dp))
                            FadingReading(outsideText, white, readingSize)
                        }
                    }
                    if (state.demo) {
                        Text(stringResource(R.string.demo_short), style = MaterialTheme.typography.labelSmall, color = muted)
                    }
                    if (indoor == null) {
                        Text(
                            stringResource(R.string.display_no_temperature),
                            style = MaterialTheme.typography.bodySmall,
                            color = muted,
                            textAlign = TextAlign.Center,
                            maxLines = if (short) 2 else 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (hasHeatingRow) {
                HeatingRoomsBar(
                    rooms = heatingRooms,
                    isHeating = { state.home.isRoomHeating(it.id) },
                    height = barHeight,
                    short = short
                )
            }
        }
        if (childLock) {
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = stringResource(R.string.child_lock),
                tint = muted.copy(alpha = 0.65f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .size(if (short) 13.dp else 16.dp)
                    .testTag("child_lock_badge")
            )
        }
        AnimatedVisibility(
            visible = childLock && showLockHint,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(220)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 10.dp, start = 16.dp, end = 16.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xE61B1D1C),
                contentColor = white,
                modifier = Modifier.testTag("child_lock_hint")
            ) {
                Column(
                    Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Outlined.Lock, null, Modifier.size(14.dp), tint = soft)
                        Text(
                            stringResource(R.string.child_lock_hold_hint),
                            fontSize = if (short) 11.sp else 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (holdProgress > 0f) {
                        LinearProgressIndicator(
                            progress = { holdProgress },
                            modifier = Modifier
                                .width(if (short) 110.dp else 148.dp)
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = white,
                            trackColor = Color(0xFF343836)
                        )
                    }
                }
            }
        }
    }
    if (showControls) {
        Dialog({ showControls = false }, DialogProperties(usePlatformDefaultWidth = false)) {
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xD9080909))
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null
                    ) { showControls = false }
                    .padding(18.dp),
                contentAlignment = Alignment.Center
            ) {
                val scale = (min(maxWidth.value, maxHeight.value) / 480f).coerceIn(0.68f, 1.1f)
                val boundRoom = room ?: state.home.visibleRooms.firstOrNull()
                Surface(
                    Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null
                        ) {},
                    shape = RoundedCornerShape((28 * scale).dp),
                    color = Color(0xFF141616),
                    contentColor = white
                ) {
                    Column(
                        Modifier.fillMaxSize().padding((18 * scale).dp),
                        verticalArrangement = Arrangement.spacedBy((12 * scale).dp)
                    ) {
                        if (boundRoom != null) {
                            FilledTonalButton(
                                {
                                    showControls = false
                                    onClimate(boundRoom.id)
                                },
                                Modifier.weight(1.15f).fillMaxWidth(),
                                shape = RoundedCornerShape((22 * scale).dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = Color(0xFF262928),
                                    contentColor = white
                                )
                            ) {
                                Icon(Icons.Outlined.MeetingRoom, null, Modifier.size((28 * scale).dp))
                                Spacer(Modifier.width((12 * scale).dp))
                                Text(
                                    stringResource(R.string.your_rooms),
                                    textAlign = TextAlign.Center,
                                    fontSize = (22 * scale).sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        Row(
                            Modifier.weight(1f).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy((12 * scale).dp)
                        ) {
                            if (state.home.ventilationUnits.isNotEmpty()) {
                                FilledTonalButton(
                                    {
                                        showControls = false
                                        onVentilation()
                                    },
                                    Modifier.weight(1f).fillMaxHeight(),
                                    shape = RoundedCornerShape((22 * scale).dp),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = Color(0xFF262928),
                                        contentColor = white
                                    )
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                        Icon(Icons.Outlined.Air, null, Modifier.size((26 * scale).dp))
                                        Spacer(Modifier.height((6 * scale).dp))
                                        Text(
                                            stringResource(R.string.ventilation),
                                            textAlign = TextAlign.Center,
                                            fontSize = (18 * scale).sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                            FilledTonalButton(
                                {
                                    showControls = false
                                    onSettings()
                                },
                                Modifier.weight(1f).fillMaxHeight(),
                                shape = RoundedCornerShape((22 * scale).dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = Color(0xFF262928),
                                    contentColor = white
                                )
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    Icon(Icons.Outlined.Settings, null, Modifier.size((26 * scale).dp))
                                    Spacer(Modifier.height((6 * scale).dp))
                                    Text(
                                        stringResource(R.string.settings),
                                        textAlign = TextAlign.Center,
                                        fontSize = (18 * scale).sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        TextButton(
                            { showControls = false },
                            Modifier.fillMaxWidth().height((56 * scale).coerceAtLeast(48f).dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = soft)
                        ) {
                            Text(
                                stringResource(R.string.close_controls),
                                fontSize = (18 * scale).sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun HeatingRoomsBar(
    rooms: List<Room>,
    isHeating: (Room) -> Boolean,
    height: Dp,
    short: Boolean,
) {
    val heatingRed = Color(0xFFEA4335)
    val heatingLabel = stringResource(R.string.heating)
    val textSize = when {
        short -> 10.sp
        rooms.size <= 3 -> 14.sp
        rooms.size <= 5 -> 12.sp
        else -> 10.sp
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(height)
            .testTag("heating_rooms_row"),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        rooms.forEach { room ->
            val active = isHeating(room)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (active) heatingRed else Color.Transparent)
                    .padding(horizontal = 3.dp)
                    .clearAndSetSemantics {
                        if (active) contentDescription = "${room.name}: $heatingLabel"
                    },
                contentAlignment = Alignment.Center
            ) {
                if (active) {
                    Text(
                        text = room.name,
                        color = Color.Black,
                        fontSize = textSize,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable private fun DayOfWeekIndicators(
    now: Long,
    locale: Locale,
    activeColor: Color,
    circleSize: Dp = 24.dp,
    fontSize: TextUnit = 12.sp,
    spacing: Dp = 5.dp
) {
    val calendar = remember(now) { Calendar.getInstance().apply { timeInMillis = now } }
    val currentDayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
    val dateDescription = remember(now, locale) { SimpleDateFormat("EEEE, d MMM", locale).format(Date(now)) }
    val slovenian = locale.language == "sl"
    val days = if (slovenian) {
        listOf(2 to "P", 3 to "T", 4 to "S", 5 to "Č", 6 to "P", 7 to "S", 1 to "N")
    } else {
        listOf(2 to "M", 3 to "T", 4 to "W", 5 to "T", 6 to "F", 7 to "S", 1 to "S")
    }
    val activeIndex = days.indexOfFirst { it.first == currentDayOfWeek }.coerceAtLeast(0)
    val dotSize = circleSize * 0.88f
    val thumbWidth = circleSize * 1.42f
    val panelBg = Color(0xFF080909)
    val pastFill = activeColor.copy(alpha = 0.16f)
    val pastBorder = activeColor.copy(alpha = 0.28f)
    val pastText = activeColor.copy(alpha = 0.52f)
    val futureFillWeekday = activeColor.copy(alpha = 0.05f)
    val futureBorderWeekday = activeColor.copy(alpha = 0.12f)
    val futureBorderWeekend = activeColor.copy(alpha = 0.24f)
    val futureText = activeColor.copy(alpha = 0.26f)
    val thumbFill = activeColor.copy(alpha = 0.62f)
    val thumbBorder = activeColor.copy(alpha = 0.78f)
    val elapsedTrack = activeColor.copy(alpha = 0.42f)
    val remainingTrack = activeColor.copy(alpha = 0.14f)

    Row(
        modifier = Modifier
            .clearAndSetSemantics { contentDescription = dateDescription }
            .drawBehind {
                val spacingPx = spacing.toPx()
                val dotW = dotSize.toPx()
                val thumbW = thumbWidth.toPx()
                fun centerX(idx: Int): Float {
                    var x = 0f
                    for (i in 0 until idx) {
                        x += (if (i == activeIndex) thumbW else dotW) + spacingPx
                    }
                    return x + (if (idx == activeIndex) thumbW else dotW) / 2f
                }
                val startX = centerX(0)
                val endX = centerX(days.lastIndex)
                val activeX = centerX(activeIndex)
                val y = size.height / 2f
                drawLine(
                    color = remainingTrack,
                    start = Offset(startX, y),
                    end = Offset(endX, y),
                    strokeWidth = 1.5.dp.toPx(),
                    cap = StrokeCap.Round
                )
                if (activeIndex > 0) {
                    drawLine(
                        color = elapsedTrack,
                        start = Offset(startX, y),
                        end = Offset(activeX, y),
                        strokeWidth = 2.5.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            },
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        days.forEachIndexed { index, (_, label) ->
            val isWeekend = index >= 5
            val isActive = index == activeIndex
            val isPast = index < activeIndex
            val shape = if (isActive) RoundedCornerShape(50) else CircleShape
            val fill = when {
                isActive -> thumbFill
                isPast -> pastFill
                isWeekend -> Color.Transparent
                else -> futureFillWeekday
            }
            val borderColor = when {
                isActive -> thumbBorder
                isPast -> pastBorder
                isWeekend -> futureBorderWeekend
                else -> futureBorderWeekday
            }
            val textColor = when {
                isActive -> panelBg
                isPast -> pastText
                else -> futureText
            }
            Box(
                modifier = Modifier
                    .width(if (isActive) thumbWidth else dotSize)
                    .height(if (isActive) circleSize else dotSize)
                    .background(panelBg, shape)
                    .background(fill, shape)
                    .border(width = if (isActive) 1.dp else 0.65.dp, color = borderColor, shape = shape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    fontSize = fontSize,
                    fontWeight = when {
                        isActive -> FontWeight.Bold
                        isPast -> FontWeight.Medium
                        else -> FontWeight.Normal
                    },
                    color = textColor
                )
            }
        }
    }
}

@Composable private fun AnimatedClock(value: String, size: TextUnit, color: Color) {
    val digitWidth = with(LocalDensity.current) { size.toDp() }
    Row(
        Modifier.clearAndSetSemantics { contentDescription = value },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        value.forEachIndexed { index, character ->
            Box(
                Modifier
                    .width(digitWidth * if (index == 2) 0.24f else 0.58f)
                    .height(digitWidth * 1.08f)
                    .clipToBounds(),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    character,
                    transitionSpec = {
                        (fadeIn(tween(220, easing = FastOutSlowInEasing)) + slideInVertically(tween(220)) { it / 6 }) togetherWith
                            (fadeOut(tween(140)) + slideOutVertically(tween(140)) { -it / 6 })
                    },
                    label = "Clock digit"
                ) { digit ->
                    Text(
                        digit.toString(),
                        fontSize = size,
                        lineHeight = size * 1.02f,
                        fontWeight = FontWeight.Light,
                        color = color,
                        maxLines = 1,
                        softWrap = false,
                        textAlign = TextAlign.Center,
                        style = TextStyle(fontFeatureSettings = "tnum")
                    )
                }
            }
        }
    }
}

@Composable private fun FadingReading(value: String, color: Color, size: TextUnit = 22.sp) {
    var fittedSize by remember(value, size) { mutableStateOf(size) }
    val minSize = size * 0.72f
    Crossfade(value, animationSpec = tween(220), label = "Temperature reading") {
        Text(
            it,
            fontSize = fittedSize,
            lineHeight = fittedSize * 1.10f,
            fontWeight = FontWeight.Light,
            color = color,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
            style = TextStyle(fontFeatureSettings = "tnum"),
            onTextLayout = { result ->
                if (result.didOverflowWidth && fittedSize.value > minSize.value) {
                    fittedSize = (fittedSize.value * 0.92f).coerceAtLeast(minSize.value).sp
                }
            }
        )
    }
}

/** Keep scarce panel pixels available on controls and authentication screens too. */
@Composable internal fun PanelWindow(enabled: Boolean) {
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        val controller = context.activity()?.window?.let { WindowInsetsControllerCompat(it, view) }
        val insets = ViewCompat.getRootWindowInsets(view)
        val statusWasVisible = insets?.isVisible(WindowInsetsCompat.Type.statusBars()) ?: true
        val navigationWasVisible = insets?.isVisible(WindowInsetsCompat.Type.navigationBars()) ?: true
        val previousBehavior = controller?.systemBarsBehavior
        if (enabled) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (enabled) {
                if (statusWasVisible) controller?.show(WindowInsetsCompat.Type.statusBars())
                if (navigationWasVisible) controller?.show(WindowInsetsCompat.Type.navigationBars())
                if (previousBehavior != null) controller?.systemBarsBehavior = previousBehavior
            }
        }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
