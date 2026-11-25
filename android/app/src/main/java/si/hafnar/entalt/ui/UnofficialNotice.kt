package si.hafnar.entalt.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import si.hafnar.entalt.R
import si.hafnar.entalt.data.AppState

@Composable internal fun UnofficialNotice(state: AppState, onAgree: () -> Unit, onExit: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val compact = maxHeight <= 480.dp || maxWidth < 360.dp
        val paged = compact && maxHeight < 300.dp
        var riskPage by remember { mutableStateOf(false) }
        Column(Modifier.widthIn(max = 520.dp).fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(if (compact) 6.dp else 24.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 16.dp)) {
                Text(stringResource(R.string.unofficial_title), style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.headlineMedium)
                if (compact) Text(stringResource(if (paged) {
                    if (riskPage) R.string.unofficial_compact_risk else R.string.unofficial_compact_intro
                } else R.string.unofficial_compact), style = MaterialTheme.typography.bodySmall)
                else {
                    Text(stringResource(R.string.unofficial_description), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.unofficial_risk), style = MaterialTheme.typography.bodyMedium)
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.fillMaxWidth().padding(if (compact) 4.dp else 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onExit, Modifier.weight(0.32f).heightIn(min = 48.dp), enabled = !state.acceptingDisclaimer,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)) {
                        Text(stringResource(R.string.exit_app), textAlign = TextAlign.Center)
                    }
                    Button({ if (paged && !riskPage) riskPage = true else onAgree() }, Modifier.weight(0.68f).heightIn(min = 48.dp), enabled = !state.acceptingDisclaimer,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)) {
                        Text(stringResource(if (paged && !riskPage) R.string.next else if (state.acceptingDisclaimer) R.string.accepting_disclaimer else R.string.accept_disclaimer), textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}
