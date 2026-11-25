@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package si.hafnar.entalt

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import si.hafnar.entalt.data.*
import si.hafnar.entalt.domain.Attr
import si.hafnar.entalt.ui.EntAltApp
import si.hafnar.entalt.ui.EntAltTheme

/** App-level development instrumentation, with fake account storage and no network. */
@RunWith(Parameterized::class)
class AdaptiveUiTest(private val name: String, private val width: Int, private val height: Int,
    private val fontScale: Float, private val language: String) {
    @get:Rule val compose = createComposeRule()
    private lateinit var repository: EntiaRepository
    private lateinit var context: Context
    private fun label(id: Int, vararg args: Any) = context.getString(id, *args)

    @Before fun prepare() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        context = target.createConfigurationContext(Configuration(target.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
        })
        compose.runOnUiThread {
            repository = EntiaRepository(object : AccountStore {
                override fun load(): Credentials? = null
                override fun save(credentials: Credentials) = error("Demo must not save credentials")
                override fun clear() = Unit
            }, object : PreferenceStore {
                override suspend fun load() = Preferences(layout = "controls", disclaimerAccepted = true)
                override suspend fun save(preferences: Preferences) = Unit
                override suspend fun clearAccount() = Unit
            }, gateway = { error("Demo must not create a live client") }, text = androidText(context))
            repository.setForeground(true)
        }
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, height.dp))
                then DeviceConfigurationOverride.FontScale(fontScale)
                then DeviceConfigurationOverride.Locales(LocaleList(language))) {
                Box(Modifier.fillMaxSize().testTag("app_window")) {
                    val state by repository.state.collectAsState()
                    EntAltTheme(state.preferences) { EntAltApp(state, repository) }
                }
            }
        }
        compose.waitUntil(5_000) { !repository.state.value.restoring }
    }

    @After fun cleanup() { compose.runOnUiThread { repository.close() } }

    private fun reveal(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        if (compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty()) {
            compose.onNode(hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
                .performScrollToNode(matcher)
        }
        return compose.onAllNodes(matcher).onFirst().performScrollTo().assertIsDisplayed()
    }

    private fun screenshot(screen: String) {
        val image = compose.onNodeWithTag("app_window").captureToImage().asAndroidBitmap()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: context.getExternalFilesDir(null)?.absolutePath ?: context.filesDir.absolutePath
        val directory = File(output, "qa").apply { mkdirs() }
        File(directory, "$name-$screen.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun openDemoRoom() {
        compose.onNodeWithText(label(R.string.explore_demo)).performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil { repository.state.value.demo }
        screenshot("rooms")
        reveal(hasText(label(R.string.text_living_room))).performClick()
        compose.waitUntil { repository.state.value.selectedRoom == 1L }
    }

    private fun chooseControl(name: String) {
        if (width < 320 || height < 360) {
            reveal(hasContentDescription(label(R.string.choose_control))).performClick()
            reveal(hasText(name)).performClick()
        } else reveal(hasText(name))
    }

    @Test fun signInRoomSelectionLightControlAndBackStayReachable() {
        screenshot("sign-in")
        openDemoRoom()
        val light = label(R.string.text_pendant_lights)
        chooseControl(light)
        val power = label(R.string.device_power, light)
        reveal(hasContentDescription(power)).performClick()
        compose.waitUntil { repository.state.value.home.devices.getValue(101).value(Attr.LIGHT) == 0 }
        screenshot("light")
        compose.onNodeWithContentDescription(label(R.string.back)).performClick()
        compose.waitUntil { repository.state.value.selectedRoom == null }
        reveal(hasText(label(R.string.text_living_room))).assertExists()
    }

    @Test fun temperatureEditorCommitsHalfDegreeAndRoomNavigationWorks() {
        openDemoRoom()
        chooseControl(label(R.string.text_room_climate))
        reveal(hasText(label(R.string.target_temperature, 22.0))).performClick()
        compose.onAllNodesWithContentDescription(label(R.string.increase)).onLast().performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.apply)).performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil { repository.state.value.home.devices.getValue(103).value(Attr.TARGET) == 45 }
        screenshot("climate")
        reveal(hasText(label(R.string.next))).performClick()
        compose.waitUntil { repository.state.value.selectedRoom == 2L }
        reveal(hasText(label(R.string.previous))).performClick()
        compose.waitUntil { repository.state.value.selectedRoom == 1L }
        assertTrue(repository.state.value.demo)
    }

    @Test fun blindTiltVentilationAndSensorStayReachable() {
        openDemoRoom()
        chooseControl(label(R.string.text_garden_blinds))
        reveal(hasText(label(R.string.close_blind))).performClick()
        compose.waitUntil { repository.state.value.home.devices.getValue(102).value(Attr.POSITION) == 100 }
        reveal(hasText(label(R.string.derived_tilt, "0"))).assertIsEnabled().performClick()
        compose.onNodeWithContentDescription(label(R.string.set_tilt)).performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(50f) }
        compose.onNodeWithText(label(R.string.apply)).performScrollTo().performClick()
        compose.waitUntil { repository.state.value.home.devices.getValue(102).value(Attr.POSITION) == 98 }
        screenshot("blind")
        compose.onNodeWithContentDescription(label(R.string.back)).performClick()
        reveal(hasText(label(R.string.ventilation))).performClick()
        reveal(hasText(label(R.string.high))).performClick()
        compose.waitUntil { repository.state.value.home.devices.getValue(105).value(Attr.HRV_SPEED) == 3 }
        val power = label(R.string.device_power, label(R.string.text_fresh_air))
        reveal(hasContentDescription(power)).performClick()
        compose.waitUntil { repository.state.value.home.devices.getValue(105).value(Attr.HRV_SPEED) == 0 }
        reveal(hasContentDescription(power)).performClick()
        compose.waitUntil { repository.state.value.home.devices.getValue(105).value(Attr.HRV_SPEED) == 3 }
        screenshot("ventilation")
        compose.onNodeWithContentDescription(label(R.string.back)).performClick()
        reveal(hasText(label(R.string.text_living_room))).performClick()
        chooseControl(label(R.string.text_temperature))
        reveal(hasText(label(R.string.celsius, 21.5))).assertExists()
        screenshot("sensor")
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun windows() = listOf(
            arrayOf<Any>("panel240", 240, 240, 1f, "en"),
            arrayOf<Any>("panel320", 320, 320, 1f, "en"),
            arrayOf<Any>("landscape", 480, 320, 1f, "en"),
            arrayOf<Any>("phone", 360, 800, 1f, "en"),
            arrayOf<Any>("tablet", 1280, 800, 1f, "en"),
            arrayOf<Any>("panel240-sl-large", 240, 240, 2f, "sl"),
        )
    }
}
