package si.hafnar.entalt.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import si.hafnar.entalt.data.AppState
import si.hafnar.entalt.data.Preferences
import si.hafnar.entalt.data.demoHome

@Preview(name = "1:1 wall display 480 × 480", widthDp = 320, heightDp = 320)
@Composable private fun SmallRoomDisplayPreview() {
    val preferences = Preferences(layout = "display", disclaimerAccepted = true)
    EntAltTheme(preferences) {
        RoomDisplay(
            AppState(
                restoring = false,
                username = "Demo",
                demo = true,
                home = demoHome(),
                preferences = preferences,
                foreground = true,
                restReachable = true
            ),
            onSettings = {}
        )
    }
}
