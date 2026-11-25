package si.hafnar.entalt.domain

import java.util.Locale

/** Localizable application text shared by the Android-free parser and repository. */
enum class TextKey(val english: String) {
    DisclaimerUnsaved("Your agreement could not be saved. Check device storage and try again."),
    SavedAccountUnreadable("The saved account could not be opened. Please sign in again."),
    CredentialsRequired("Enter your username and password."),
    AccountPreferencesUnsaved("Account saved. Preferences could not be saved on this device."),
    CredentialsRejected("The username or password was rejected."),
    SignInFailed("Sign-in could not finish. Check your connection and device storage, then retry."),
    RoomsRefreshFailed("Room information could not refresh. Showing the last known rooms."),
    RefreshFailed("Could not refresh Entia. Last known readings are shown; check your connection and retry."),
    InvalidResponse("Entia returned data that could not be read. Please refresh again."),
    ControlRemoved("This control is no longer available. Refresh the room before retrying."),
    SessionRejected("Your session was rejected. Please sign in again."),
    TargetUnobserved("Target not observed. Refresh before retrying; it may still have been applied."),
    OutcomeUnknown("The outcome is unknown. Refresh before retrying; the action was not replayed."),
    PermissionDenied("This account does not have permission for that action."),
    RateLimited("Entia is receiving too many requests. Please wait and retry."),
    RequestFailed("Entia could not complete the request (HTTP %1\$d)."),
    PreferencesUnsaved("Preferences could not be saved on this device."),
    AccountRemovalFailed("The saved account could not be removed. Clear EntAlt's app storage in Android Settings to forget it."),
    FloorFallback("Floor %1\$d"),
    RoomFallback("Room %1\$d"),
    DeviceFallback("Device %1\$d"),
    UnassignedDevices("Unassigned devices"),
    Home("Home"),
    DemoHome("Demo home"),
    PendantLights("Pendant lights"),
    GardenBlinds("Garden blinds"),
    RoomClimate("Room climate"),
    HeatingValve("Heating valve"),
    FreshAir("Fresh air"),
    Temperature("Temperature"),
    OutsideSensor("Outside sensor"),
    CounterLights("Counter lights"),
    BedsideLamps("Bedside lamps"),
    BlackoutBlinds("Blackout blinds"),
    DeskLight("Desk light"),
    CeilingLight("Ceiling light"),
    LivingRoom("Living room"),
    GroundFloor("Ground floor"),
    Kitchen("Kitchen"),
    Bedroom("Bedroom"),
    FirstFloor("First floor"),
    Study("Study"),
    GuestRoom("Guest room"),
    HueDemoPendant("Hue Pendant Spot"),
    HueDemoFloor("Hue Floor Lamp"),
    HueDemoStrip("Hue Kitchen Strip"),
    HueDemoBedside("Hue Bedside Bulb"),
    HuePairingTimeout("Link button was not pressed in time. Press the button on top of the Hue Bridge and try again."),
    HueInvalidBridgeIp("Enter a valid Hue Bridge IP address."),
    HueSyncFailed("Could not reach the Hue Bridge on the local network."),
    HueAuthRejected("Hue Bridge key was rejected. Pair the bridge again in Settings."),
    HueStandaloneDefaultName("Hue light"),
}

fun interface TextCatalog {
    fun get(key: TextKey, number: Long?): String
}

operator fun TextCatalog.invoke(key: TextKey, number: Long? = null): String = get(key, number)

val EnglishText = TextCatalog { key, number ->
    if (number == null) key.english else String.format(Locale.ROOT, key.english, number)
}
