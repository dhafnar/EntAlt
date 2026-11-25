package si.hafnar.entalt

import android.app.Application
import android.text.Html
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import si.hafnar.entalt.data.*
import si.hafnar.entalt.widget.EntAltWidgetProvider
import si.hafnar.entalt.widget.WidgetStore

class EntAltApplication : Application(), DefaultLifecycleObserver {
    lateinit var repository: EntiaRepository
        private set
    private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super<Application>.onCreate()
        val credentialStore = CredentialStore(this)
        repository = EntiaRepository(credentialStore, AppPreferences(this),
            decodeLabel = { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString().trim() },
            text = androidText(this) {
                if (::repository.isInitialized) repository.state.value.preferences.language else "system"
            },
            hueStore = credentialStore,
            hueDiscoverer = { HueDiscovery.discoverBridges(this) })
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        val widgetStore = WidgetStore(this)
        widgetScope.launch {
            repository.state
                .distinctUntilChangedBy {
                    listOf(
                        it.home,
                        it.username == null && !it.demo,
                        it.restoring,
                        it.hue.targets,
                        it.preferences.hueEnabled,
                        it.preferences.hueBindings,
                        it.preferences.standaloneHueLights,
                        it.preferences.language,
                    )
                }
                .collect { state ->
                    widgetStore.saveLanguage(state.preferences.language)
                    if (state.username == null && !state.demo && !state.restoring) {
                        widgetStore.clearCache()
                        EntAltWidgetProvider.refreshAllWidgets(this@EntAltApplication)
                    } else if (state.home.devices.isNotEmpty()) {
                        widgetStore.saveCacheFromHome(state.home, state.preferences, state.hue)
                        EntAltWidgetProvider.refreshAllWidgets(this@EntAltApplication)
                    }
                }
        }
    }
    override fun onStart(owner: LifecycleOwner) { repository.setForeground(true) }
    override fun onStop(owner: LifecycleOwner) { repository.setForeground(false) }
}
