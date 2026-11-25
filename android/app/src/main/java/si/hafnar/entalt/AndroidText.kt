package si.hafnar.entalt

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import si.hafnar.entalt.domain.TextCatalog
import si.hafnar.entalt.domain.TextKey

fun normalizeAppLanguage(language: String?): String = when (language) {
    "en", "sl" -> language
    else -> "system"
}

fun resolveAppLocale(languagePref: String, baseConfig: Configuration): Locale = when (normalizeAppLanguage(languagePref)) {
    "en" -> Locale.ENGLISH
    "sl" -> Locale.forLanguageTag("sl")
    else -> baseConfig.locales[0] ?: Locale.getDefault()
}

fun Context.withAppLanguage(languagePref: String): Context {
    val normalized = normalizeAppLanguage(languagePref)
    if (normalized == "system") return this
    val locale = resolveAppLocale(normalized, resources.configuration)
    val config = Configuration(resources.configuration).apply {
        setLocale(locale)
        setLocales(LocaleList(locale))
    }
    return createConfigurationContext(config)
}

/** Resolve against current resources and manual app language preference. */
fun androidText(context: Context, languageProvider: () -> String = { "system" }): TextCatalog {
    val cached = AtomicReference<Pair<String, Context>?>(null)
    return TextCatalog { key, number ->
        val requested = normalizeAppLanguage(languageProvider())
        val activeContext = if (requested == "system") {
            context
        } else {
            val current = cached.get()
            if (current != null && current.first == requested) {
                current.second
            } else {
                context.withAppLanguage(requested).also { cached.set(requested to it) }
            }
        }
        val id = when (key) {
            TextKey.DisclaimerUnsaved -> R.string.disclaimer_save_failed
            TextKey.SavedAccountUnreadable -> R.string.text_saved_account_unreadable
            TextKey.CredentialsRequired -> R.string.text_credentials_required
            TextKey.AccountPreferencesUnsaved -> R.string.text_account_preferences_unsaved
            TextKey.CredentialsRejected -> R.string.text_credentials_rejected
            TextKey.SignInFailed -> R.string.text_sign_in_failed
            TextKey.RoomsRefreshFailed -> R.string.text_rooms_refresh_failed
            TextKey.RefreshFailed -> R.string.text_refresh_failed
            TextKey.InvalidResponse -> R.string.text_invalid_response
            TextKey.ControlRemoved -> R.string.text_control_removed
            TextKey.SessionRejected -> R.string.text_session_rejected
            TextKey.TargetUnobserved -> R.string.text_target_unobserved
            TextKey.OutcomeUnknown -> R.string.text_outcome_unknown
            TextKey.PermissionDenied -> R.string.text_permission_denied
            TextKey.RateLimited -> R.string.text_rate_limited
            TextKey.RequestFailed -> R.string.text_request_failed
            TextKey.PreferencesUnsaved -> R.string.text_preferences_unsaved
            TextKey.AccountRemovalFailed -> R.string.text_account_removal_failed
            TextKey.FloorFallback -> R.string.text_floor_fallback
            TextKey.RoomFallback -> R.string.text_room_fallback
            TextKey.DeviceFallback -> R.string.text_device_fallback
            TextKey.UnassignedDevices -> R.string.text_unassigned_devices
            TextKey.Home -> R.string.text_home
            TextKey.DemoHome -> R.string.text_demo_home
            TextKey.PendantLights -> R.string.text_pendant_lights
            TextKey.GardenBlinds -> R.string.text_garden_blinds
            TextKey.RoomClimate -> R.string.text_room_climate
            TextKey.HeatingValve -> R.string.text_heating_valve
            TextKey.FreshAir -> R.string.text_fresh_air
            TextKey.Temperature -> R.string.text_temperature
            TextKey.OutsideSensor -> R.string.text_outside_sensor
            TextKey.CounterLights -> R.string.text_counter_lights
            TextKey.BedsideLamps -> R.string.text_bedside_lamps
            TextKey.BlackoutBlinds -> R.string.text_blackout_blinds
            TextKey.DeskLight -> R.string.text_desk_light
            TextKey.CeilingLight -> R.string.text_ceiling_light
            TextKey.LivingRoom -> R.string.text_living_room
            TextKey.GroundFloor -> R.string.text_ground_floor
            TextKey.Kitchen -> R.string.text_kitchen
            TextKey.Bedroom -> R.string.text_bedroom
            TextKey.FirstFloor -> R.string.text_first_floor
            TextKey.Study -> R.string.text_study
            TextKey.GuestRoom -> R.string.text_guest_room
            TextKey.HueDemoPendant -> R.string.text_hue_demo_pendant
            TextKey.HueDemoFloor -> R.string.text_hue_demo_floor
            TextKey.HueDemoStrip -> R.string.text_hue_demo_strip
            TextKey.HueDemoBedside -> R.string.text_hue_demo_bedside
            TextKey.HuePairingTimeout -> R.string.text_hue_pairing_timeout
            TextKey.HueInvalidBridgeIp -> R.string.text_hue_invalid_ip
            TextKey.HueSyncFailed -> R.string.text_hue_sync_failed
            TextKey.HueAuthRejected -> R.string.text_hue_auth_rejected
            TextKey.HueStandaloneDefaultName -> R.string.text_hue_standalone_default
        }
        if (number == null) activeContext.getString(id) else activeContext.getString(id, number)
    }
}
