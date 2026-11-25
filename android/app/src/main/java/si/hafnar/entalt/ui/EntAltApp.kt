@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package si.hafnar.entalt.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import si.hafnar.entalt.BuildConfig
import si.hafnar.entalt.R
import si.hafnar.entalt.data.*
import si.hafnar.entalt.domain.*

private enum class Page { Home, Settings, Rooms, Controls, Connection, Replace, Ventilation }

@Composable fun EntAltApp(
    state: AppState,
    repository: EntiaRepository,
    isProfileA: Boolean = false,
    onExit: () -> Unit = {}
) {
    var page by rememberSaveable { mutableStateOf(Page.Home) }
    var focusedControl by rememberSaveable { mutableStateOf<String?>(null) }
    var browsingDisplay by rememberSaveable { mutableStateOf(false) }
    val owner = if (state.demo) "demo" else state.preferences.accountId
    var displayedRoom by rememberSaveable(owner, state.preferences.displayRoom) {
        mutableStateOf(state.preferences.displayRoom)
    }
    var navigationOwner by rememberSaveable { mutableStateOf(owner) }
    var autoSelectedInitialRoom by rememberSaveable(owner) { mutableStateOf(false) }
    val viewState = rememberSaveableStateHolder()

    val window = LocalWindowInfo.current.containerSize
    // Clock view exists ONLY on 1:1 (isProfileA) resolution.
    val displayLayout = isProfileA
    val displayingRoom = displayLayout && !browsingDisplay && page == Page.Home

    LaunchedEffect(displayLayout, owner, state.home.visibleRooms.isNotEmpty()) {
        if (!displayLayout && !autoSelectedInitialRoom && state.home.visibleRooms.isNotEmpty()) {
            autoSelectedInitialRoom = true
            if (state.selectedRoom == null) {
                val preferred = state.home.startupRoom(state.preferences.startupRoom, state.preferences.lastRoom)
                    ?: state.home.visibleRooms.firstOrNull {
                        val name = it.name.lowercase(Locale.ROOT)
                        "living room" in name || "dnevna" in name
                    }?.id
                    ?: state.home.visibleRooms.firstOrNull()?.id
                if (preferred != null) {
                    repository.selectRoom(preferred)
                }
            }
        }
    }

    LaunchedEffect(owner, state.restoring) {
        if (!state.restoring && navigationOwner != owner) {
            page = Page.Home
            focusedControl = null
            browsingDisplay = false
            autoSelectedInitialRoom = false
            viewState.removeState("rooms")
            navigationOwner = owner
        }
    }

    LaunchedEffect(state.selectedRoom) {
        if (state.selectedRoom == null && page == Page.Controls) {
            page = Page.Home
            focusedControl = null
        }
    }

    val back = {
        when {
            page == Page.Replace || page == Page.Connection -> page = Page.Settings
            page != Page.Home -> page = Page.Home
            displayLayout && browsingDisplay -> browsingDisplay = false
            state.selectedRoom != null -> repository.selectRoom(null)
            else -> browsingDisplay = false
        }
    }

    val density = LocalDensity.current.density
    PanelWindow(displayLayout || window.height / density <= 480f || window.width / density < 360f)
    BackHandler(page != Page.Home || (!displayingRoom && (state.selectedRoom != null || browsingDisplay))) { back() }

    Surface(Modifier.fillMaxSize()) {
        if (!state.restoring && state.preferences.disclaimerAccepted && state.username != null && !state.needsSignIn && page != Page.Replace && displayingRoom) {
            RoomDisplay(
                state = state,
                roomId = displayedRoom,
                onClimate = { roomId ->
                    if (!state.demo && (!state.restReachable || !state.socketConnected)) repository.refresh()
                    repository.selectRoom(roomId)
                    focusedControl = controls(state.home, roomId).firstOrNull { it.kind == Attr.TARGET }?.key
                    browsingDisplay = true
                },
                onVentilation = {
                    if (!state.demo && (!state.restReachable || !state.socketConnected)) repository.refresh()
                    page = Page.Ventilation
                },
                onSettings = { page = Page.Settings },
                onReconnect = repository::refresh
            )
            return@Surface
        }
        val insetsModifier = if (isProfileA || window.height / density <= 480f || window.width / density < 360f) {
            Modifier.fillMaxSize().imePadding()
        } else {
            Modifier.fillMaxSize().safeDrawingPadding()
        }
        BoxWithConstraints(insetsModifier) {
            val windowWidth = maxWidth
            val windowHeight = maxHeight
            val panel = isProfileA || windowWidth < 360.dp || windowHeight <= 480.dp
            val wide = !panel && windowWidth >= 840.dp && windowHeight >= 480.dp
            when {
                state.restoring -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                !state.preferences.disclaimerAccepted -> UnofficialNotice(state, repository::acceptDisclaimer, onExit)
                state.username == null || state.needsSignIn || page == Page.Replace -> SignIn(
                    state,
                    repository,
                    replacement = page == Page.Replace && !state.needsSignIn,
                    onCancel = { page = Page.Settings },
                    onSuccess = { page = Page.Home }
                )
                else -> Column(Modifier.fillMaxSize().widthIn(max = 1440.dp).align(Alignment.TopCenter)) {
                    val room = state.home.visibleRooms.firstOrNull { it.id == state.selectedRoom }
                    AppToolbar(
                        title = when (page) {
                            Page.Settings -> stringResource(R.string.settings)
                            Page.Rooms -> stringResource(R.string.choose_room)
                            Page.Controls -> stringResource(R.string.choose_control)
                            Page.Connection -> stringResource(R.string.connection)
                            Page.Ventilation -> stringResource(if (panel) R.string.ventilation else R.string.home_ventilation)
                            else -> if (panel) room?.name ?: stringResource(R.string.app_name) else stringResource(R.string.app_name)
                        },
                        showBack = page != Page.Home || room != null || browsingDisplay,
                        onBack = back,
                        onSettings = if (page == Page.Settings || page == Page.Connection || page == Page.Replace) {
                            null
                        } else {
                            { page = Page.Settings }
                        },
                        compact = panel
                    )
                    if (state.demo && !panel) {
                        Text(
                            stringResource(R.string.demo_banner),
                            Modifier.fillMaxWidth()
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            maxLines = if (panel) 1 else Int.MAX_VALUE,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    when (page) {
                        Page.Settings -> if (panel) {
                            PanelSettings(state, repository, onReplace = { page = Page.Replace }, onConnection = { page = Page.Connection })
                        } else {
                            Settings(state, repository, onReplace = { page = Page.Replace }, onConnection = { page = Page.Connection })
                        }
                        Page.Rooms -> RoomPicker(
                            state,
                            onChoose = {
                                repository.selectRoom(it)
                                browsingDisplay = true
                                page = Page.Home
                            },
                            onVentilation = { page = Page.Ventilation },
                            rooms = state.home.visibleRooms,
                            panel = panel
                        )
                        Page.Controls -> ControlPicker(state, onChoose = { focusedControl = it; page = Page.Home }, panel = panel)
                        Page.Connection -> if (panel) PanelConnection(state, repository) else Connection(state, repository)
                        Page.Ventilation -> HomeVentilation(state, repository, panel)
                        else -> {
                            if (wide && state.home.visibleRooms.isNotEmpty()) {
                                Row(Modifier.weight(1f)) {
                                    RoomList(state, repository::selectRoom, { page = Page.Ventilation }, Modifier.width(280.dp).fillMaxHeight())
                                    VerticalDivider()
                                    if (room != null) {
                                        viewState.SaveableStateProvider("$owner:room:${room.id}") {
                                            RoomPager(state, repository, panel = false, modifier = Modifier.weight(1f))
                                        }
                                    } else {
                                        EmptyMessage(stringResource(R.string.choose_room_hint), Modifier.weight(1f))
                                    }
                                }
                            } else if (room == null) {
                                viewState.SaveableStateProvider("rooms") {
                                    if (panel) {
                                        RoomPicker(state, { repository.selectRoom(it); browsingDisplay = true }, { page = Page.Ventilation }, panel = true)
                                    } else {
                                        RoomGrid(state, repository::selectRoom, { page = Page.Ventilation }, columns = if (windowWidth >= 600.dp) 2 else 1)
                                    }
                                }
                            } else {
                                RoomPager(state, repository, panel = panel, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun AppToolbar(
    title: String,
    showBack: Boolean,
    onBack: () -> Unit,
    onSettings: (() -> Unit)?,
    compact: Boolean = false
) {
    val backDispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (compact) 68.dp else 56.dp)
            .padding(horizontal = if (compact) 10.dp else 6.dp, vertical = if (compact) 6.dp else 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 4.dp)
    ) {
        if (showBack) {
            if (compact) {
                FilledTonalIconButton(
                    onClick = { backDispatcher?.onBackPressed() ?: onBack() },
                    modifier = Modifier.width(76.dp).height(56.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        stringResource(R.string.back),
                        modifier = Modifier.size(30.dp)
                    )
                }
            } else {
                IconButton(onClick = { backDispatcher?.onBackPressed() ?: onBack() }, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        stringResource(R.string.back),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        } else {
            Icon(Icons.Outlined.Home, null, Modifier.padding(12.dp).size(24.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text(
            title,
            Modifier.weight(1f).padding(horizontal = 8.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleLarge
        )
        if (onSettings != null) {
            if (compact) {
                FilledTonalIconButton(
                    onClick = onSettings,
                    modifier = Modifier.width(68.dp).height(56.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Icon(
                        Icons.Outlined.Settings,
                        stringResource(R.string.settings),
                        modifier = Modifier.size(28.dp)
                    )
                }
            } else {
                IconButton(onSettings, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.Outlined.Settings,
                        stringResource(R.string.settings),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

@Composable private fun SignIn(
    state: AppState,
    repository: EntiaRepository,
    replacement: Boolean,
    onCancel: () -> Unit,
    onSuccess: () -> Unit
) {
    var username by remember { mutableStateOf(if (replacement) state.username.orEmpty() else "") }
    // Deliberately never save or restore passwords across activity/process recreation.
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    LaunchedEffect(state.signingIn) {
        if (submitted && !state.signingIn && state.loginError == null && !state.needsSignIn) {
            password = ""
            onSuccess()
        }
    }
    val submit = {
        submitted = true
        repository.signIn(username, password)
        password = ""
    }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val passwordFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val usernameInput: @Composable (Modifier) -> Unit = { modifier ->
        OutlinedTextField(
            username,
            { username = it },
            modifier.semantics { contentType = ContentType.Username },
            enabled = !state.signingIn,
            label = { Text(stringResource(R.string.username), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() })
        )
    }
    val passwordInput: @Composable (Modifier) -> Unit = { modifier ->
        OutlinedTextField(
            password,
            { password = it },
            modifier.focusRequester(passwordFocus).semantics { contentType = ContentType.Password },
            enabled = !state.signingIn,
            label = { Text(stringResource(R.string.password), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                keyboard?.hide()
                if (!state.signingIn) submit()
            }),
            trailingIcon = {
                IconButton({ visible = !visible }) {
                    Icon(
                        if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        stringResource(if (visible) R.string.hide_password else R.string.show_password)
                    )
                }
            }
        )
    }
    val primary: @Composable (Modifier) -> Unit = { modifier ->
        Button(
            {
                keyboard?.hide()
                submit()
            },
            modifier.heightIn(min = 48.dp),
            enabled = !state.signingIn
        ) {
            if (state.signingIn) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(stringResource(if (state.signingIn) R.string.signing_in else R.string.sign_in), textAlign = TextAlign.Center)
        }
    }
    val secondaryAction: (() -> Unit)? = if (replacement) onCancel else if (state.username == null) ({
        keyboard?.hide()
        repository.demo()
    }) else null
    val secondary: @Composable (Modifier) -> Unit = { modifier ->
        if (secondaryAction != null) {
            OutlinedButton(secondaryAction, modifier.heightIn(min = 48.dp), enabled = !state.signingIn) {
                Text(stringResource(if (replacement) R.string.cancel else R.string.explore_demo), textAlign = TextAlign.Center)
            }
        }
    }
    val window = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current.density
    val windowWidthDp = window.width / density
    val windowHeightDp = window.height / density
    val compact = windowHeightDp <= 480f || windowWidthDp < 360f
    val wideLandscape = compact && windowWidthDp >= 440f && window.width > window.height * 1.15f
    Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.TopCenter) {
        if (compact) {
            val stepped = windowWidthDp < 280f && windowHeightDp < 280f
            var passwordStep by remember { mutableStateOf(false) }
            var information by remember { mutableStateOf(false) }
            LaunchedEffect(passwordStep) { if (passwordStep) passwordFocus.requestFocus() }
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
            ) {
                Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (stepped && passwordStep) {
                        IconButton({ passwordStep = false }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
                    }
                    Text(
                        stringResource(if (replacement) R.string.replace_credentials else R.string.welcome),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    IconButton({ information = true }) { Icon(Icons.Outlined.Info, stringResource(R.string.details)) }
                }
                if (wideLandscape) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        usernameInput(Modifier.weight(1f))
                        passwordInput(Modifier.weight(1f))
                    }
                } else if (stepped) {
                    if (passwordStep) passwordInput(Modifier.fillMaxWidth()) else usernameInput(Modifier.fillMaxWidth())
                } else {
                    usernameInput(Modifier.fillMaxWidth())
                    passwordInput(Modifier.fillMaxWidth())
                }
                state.loginError?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (stepped && !passwordStep) {
                        Button({ passwordStep = true }, Modifier.weight(1f).heightIn(min = 48.dp), enabled = !state.signingIn) {
                            Text(stringResource(R.string.next))
                        }
                    } else {
                        primary(Modifier.weight(1f))
                    }
                    if (secondaryAction != null) secondary(Modifier.weight(1f))
                }
            }
            if (information) {
                AlertDialog(
                    { information = false },
                    confirmButton = { TextButton({ information = false }) { Text(stringResource(R.string.close_controls)) } },
                    text = { Text(stringResource(R.string.sign_in_description) + "\n\n" + stringResource(R.string.secure_sign_in)) }
                )
            }
            return@Box
        }
        Column(
            Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(Icons.Outlined.Home, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            Text(
                stringResource(if (replacement) R.string.replace_credentials else R.string.welcome),
                style = MaterialTheme.typography.headlineLarge
            )
            Text(
                stringResource(R.string.sign_in_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            usernameInput(Modifier.fillMaxWidth())
            passwordInput(Modifier.fillMaxWidth())
            state.loginError?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
            primary(Modifier.fillMaxWidth())
            secondary(Modifier.fillMaxWidth())
            Text(
                stringResource(R.string.secure_sign_in),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.needsSignIn) {
                TextButton(repository::logout, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.forget_account))
                }
            }
        }
    }
}

@Composable private fun RoomGrid(state: AppState, onChoose: (Long?) -> Unit, onVentilation: () -> Unit, columns: Int) {
    var floor by rememberSaveable { mutableStateOf<String?>(null) }
    val rooms = state.home.visibleRooms
    if (rooms.isEmpty() && state.home.ventilationUnits.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            if (state.syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
            EmptyMessage(stringResource(if (state.syncing) R.string.loading_home else R.string.no_rooms), Modifier.weight(1f))
        }
        return
    }
    val floors = rooms.map { it.floor }.distinct()
    val selectedFloor = floor?.takeIf { it in floors }
    LazyVerticalGrid(
        GridCells.Fixed(columns),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (state.home.ventilationUnits.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                VentilationEntry(state, onVentilation)
            }
        }
        if (rooms.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(stringResource(R.string.your_rooms), style = MaterialTheme.typography.headlineMedium)
            }
        }
        if (floors.size > 1) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(selectedFloor == null, { floor = null }, label = { Text(stringResource(R.string.all_floors)) }) }
                    items(floors) { label -> FilterChip(selectedFloor == label, { floor = label }, label = { Text(label) }) }
                }
            }
        }
        items(rooms.filter { selectedFloor == null || it.floor == selectedFloor }, key = { it.id }) { room ->
            RoomTile(room, state, { onChoose(room.id) })
        }
    }
}

@Composable private fun RoomTile(room: Room, state: AppState, onChoose: () -> Unit) {
    val home = state.home
    val devices = home.visibleDevicesIn(room.id)
    val temperature = devices.firstNotNullOfOrNull { it.temperature(Attr.INSIDE) ?: it.temperature(Attr.TEMPERATURE) }
    val lightsOn = devices.count { d ->
        d.isLight && resolveLightState(home, state.preferences, state.hue, d).on
    }
    Card(
        onChoose,
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.MeetingRoom, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
                temperature?.let {
                    Text(stringResource(R.string.celsius, it), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge)
                }
            }
            Text(room.name, style = MaterialTheme.typography.titleMedium)
            Text(room.floor, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            Text(
                stringResource(R.string.room_summary, devices.size, lightsOn),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable private fun RoomList(state: AppState, onChoose: (Long?) -> Unit, onVentilation: () -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.home.ventilationUnits.isNotEmpty()) item { VentilationEntry(state, onVentilation) }
        items(state.home.visibleRooms, key = { it.id }) { room ->
            FilledTonalButton(
                { onChoose(room.id) },
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = if (room.id == state.selectedRoom) ButtonDefaults.filledTonalButtonColors()
                else ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(room.name, style = MaterialTheme.typography.titleMedium)
                    Text(room.floor, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable private fun RoomPicker(
    state: AppState,
    onChoose: (Long) -> Unit,
    onVentilation: (() -> Unit)? = null,
    rooms: List<Room> = state.home.visibleRooms,
    panel: Boolean = false
) {
    if (rooms.isEmpty() && (onVentilation == null || state.home.ventilationUnits.isEmpty())) {
        EmptyMessage(stringResource(R.string.no_rooms))
        return
    }
    if (panel) {
        val entries = buildList {
            if (onVentilation != null && state.home.ventilationUnits.isNotEmpty()) {
                add("ventilation" to stringResource(R.string.ventilation))
            }
            rooms.forEach { add(it.id.toString() to it.name) }
        }
        PanelMenu(entries, { if (it == "ventilation") onVentilation?.invoke() else onChoose(it.toLong()) })
        return
    }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onVentilation != null && state.home.ventilationUnits.isNotEmpty()) item { VentilationEntry(state, onVentilation) }
        items(rooms, key = { it.id }) { room ->
            OutlinedButton({ onChoose(room.id) }, Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    Text(room.name)
                    Text(room.floor, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

internal data class Control(val device: Device, val kind: Int) { val key get() = "${device.id}:$kind" }
internal fun controls(home: Home, room: Long?): List<Control> = home.devices.values.filter { it.roomId == room }.flatMap { d ->
    d.roomControlKinds.map { Control(d, it) }
}.sortedBy {
    when (it.kind) {
        Attr.TARGET -> 0
        Attr.TEMPERATURE, Attr.INSIDE, Attr.OUTSIDE -> 1
        Attr.POSITION -> 2
        else -> 3
    }
}

@Composable private fun ControlPicker(state: AppState, onChoose: (String) -> Unit, panel: Boolean = false) {
    if (panel) {
        PanelMenu(controls(state.home, state.selectedRoom).map { it.key to controlDisplayName(it, state) }, onChoose)
        return
    }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(controls(state.home, state.selectedRoom), key = { it.key }) { control ->
            OutlinedButton({ onChoose(control.key) }, Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Icon(controlIcon(control), null)
                Spacer(Modifier.width(12.dp))
                Text(controlDisplayName(control, state), Modifier.weight(1f))
                Text(controlLabel(control.kind), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable private fun RoomPager(
    state: AppState,
    repository: EntiaRepository,
    panel: Boolean,
    modifier: Modifier = Modifier
) {
    val rooms = state.home.visibleRooms
    if (rooms.isEmpty()) {
        EmptyMessage(stringResource(R.string.no_rooms), modifier)
        return
    }

    val initialIndex = maxOf(0, rooms.indexOfFirst { it.id == state.selectedRoom })
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { rooms.size })
    var draggingControl by remember { mutableStateOf(false) }

    LaunchedEffect(pagerState.currentPage, rooms) {
        val currentRoom = rooms.getOrNull(pagerState.currentPage) ?: return@LaunchedEffect
        if (state.selectedRoom != currentRoom.id) {
            repository.selectRoom(currentRoom.id)
        }
    }
    LaunchedEffect(state.selectedRoom, rooms) {
        val targetIndex = rooms.indexOfFirst { it.id == state.selectedRoom }
        if (targetIndex >= 0 && pagerState.currentPage != targetIndex) {
            pagerState.animateScrollToPage(targetIndex)
        }
    }

    Column(modifier.fillMaxSize()) {
        if (!panel && rooms.size > 1) {
            val currentTab = pagerState.currentPage.coerceIn(0, rooms.lastIndex)
            if (rooms.size > 4) {
                PrimaryScrollableTabRow(selectedTabIndex = currentTab, edgePadding = 12.dp) {
                    rooms.forEachIndexed { index, room ->
                        val selected = currentTab == index
                        Tab(
                            selected = selected,
                            onClick = { repository.selectRoom(room.id) },
                            modifier = Modifier.heightIn(min = 44.dp)
                        ) {
                            Text(
                                room.name,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            } else {
                PrimaryTabRow(selectedTabIndex = currentTab) {
                    rooms.forEachIndexed { index, room ->
                        val selected = currentTab == index
                        Tab(
                            selected = selected,
                            onClick = { repository.selectRoom(room.id) },
                            modifier = Modifier.heightIn(min = 44.dp)
                        ) {
                            Text(
                                room.name,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }
        }

        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !draggingControl,
            modifier = Modifier.weight(1f).testTag("room_pager")
        ) { pageIndex ->
            RoomControls(
                state = state,
                repository = repository,
                panel = panel,
                roomId = rooms[pageIndex].id,
                onDragStateChange = { draggingControl = it },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable private fun RoomControls(
    state: AppState,
    repository: EntiaRepository,
    panel: Boolean,
    roomId: Long,
    onDragStateChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val all = controls(state.home, roomId)
    if (all.isEmpty()) {
        EmptyMessage(stringResource(R.string.empty_room), modifier)
        return
    }

    val lights = all.filter { it.kind == Attr.LIGHT }
    val shades = all.filter { it.kind == Attr.POSITION }
    val targetControls = all.filter { it.kind == Attr.TARGET }
    val sensorControls = all.filter { it.kind == Attr.TEMPERATURE || it.kind == Attr.INSIDE || it.kind == Attr.OUTSIDE }
    val vent = all.filter { it.kind == Attr.HRV_SPEED }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val containerHeight = maxHeight
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = containerHeight)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (panel) 14.dp else 16.dp, vertical = if (panel) 10.dp else 14.dp),
            verticalArrangement = if (panel) Arrangement.SpaceEvenly else Arrangement.spacedBy(14.dp)
        ) {
            if (lights.isNotEmpty()) {
                val spacing = if (panel) 10.dp else 12.dp
                Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
                    lights.chunked(2).forEach { rowLights ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing)) {
                            rowLights.forEach { light ->
                                Box(modifier = Modifier.weight(1f)) {
                                    DeviceControl(light, state, repository, panel)
                                }
                            }
                            if (rowLights.size == 1 && lights.size > 1) Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            if (lights.isNotEmpty() && shades.isNotEmpty()) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = if (panel) 2.dp else 4.dp),
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                )
            }

            if (shades.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(if (panel) 8.dp else 10.dp)) {
                    shades.forEach { shade ->
                        ShadeControlTile(shade, state, repository, panel, onDragStateChange = onDragStateChange)
                    }
                }
            }

            targetControls.forEach { targetControl ->
                TemperatureControlStrip(targetControl, state, repository, panel)
            }

            val showSensors = targetControls.isEmpty()
            sensorControls.forEach { sensor ->
                if (showSensors || sensor.kind == Attr.OUTSIDE) {
                    TemperatureSensorTile(sensor, state, repository, panel)
                }
            }

            vent.forEach { v ->
                DeviceControl(v, state, repository, panel)
            }
        }
    }
}

@Composable internal fun EmptyMessage(message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable private fun Settings(
    state: AppState,
    repository: EntiaRepository,
    onReplace: () -> Unit,
    onConnection: () -> Unit
) {
    var choosingStartup by rememberSaveable { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    var showingHidden by rememberSaveable { mutableStateOf(false) }

    BackHandler(showingHidden || choosingStartup) {
        showingHidden = false
        choosingStartup = false
    }

    if (showingHidden) {
        Column(Modifier.fillMaxSize()) {
            TextButton({ showingHidden = false }, Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.back))
            }
            if (state.preferences.hiddenDevices.isEmpty()) {
                EmptyMessage(stringResource(R.string.no_hidden_devices))
            } else {
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(state.preferences.hiddenDevices.sorted(), key = { it }) { id ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                state.home.devices[id]?.name ?: stringResource(R.string.text_device_fallback, id),
                                Modifier.weight(1f)
                            )
                            OutlinedButton({ repository.hideDevice(id, false) }, Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.show_device))
                            }
                        }
                    }
                }
            }
        }
        return
    }
    if (choosingStartup) {
        Column {
            TextButton(
                {
                    repository.updatePreferences { it.copy(startupRoom = null) }
                    choosingStartup = false
                },
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.restore_last_room))
            }
            RoomPicker(
                state,
                onChoose = { room ->
                    repository.updatePreferences { it.copy(startupRoom = room) }
                    choosingStartup = false
                },
                rooms = state.home.visibleRooms
            )
        }
        return
    }
    LazyColumn(
        Modifier.widthIn(max = 720.dp).fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Text(state.username.orEmpty(), style = MaterialTheme.typography.titleLarge) }
        if (!state.demo) {
            item { OutlinedButton(onReplace, Modifier.fillMaxWidth()) { Text(stringResource(R.string.replace_credentials)) } }
        }
        item { Text(stringResource(R.string.theme), style = MaterialTheme.typography.titleMedium) }
        item {
            val themes = listOf(
                "dark" to stringResource(R.string.dark),
                "light" to stringResource(R.string.light),
                "system" to stringResource(R.string.system)
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                themes.forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = state.preferences.theme == value,
                        onClick = { repository.updatePreferences { it.copy(theme = value) } },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = themes.size)
                    ) {
                        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        item { Text(stringResource(R.string.language), style = MaterialTheme.typography.titleMedium) }
        item {
            val languages = listOf(
                "system" to stringResource(R.string.language_system),
                "en" to stringResource(R.string.language_english),
                "sl" to stringResource(R.string.language_slovenian)
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                languages.forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = state.preferences.language == value,
                        onClick = { repository.setLanguage(value) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = languages.size)
                    ) {
                        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.dynamic_colors), Modifier.weight(1f))
                Switch(state.preferences.dynamicColor, { value -> repository.updatePreferences { it.copy(dynamicColor = value) } })
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.hide_cooling), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.hide_cooling_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(state.preferences.hideCooling, { value -> repository.updatePreferences { it.copy(hideCooling = value) } })
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.show_heating_rooms), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.show_heating_rooms_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(state.preferences.showHeatingRooms, { value -> repository.updatePreferences { it.copy(showHeatingRooms = value) } })
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.child_lock), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.child_lock_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(state.preferences.childLock, { value -> repository.updatePreferences { it.copy(childLock = value) } })
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.philips_hue), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.philips_hue_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    state.preferences.hueEnabled,
                    repository::setHueEnabled,
                    Modifier.testTag("hue_enabled_switch")
                )
            }
        }
        if (state.preferences.hueEnabled) {
            item { HueBridgeSettingsCard(state, repository) }
        }
        item {
            OutlinedButton(
                { choosingStartup = true },
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                    Text(stringResource(R.string.startup_room))
                    Text(
                        state.home.visibleRooms.firstOrNull { it.id == state.preferences.startupRoom }?.name
                            ?: stringResource(R.string.restore_last_room),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { Text(stringResource(R.string.version, BuildConfig.VERSION_NAME), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { OutlinedButton({ showingHidden = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.hidden_devices)) } }
        item { OutlinedButton(onConnection, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.connection)) } }
        item {
            if (confirmLogout) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.logout_description))
                    Button({ repository.logout() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.forget_account)) }
                    TextButton({ confirmLogout = false }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
                }
            } else {
                OutlinedButton({ if (state.demo) repository.logout() else confirmLogout = true }, Modifier.fillMaxWidth()) {
                    Text(stringResource(if (state.demo) R.string.leave_demo else R.string.log_out))
                }
            }
        }
    }
}

@Composable private fun Connection(state: AppState, repository: EntiaRepository) {
    val locale = LocalConfiguration.current.locales[0]
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(statusText(state), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.socket_status, stringResource(if (state.socketConnected) R.string.connected else R.string.disconnected)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            stringResource(
                R.string.controller_status,
                stringResource(
                    when (state.home.controllerConnected) {
                        true -> R.string.connected
                        false -> R.string.disconnected
                        null -> R.string.unknown
                    }
                )
            )
        )
        Text(
            stringResource(
                R.string.last_sync,
                if (state.home.syncedAt == 0L) stringResource(R.string.never)
                else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(state.home.syncedAt))
            )
        )
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text(stringResource(R.string.connection_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(
            repository::refresh,
            enabled = !state.syncing && !state.demo,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        ) {
            Text(stringResource(if (state.syncing) R.string.refreshing else R.string.refresh))
        }
    }
}

@Composable internal fun statusText(state: AppState) = stringResource(
    when {
        state.demo -> R.string.demo_home
        state.syncing -> R.string.refreshing
        !state.foreground -> R.string.paused
        !state.restReachable -> R.string.offline
        else -> R.string.readings_available
    }
)

internal fun controlIcon(kind: Int): ImageVector = when (kind) {
    Attr.LIGHT -> Icons.Outlined.Lightbulb
    Attr.POSITION -> Icons.Outlined.Blinds
    Attr.TARGET -> Icons.Outlined.Thermostat
    Attr.HRV_SPEED -> Icons.Outlined.Air
    Attr.TEMPERATURE, Attr.INSIDE, Attr.OUTSIDE -> Icons.Outlined.DeviceThermostat
    else -> Icons.Outlined.Sensors
}
internal fun controlIcon(control: Control): ImageVector =
    if (control.kind == Attr.LIGHT) lightGlyph(control.device.lightIcon) else controlIcon(control.kind)
@Composable internal fun controlLabel(kind: Int) = stringResource(
    when (kind) {
        Attr.LIGHT -> R.string.lights
        Attr.POSITION -> R.string.blinds
        Attr.TARGET -> R.string.climate
        Attr.HRV_SPEED -> R.string.ventilation
        Attr.TEMPERATURE, Attr.INSIDE -> R.string.temperature
        Attr.OUTSIDE -> R.string.outdoor_temperature
        else -> R.string.device
    }
)
