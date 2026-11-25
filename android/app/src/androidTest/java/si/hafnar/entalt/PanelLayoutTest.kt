@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package si.hafnar.entalt

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import kotlin.math.abs
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import si.hafnar.entalt.data.*
import si.hafnar.entalt.domain.Attr
import si.hafnar.entalt.ui.*

/** Synthetic in-memory app testing real 480x480 (1:1) panel and regular phone viewports, including keyboard entry. */
@RunWith(Parameterized::class)
class PanelLayoutTest(
    private val name: String,
    private val widthPx: Int,
    private val heightPx: Int,
    private val densityDpi: Int,
    private val font: Float,
    private val language: String
) {
    @get:Rule val ui = createAndroidComposeRule<PanelQaActivity>()
    private lateinit var repository: EntiaRepository
    private val context by lazy {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        target.createConfigurationContext(Configuration(target.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
        })
    }
    private fun text(id: Int, vararg values: Any) = context.getString(id, *values)
    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
            FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes() }
        }
    }
    private val isProfileA get() = widthPx > 0 && widthPx == heightPx

    @Before fun prepare() {
        shell("wm size ${widthPx}x${heightPx}")
        shell("wm density $densityDpi")
        shell("settings put system font_scale $font")
        shell("mkdir -p /data/local/tmp/entalt-panel-qa")
        ui.runOnUiThread {
            ui.activity.getSystemService(android.app.LocaleManager::class.java).applicationLocales =
                android.os.LocaleList.forLanguageTags(language)
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(ui.activity.window, false)
            androidx.core.view.WindowInsetsControllerCompat(ui.activity.window, ui.activity.window.decorView)
                .hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            repository = EntiaRepository(
                object : AccountStore {
                    override fun load(): Credentials? = null
                    override fun save(credentials: Credentials) = error("No credentials in layout tests")
                    override fun clear() = Unit
                },
                object : PreferenceStore {
                    override suspend fun load() = Preferences(layout = "auto", disclaimerAccepted = true)
                    override suspend fun save(preferences: Preferences) = Unit
                    override suspend fun clearAccount() = Unit
                },
                gateway = { error("No live network in layout tests") },
                text = androidText(context)
            )
            repository.setForeground(true)
        }
        ui.setContent {
            Box(Modifier.fillMaxSize().testTag("panel_window")) {
                val state by repository.state.collectAsState()
                EntAltTheme(state.preferences) {
                    EntAltApp(state, repository, isProfileA = isProfileA)
                }
            }
        }
        ui.waitUntil(5_000) { !repository.state.value.restoring }
        ui.waitForIdle()
        ui.waitUntil(10_000) {
            val config = ui.activity.resources.configuration
            abs(config.fontScale - font) < 0.02f && config.locales[0].language == language
        }
    }

    @After fun close() {
        ui.runOnUiThread {
            repository.close()
            ui.activity.getSystemService(android.app.LocaleManager::class.java).applicationLocales =
                android.os.LocaleList.getEmptyLocaleList()
        }
        shell("settings put system font_scale 1.0")
    }

    private fun button(id: Int, vararg values: Any) = ui.onAllNodes(hasText(text(id, *values)) and hasClickAction()).onFirst()
    private fun action(id: Int) = ui.onAllNodes(hasContentDescription(text(id)) and hasClickAction()).onLast()

    private fun fits(node: SemanticsNodeInteraction) {
        node.assertIsDisplayed()
        val root = ui.onNodeWithTag("panel_window").fetchSemanticsNode()
        val n = node.fetchSemanticsNode()
        assertTrue("$name clipped left/top: ${n.config}", n.positionInRoot.x >= root.positionInRoot.x - 2 && n.positionInRoot.y >= root.positionInRoot.y - 2)
        assertTrue("$name clipped right: ${n.config}", n.positionInRoot.x + n.size.width <= root.positionInRoot.x + root.size.width + 2)
        assertTrue("$name clipped bottom: ${n.config}", n.positionInRoot.y + n.size.height <= root.positionInRoot.y + root.size.height + 2)
    }

    private fun capture(screen: String, dialog: Boolean = false) {
        ui.waitForIdle()
        val bitmap = (if (dialog) ui.onAllNodes(isDialog()).onLast() else ui.onNodeWithTag("panel_window")).captureToImage().asAndroidBitmap()
        val directory = File(context.getExternalFilesDir(null), "panel-qa").apply { mkdirs() }
        val file = File(directory, "$name-$screen.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        shell("cp ${file.absolutePath} /data/local/tmp/entalt-panel-qa/")
    }

    @Test fun fullFlowAndKeyboardEntryWorkAndFitViewport() {
        // 1. Login and keyboard entry
        fits(button(R.string.sign_in))
        fits(button(R.string.explore_demo))
        capture("login")

        val usernameField = ui.onNode(hasText(text(R.string.username)) and hasSetTextAction())
        val passwordField = ui.onNode(hasText(text(R.string.password)) and hasSetTextAction())
        usernameField.performClick()
        usernameField.performTextInput("panel@example.com")
        usernameField.performImeAction()
        passwordField.assertIsFocused()
        passwordField.performTextInput("secret123")
        capture("login-typed")
        ui.runOnUiThread {
            ui.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(ui.activity.window.decorView.windowToken, 0)
        }
        ui.waitForIdle()

        // Enter Demo mode
        button(R.string.explore_demo).performScrollTo().performClick()
        ui.waitUntil(5_000) { repository.state.value.demo }
        ui.waitForIdle()

        if (isProfileA) {
            // 2. Clock face ONLY on 1:1 (480x480)
            ui.onNodeWithTag("clock_face").assertIsDisplayed()
            capture("clock")
            ui.onNodeWithTag("clock_face").performClick()
            fits(button(R.string.your_rooms))
            fits(button(R.string.ventilation))
            fits(button(R.string.settings))
            capture("clock-menu", dialog = true)
            button(R.string.your_rooms).performClick()
            ui.waitForIdle()
        } else {
            // Non-1:1 resolutions never show the clock face
            ui.onAllNodesWithTag("clock_face").assertCountEquals(0)
        }

        // 3. RoomPager in Living room
        ui.waitUntil(5_000) { repository.state.value.selectedRoom == 1L }
        ui.onAllNodes(hasText(text(R.string.data_attention))).assertCountEquals(0)
        if (!isProfileA) {
            ui.onNodeWithText(text(R.string.app_name)).assertIsDisplayed()
        }
        capture("living-room")

        // 4. Toggle light & test DeviceEditor keyboard entry + remove confirmation
        val lightPower = ui.onNodeWithContentDescription(text(R.string.device_power, text(R.string.text_pendant_lights)))
        fits(lightPower)
        lightPower.performClick()
        ui.waitUntil(5_000) { repository.state.value.home.devices.getValue(101).value(Attr.LIGHT) == 0 }

        lightPower.performTouchInput { longClick() }
        ui.waitForIdle()
        if (ui.onAllNodes(hasText(text(R.string.remove_local_device))).fetchSemanticsNodes().isNotEmpty()) {
            button(R.string.remove_local_device).let { if (isProfileA) it else it.performScrollTo() }.performClick()
            ui.waitForIdle()
            ui.onNodeWithText(text(R.string.confirm_remove_device_message, text(R.string.text_pendant_lights))).assertIsDisplayed()
            button(R.string.cancel).performClick()
            ui.waitForIdle()
            assertFalse(repository.state.value.home.devices.getValue(101).hidden)
        }
        if (ui.onAllNodes(hasText(text(R.string.device_name)) and !hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()) {
            button(R.string.device_name).performClick()
            ui.waitForIdle()
        }
        val nameInput = ui.onNode(hasSetTextAction())
        nameInput.performClick()
        nameInput.performTextReplacement("Dining lights")
        nameInput.performImeAction()
        ui.runOnUiThread {
            ui.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(ui.activity.window.decorView.windowToken, 0)
        }
        ui.waitForIdle()
        button(R.string.save).let { if (isProfileA) it else it.performScrollTo() }.performClick()
        ui.waitUntil(5_000) { repository.state.value.home.devices.getValue(101).name == "Dining lights" }

        // 5. Climate +/- and ValueEditor
        fits(action(R.string.increase))
        fits(action(R.string.decrease))
        action(R.string.increase).performClick()
        ui.waitUntil(5_000) { repository.state.value.home.devices.getValue(103).value(Attr.TARGET) == 45 }

        ui.onNode(hasText(text(R.string.panel_temperature, 22.5)) and hasClickAction()).performClick()
        capture("value-editor", dialog = true)
        fits(button(R.string.apply))
        action(R.string.increase).performClick()
        button(R.string.apply).performClick()
        ui.waitUntil(5_000) { repository.state.value.home.devices.getValue(103).value(Attr.TARGET) == 46 }

        // 5b. Simplified blind menu: +1/-1 stepper and two save slots
        assertEquals("Blind starts at 30% closed", 30, repository.state.value.home.devices.getValue(102).value(Attr.POSITION))
        button(R.string.text_garden_blinds).performClick()
        ui.waitForIdle()
        // Clicking the tile only opens the window; it must NOT move the blind
        assertEquals(30, repository.state.value.home.devices.getValue(102).value(Attr.POSITION))
        ui.onAllNodes(hasText(text(R.string.apply))).assertCountEquals(0)
        // Save current 30% to Slot 1
        button(R.string.save_slot, 1).performClick()
        ui.waitUntil(5_000) { repository.state.value.preferences.blindPresets[102L]?.slot1 == 30 }
        // + and - change the blind by 1% immediately
        action(R.string.increase).performClick()
        ui.waitUntil(5_000) { repository.state.value.home.devices.getValue(102).value(Attr.POSITION) == 31 }
        // Save 31% to Slot 2
        button(R.string.save_slot, 2).performClick()
        ui.waitUntil(5_000) { repository.state.value.preferences.blindPresets[102L]?.slot2 == 31 }
        // One click on Slot 1 sets blind back to 30%
        ui.onAllNodes(hasText(text(R.string.percent, 30)) and hasClickAction()).onLast().performClick()
        ui.waitUntil(5_000) { repository.state.value.home.devices.getValue(102).value(Attr.POSITION) == 30 }
        capture("blind-window", dialog = true)
        action(R.string.back).performClick()
        ui.waitForIdle()

        // 6. Switch to Kitchen via swiping (480x480) or tabs (phone) and verify read-only temperature sensor is visible
        if (isProfileA) {
            ui.onNodeWithTag("room_pager").performTouchInput { swipeLeft() }
        } else {
            ui.onAllNodes(hasText(text(R.string.text_kitchen)) and hasClickAction()).onFirst().performClick()
        }
        ui.waitUntil(5_000) { repository.state.value.selectedRoom == 2L }
        ui.onNodeWithText(text(R.string.celsius, 23.0)).assertIsDisplayed()
        capture("kitchen")

        // 7. Return navigation & Ventilation
        if (isProfileA) {
            // Back from room controls on 480x480 returns directly to Clock view
            action(R.string.back).performClick()
            ui.onNodeWithTag("clock_face").assertIsDisplayed()
            ui.onNodeWithTag("clock_face").performClick()
            button(R.string.settings).performClick()
            ui.waitForIdle()
            ui.onAllNodes(hasContentDescription(text(R.string.settings)) and hasClickAction()).assertCountEquals(0)
            capture("settings")
            action(R.string.back).performClick()
            ui.onNodeWithTag("clock_face").assertIsDisplayed()
            ui.onNodeWithTag("clock_face").performClick()
            button(R.string.ventilation).performClick()
            ui.waitForIdle()
            capture("ventilation")
            action(R.string.high).performClick()
            ui.waitUntil(5_000) { repository.state.value.home.devices.getValue(105).value(Attr.HRV_SPEED) == 3 }
            button(R.string.bypass).performClick()
            ui.waitUntil(5_000) { repository.state.value.home.devices.getValue(105).value(Attr.HRV_MODE) == 0 }
            action(R.string.back).performClick()
            ui.onNodeWithTag("clock_face").assertIsDisplayed()
            ui.onNodeWithTag("clock_face").performClick()
            button(R.string.settings).performClick()
            ui.waitForIdle()
            ui.onAllNodes(hasContentDescription(text(R.string.settings)) and hasClickAction()).assertCountEquals(0)
            capture("settings")

            // 8. Enable Philips Hue in PanelSettings, bind Dining lights (mains currently 0), test Smart Mains Guard & HueControlWindow
            button(R.string.philips_hue).performClick()
            ui.waitForIdle()
            ui.onNode(isToggleable()).performClick()
            ui.waitUntil(5_000) { repository.state.value.preferences.hueEnabled && repository.state.value.hue.paired }
            fits(button(R.string.hue_refresh))
            fits(button(R.string.hue_disconnect))
            capture("settings-hue")
            action(R.string.back).performClick()
            ui.waitForIdle()
            action(R.string.back).performClick()
            ui.onNodeWithTag("clock_face").assertIsDisplayed()

            ui.onNodeWithTag("clock_face").performClick()
            button(R.string.your_rooms).performClick()
            ui.waitUntil(5_000) { repository.state.value.selectedRoom == 1L }
            val diningPower = ui.onNodeWithContentDescription(text(R.string.device_power, "Dining lights"))
            diningPower.performTouchInput { longClick() }
            ui.waitForIdle()
            button(R.string.managed_by_hue).performClick()
            ui.waitForIdle()
            ui.onNode(isToggleable()).performClick()
            ui.waitForIdle()
            ui.onAllNodes(hasText(text(R.string.text_hue_demo_pendant), substring = true) and hasClickAction()).onFirst().performClick()
            ui.waitForIdle()
            button(R.string.save).performClick()
            ui.waitUntil(5_000) { repository.state.value.preferences.hueBindings[101L]?.targetId == "demo-light-pendant" }

            // Dining lights mains was 0 -> Smart Mains Guard restores mains (1) and turns Hue ON on tap
            fits(diningPower)
            val hueTune = ui.onNodeWithContentDescription(text(R.string.hue_controls, "Dining lights"))
            fits(hueTune)
            capture("living-room-hue")
            diningPower.performClick()
            ui.waitUntil(5_000) {
                repository.state.value.home.devices.getValue(101L).value(Attr.LIGHT) == 1 &&
                    repository.state.value.hue.targetFor(repository.state.value.preferences.hueBindings[101L])?.on == true
            }

            // Open HueControlWindow and verify Dimmer, White, Color tabs fit 480x480 without clipping
            hueTune.performClick()
            ui.waitForIdle()
            fits(button(R.string.hue_turn_off))
            fits(button(R.string.hue_mains_cut_action))
            capture("hue-window", dialog = true)

            button(R.string.hue_tab_white).performClick()
            ui.waitForIdle()
            fits(button(R.string.hue_temp_warm))
            capture("hue-white", dialog = true)

            button(R.string.hue_tab_color).performClick()
            ui.waitForIdle()
            fits(button(R.string.hue_color_emerald))
            button(R.string.hue_color_emerald).performClick()
            ui.waitForIdle()
            capture("hue-color", dialog = true)

            button(R.string.hue_tab_dimmer).performClick()
            ui.waitForIdle()
            button(R.string.hue_turn_off).performClick()
            ui.waitUntil(5_000) {
                repository.state.value.hue.targetFor(repository.state.value.preferences.hueBindings[101L])?.on == false &&
                    repository.state.value.home.devices.getValue(101L).value(Attr.LIGHT) == 1
            }
            action(R.string.back).performClick()
            ui.waitForIdle()
            action(R.string.back).performClick()
            ui.onNodeWithTag("clock_face").assertIsDisplayed()
        } else {
            // On Phone, Back from RoomPager goes to RoomGrid
            action(R.string.back).performClick()
            ui.waitUntil(5_000) { repository.state.value.selectedRoom == null }
            capture("rooms-grid")
            // Trigger a state change and ensure phone stays on RoomGrid
            ui.runOnUiThread { repository.customizeDevice(101, "Custom light", si.hafnar.entalt.domain.LightIcon.Bulb) }
            ui.waitForIdle()
            assertNull("Phone should stay on RoomGrid after Back", repository.state.value.selectedRoom)
            button(R.string.ventilation).performClick()
            ui.waitForIdle()
            capture("ventilation")
            action(R.string.high).performClick()
            ui.waitUntil(5_000) { repository.state.value.home.devices.getValue(105).value(Attr.HRV_SPEED) == 3 }
            button(R.string.bypass).performClick()
            ui.waitUntil(5_000) { repository.state.value.home.devices.getValue(105).value(Attr.HRV_MODE) == 0 }
            action(R.string.back).performClick()
            ui.waitForIdle()

            // Enable Philips Hue in phone Settings and test HueControlWindow
            action(R.string.settings).performClick()
            ui.waitForIdle()
            ui.onNode(hasScrollAction()).performScrollToNode(hasText(text(R.string.philips_hue)))
            ui.onNodeWithTag("hue_enabled_switch").performClick()
            ui.waitUntil(5_000) { repository.state.value.preferences.hueEnabled && repository.state.value.hue.paired }
            ui.onNode(hasScrollAction()).performScrollToNode(hasText(text(R.string.hue_refresh)))
            capture("settings-hue")
            action(R.string.back).performClick()
            ui.waitForIdle()

            button(R.string.text_living_room).performClick()
            ui.waitUntil(5_000) { repository.state.value.selectedRoom == 1L }
            val customPower = ui.onNodeWithContentDescription(text(R.string.device_power, "Custom light"))
            customPower.performTouchInput { longClick() }
            ui.waitForIdle()
            ui.onNode(hasText(text(R.string.managed_by_hue))).performScrollTo()
            ui.onNode(isToggleable()).performClick()
            ui.waitForIdle()
            ui.onNode(hasText(text(R.string.text_hue_demo_pendant), substring = true) and hasClickAction()).performScrollTo().performClick()
            ui.waitForIdle()
            button(R.string.save).performScrollTo().performClick()
            ui.waitUntil(5_000) { repository.state.value.preferences.hueBindings[101L]?.targetId == "demo-light-pendant" }
            customPower.performClick()
            ui.waitUntil(5_000) {
                repository.state.value.home.devices.getValue(101L).value(Attr.LIGHT) == 1 &&
                    repository.state.value.hue.targetFor(repository.state.value.preferences.hueBindings[101L])?.on == true
            }
            ui.onNodeWithContentDescription(text(R.string.hue_controls, "Custom light")).performClick()
            ui.waitForIdle()
            fits(button(R.string.hue_turn_off))
            capture("hue-window", dialog = true)
            action(R.string.back).performClick()
            ui.waitForIdle()
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun windows() = listOf(
            arrayOf<Any>("panel480-160dpi", 480, 480, 160, 1f, "en"),
            arrayOf<Any>("panel480-240dpi", 480, 480, 240, 1f, "en"),
            arrayOf<Any>("panel480-sl", 480, 480, 240, 1.15f, "sl"),
            arrayOf<Any>("phone-360x800", 720, 1600, 320, 1f, "en")
        )
    }
}
