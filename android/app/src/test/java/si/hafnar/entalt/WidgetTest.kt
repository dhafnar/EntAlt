package si.hafnar.entalt

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import si.hafnar.entalt.data.AccountStore
import si.hafnar.entalt.data.Credentials
import si.hafnar.entalt.data.EntiaRepository
import si.hafnar.entalt.data.PreferenceStore
import si.hafnar.entalt.data.Preferences
import si.hafnar.entalt.domain.Attr
import si.hafnar.entalt.widget.EntAltWidgetProvider

class WidgetTest {

    private val allowedRemoteViewTags = setOf(
        "FrameLayout",
        "LinearLayout",
        "RelativeLayout",
        "GridLayout",
        "ImageView",
        "TextView",
        "Button",
        "ProgressBar",
        "AnalogClock",
        "Chronometer",
        "ImageButton",
        "ViewFlipper",
        "ListView",
        "GridView",
        "StackView",
        "AdapterViewFlipper",
        "ViewStub",
        "CheckBox",
        "Switch",
        "RadioButton",
        "RadioGroup",
        "SeekBar",
        "DatePicker",
        "NumberPicker",
        "TimePicker",
    )

    @Test
    fun widgetLayoutsUseOnlyRemoteViewAllowedTags() {
        val layoutDir = listOf(
            File("src/main/res/layout"),
            File("app/src/main/res/layout"),
        ).first { it.isDirectory }
        val widgetLayouts = layoutDir.listFiles { f ->
            f.name.startsWith("entalt_widget_") && f.name.endsWith(".xml")
        }.orEmpty()
        assertTrue("Expected entalt_widget_*.xml layouts", widgetLayouts.isNotEmpty())

        val factory = DocumentBuilderFactory.newInstance()
        widgetLayouts.forEach { file ->
            val doc = factory.newDocumentBuilder().parse(file)
            val nodes = doc.getElementsByTagName("*")
            for (i in 0 until nodes.length) {
                val el = nodes.item(i) as Element
                assertTrue(
                    "${file.name} contains disallowed RemoteViews tag <${el.tagName}>",
                    el.tagName in allowedRemoteViewTags
                )
            }
        }
    }

    @Test
    fun responsiveLayoutAdaptsCountAndGridToLauncherSize() {
        // 0 items -> single full-bleed add tile
        assertEquals(listOf(1), EntAltWidgetProvider.computeResponsiveLayout(0, 140, 140).rowItemCounts)

        // Shrinking below 4 items shows fewer tiles (1x1 -> 1, 2x1 -> 2, 1x2 -> 2)
        assertEquals(listOf(1), EntAltWidgetProvider.computeResponsiveLayout(6, 70, 70).rowItemCounts)
        assertEquals(listOf(2), EntAltWidgetProvider.computeResponsiveLayout(6, 140, 70).rowItemCounts)
        assertEquals(listOf(1, 1), EntAltWidgetProvider.computeResponsiveLayout(6, 70, 140).rowItemCounts)

        // Fewer configured items than 2x2 capacity -> no empty holes, fills space
        assertEquals(listOf(1), EntAltWidgetProvider.computeResponsiveLayout(1, 140, 140).rowItemCounts)
        assertEquals(listOf(1, 1), EntAltWidgetProvider.computeResponsiveLayout(2, 140, 140).rowItemCounts)
        assertEquals(listOf(2, 1), EntAltWidgetProvider.computeResponsiveLayout(3, 140, 140).rowItemCounts)
        assertEquals(listOf(2, 2), EntAltWidgetProvider.computeResponsiveLayout(4, 140, 140).rowItemCounts)

        // Expanding beyond 2x2 shows more than 4 items cleanly
        assertEquals(listOf(4), EntAltWidgetProvider.computeResponsiveLayout(6, 290, 70).rowItemCounts)
        assertEquals(listOf(3, 3), EntAltWidgetProvider.computeResponsiveLayout(6, 290, 140).rowItemCounts)
        assertEquals(listOf(3, 2), EntAltWidgetProvider.computeResponsiveLayout(5, 290, 140).rowItemCounts)
        assertEquals(listOf(4, 4), EntAltWidgetProvider.computeResponsiveLayout(8, 290, 140).rowItemCounts)
        assertEquals(listOf(4, 4, 4), EntAltWidgetProvider.computeResponsiveLayout(12, 290, 220).rowItemCounts)
    }

    @Test
    fun demoCommandWorksInBackgroundOnlyWhenAllowBackgroundIsTrue() = runBlocking {
        val accounts = object : AccountStore {
            override fun load(): Credentials? = null
            override fun save(credentials: Credentials) {}
            override fun clear() {}
        }
        val prefs = object : PreferenceStore {
            override suspend fun load() = Preferences(disclaimerAccepted = true)
            override suspend fun save(preferences: Preferences) {}
            override suspend fun clearAccount() {}
        }
        val repo = EntiaRepository(
            accounts = accounts,
            preferences = prefs,
            dispatcher = Dispatchers.Unconfined,
            io = Dispatchers.Unconfined,
        )
        repo.demo()
        repo.setForeground(false)

        val light = repo.state.value.home.devices.values.first { it.isLight && !it.isStandaloneHue }
        val initial = light.value(Attr.LIGHT) ?: 0
        val next = if (initial == 1) 0 else 1

        // Default allowBackground = false drops command while backgrounded.
        repo.command(light.id, Attr.LIGHT, next)
        assertEquals(initial, repo.state.value.home.devices[light.id]?.value(Attr.LIGHT))

        // Widget background tap passes allowBackground = true and succeeds.
        repo.command(light.id, Attr.LIGHT, next, allowBackground = true)
        assertEquals(next, repo.state.value.home.devices[light.id]?.value(Attr.LIGHT))
        assertFalse(repo.state.value.foreground)
    }
}
