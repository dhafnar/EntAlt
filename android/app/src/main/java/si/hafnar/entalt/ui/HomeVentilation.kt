package si.hafnar.entalt.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import si.hafnar.entalt.R
import si.hafnar.entalt.data.AppState
import si.hafnar.entalt.data.EntiaRepository
import si.hafnar.entalt.domain.Attr

@Composable internal fun VentilationEntry(state: AppState, onOpen: () -> Unit) {
    Card(onOpen, Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Outlined.Air, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.home_controls),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(stringResource(R.string.ventilation), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.ventilation_units, state.home.ventilationUnits.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.Outlined.ChevronRight, null)
        }
    }
}

@Composable internal fun HomeVentilation(state: AppState, repository: EntiaRepository, panel: Boolean) {
    if (state.home.ventilationUnits.isEmpty()) {
        EmptyMessage(stringResource(R.string.no_ventilation))
        return
    }
    Box(Modifier.fillMaxSize(), contentAlignment = if (panel) Alignment.Center else Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 720.dp).fillMaxSize(),
            contentPadding = PaddingValues(if (panel) 14.dp else 16.dp),
            verticalArrangement = if (panel) Arrangement.spacedBy(12.dp, Alignment.CenterVertically) else Arrangement.spacedBy(12.dp)
        ) {
            if (!panel) {
                item {
                    Text(
                        stringResource(R.string.ventilation_scope),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(state.home.ventilationUnits, key = { it.id }) { device ->
                DeviceControl(Control(device, Attr.HRV_SPEED), state, repository, panel)
            }
        }
    }
}
