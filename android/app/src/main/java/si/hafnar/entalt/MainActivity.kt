package si.hafnar.entalt

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import si.hafnar.entalt.data.EntiaRepository
import si.hafnar.entalt.ui.EntAltApp
import si.hafnar.entalt.ui.EntAltTheme

class HomeViewModel(val repository: EntiaRepository) : ViewModel()

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = (application as EntAltApplication).repository
        setContent {
            val vm: HomeViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(repository) as T
            })
            val state = vm.repository.state.collectAsStateWithLifecycle().value
            val windowInfo = LocalWindowInfo.current
            val widthPx = windowInfo.containerSize.width
            val heightPx = windowInfo.containerSize.height
            val isProfileA = widthPx > 0 && widthPx == heightPx
            LaunchedEffect(isProfileA) {
                requestedOrientation = if (isProfileA) {
                    ActivityInfo.SCREEN_ORIENTATION_NOSENSOR
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
            EntAltTheme(state.preferences) { EntAltApp(state, vm.repository, isProfileA, onExit = { finish() }) }
        }
    }
}
