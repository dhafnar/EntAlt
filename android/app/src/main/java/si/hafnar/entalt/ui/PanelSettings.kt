package si.hafnar.entalt.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import si.hafnar.entalt.R
import si.hafnar.entalt.data.*

@Composable internal fun PanelSettings(
    state: AppState,
    repository: EntiaRepository,
    onReplace: () -> Unit,
    onConnection: () -> Unit
) {
    var section by rememberSaveable { mutableStateOf("main") }
    val stepBack = {
        section = when (section) {
            "displayRoom", "startupRoom" -> "display"
            "theme" -> "appearance"
            "logout" -> "account"
            else -> "main"
        }
    }
    BackHandler(section != "main") { stepBack() }
    if (section == "hue") {
        PanelHueSettings(state, repository)
        return
    }
    val back = "back" to stringResource(R.string.back)
    val prefs = state.preferences
    val entries = when (section) {
        "display" -> listOf(
            back,
            "displayRoom" to stringResource(R.string.display_room),
            "startupRoom" to stringResource(R.string.startup_room),
            "heatingRooms" to stringResource(R.string.show_heating_rooms),
            "childLock" to stringResource(R.string.child_lock)
        )
        "appearance" -> listOf(
            back,
            "theme" to stringResource(R.string.theme),
            "dynamic" to stringResource(R.string.dynamic_colors)
        )
        "theme" -> listOf(
            back,
            "dark" to stringResource(R.string.dark),
            "light" to stringResource(R.string.light),
            "system" to stringResource(R.string.system)
        )
        "language" -> listOf(
            back,
            "system" to stringResource(R.string.language_system),
            "en" to stringResource(R.string.language_english),
            "sl" to stringResource(R.string.language_slovenian)
        )
        "cooling" -> listOf(
            back,
            "false" to stringResource(R.string.show_cooling_mode),
            "true" to stringResource(R.string.hide_cooling_mode)
        )
        "hidden" -> listOf(back) + prefs.hiddenDevices.map { it.toString() to (state.home.devices[it]?.name ?: it.toString()) }
        "displayRoom", "startupRoom" -> listOf(back, "automatic" to stringResource(R.string.automatic)) +
            (if (section == "displayRoom") state.home.displayRooms else state.home.visibleRooms).map { it.id.toString() to it.name }
        "account" -> buildList {
            add(back)
            if (!state.demo) add("replace" to stringResource(R.string.replace_credentials))
            add("logout" to stringResource(if (state.demo) R.string.leave_demo else R.string.log_out))
        }
        "logout" -> listOf(back, "confirm" to stringResource(R.string.forget_account))
        else -> listOf(
            "display" to stringResource(R.string.display_settings),
            "appearance" to stringResource(R.string.theme),
            "language" to stringResource(R.string.language),
            "cooling" to stringResource(R.string.cooling_controls),
            "hue" to stringResource(R.string.philips_hue),
            "hidden" to stringResource(R.string.hidden_devices),
            "account" to stringResource(R.string.account_settings),
            "connection" to stringResource(R.string.connection)
        )
    }
    val selected = when (section) {
        "theme" -> prefs.theme
        "language" -> prefs.language
        "cooling" -> prefs.hideCooling.toString()
        "displayRoom" -> prefs.displayRoom?.toString() ?: "automatic"
        "startupRoom" -> prefs.startupRoom?.toString() ?: "automatic"
        else -> null
    }
    val icons = buildMap {
        if (section == "appearance" && prefs.dynamicColor) put("dynamic", Icons.Outlined.Check)
        if (section == "display") {
            if (prefs.showHeatingRooms) put("heatingRooms", Icons.Outlined.Check)
            if (prefs.childLock) put("childLock", Icons.Outlined.Check)
        }
    }
    PanelMenu(entries, { value ->
        if (value == "back") {
            stepBack()
        } else when (section) {
            "theme" -> { repository.updatePreferences { it.copy(theme = value) }; section = "appearance" }
            "language" -> { repository.setLanguage(value); section = "main" }
            "cooling" -> { repository.updatePreferences { it.copy(hideCooling = value == "true") }; section = "main" }
            "hidden" -> repository.hideDevice(value.toLong(), false)
            "display" -> when (value) {
                "heatingRooms" -> repository.updatePreferences { it.copy(showHeatingRooms = !it.showHeatingRooms) }
                "childLock" -> repository.updatePreferences { it.copy(childLock = !it.childLock) }
                else -> section = value
            }
            "displayRoom" -> { repository.updatePreferences { it.copy(displayRoom = value.toLongOrNull()) }; section = "display" }
            "startupRoom" -> { repository.updatePreferences { it.copy(startupRoom = value.toLongOrNull()) }; section = "display" }
            "appearance" -> if (value == "dynamic") repository.updatePreferences { it.copy(dynamicColor = !it.dynamicColor) } else section = value
            "account" -> when (value) { "replace" -> onReplace(); "logout" -> if (state.demo) repository.logout() else section = "logout" }
            "logout" -> repository.logout()
            else -> if (value == "connection") onConnection() else section = value
        }
    }, selected = selected, icons = icons)
}

@Composable internal fun PanelConnection(state: AppState, repository: EntiaRepository) {
    val locale = LocalConfiguration.current.locales[0]
    var details by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(statusText(state), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(
                    R.string.last_sync,
                    if (state.home.syncedAt == 0L) stringResource(R.string.never)
                    else DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(Date(state.home.syncedAt))
                ),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            state.error?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton({ details = true }, Modifier.weight(1f).heightIn(min = 56.dp)) {
                Text(stringResource(R.string.details), style = MaterialTheme.typography.titleMedium)
            }
            Button(repository::refresh, Modifier.weight(1f).heightIn(min = 56.dp), enabled = !state.syncing && !state.demo) {
                Text(stringResource(R.string.refresh), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
    if (details) {
        PanelChoiceDialog(
            stringResource(R.string.connection),
            listOf(
                "socket" to stringResource(R.string.socket_status, stringResource(if (state.socketConnected) R.string.connected else R.string.disconnected)),
                "controller" to stringResource(
                    R.string.controller_status,
                    stringResource(
                        when (state.home.controllerConnected) {
                            true -> R.string.connected
                            false -> R.string.disconnected
                            null -> R.string.unknown
                        }
                    )
                )
            ),
            null,
            true,
            { details = false }
        ) { details = false }
    }
}
