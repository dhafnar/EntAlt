package si.hafnar.entalt.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.ceil
import kotlin.math.min
import si.hafnar.entalt.R

/** Finite pages keep every item and navigation button prominent inside the square panel window. */
@Composable internal fun PanelMenu(
    items: List<Pair<String, String>>,
    onChoose: (String) -> Unit,
    modifier: Modifier = Modifier,
    selected: String? = null,
    enabled: Boolean = true,
    icons: Map<String, ImageVector> = emptyMap()
) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    BoxWithConstraints(modifier.fillMaxSize().padding(14.dp)) {
        val scale = (min(maxWidth.value, maxHeight.value) / 420f).coerceIn(0.72f, 1.15f)
        val columns = if (maxWidth >= 240.dp && items.size > 2) 2 else 1
        val minRowHeight = 76f * scale
        val unpagedRows = (maxHeight.value / minRowHeight).toInt().coerceAtLeast(1)
        val paged = items.size > columns * unpagedRows
        val rows = ((maxHeight.value - if (paged) 56f * scale else 0f) / minRowHeight).toInt().coerceAtLeast(1)
        val perPage = rows * columns
        val pages = ceil(items.size.toDouble() / perPage).toInt().coerceAtLeast(1)
        val current = page.coerceIn(0, pages - 1)
        val spacing = (10 * scale).dp
        val cornerShape = RoundedCornerShape((20 * scale).dp)
        val fontSize = (19 * scale).sp

        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(spacing)) {
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(spacing, Alignment.CenterVertically)
            ) {
                items.drop(current * perPage).take(perPage).chunked(columns).forEach { row ->
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing)) {
                        row.forEach { (key, label) ->
                            FilledTonalButton(
                                { onChoose(key) },
                                Modifier.weight(1f).fillMaxHeight().heightIn(min = 52.dp),
                                enabled = enabled,
                                shape = cornerShape,
                                contentPadding = PaddingValues((12 * scale).dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = if (selected == key) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerHigh
                                )
                            ) {
                                icons[key]?.let {
                                    Icon(it, null, Modifier.size((26 * scale).dp))
                                    Spacer(Modifier.width((10 * scale).dp))
                                }
                                Text(
                                    label,
                                    fontSize = fontSize,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        if (row.size < columns && items.size > 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            if (pages > 1) {
                Row(
                    Modifier.fillMaxWidth().height((54 * scale).coerceAtLeast(48f).dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalIconButton(
                        { page = current - 1 },
                        Modifier.size((52 * scale).coerceAtLeast(48f).dp),
                        enabled = current > 0
                    ) {
                        Icon(Icons.Outlined.ChevronLeft, stringResource(R.string.previous), Modifier.size((28 * scale).dp))
                    }
                    Text(
                        "${current + 1} / $pages",
                        Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        fontSize = (17 * scale).sp,
                        fontWeight = FontWeight.Medium
                    )
                    FilledTonalIconButton(
                        { page = current + 1 },
                        Modifier.size((52 * scale).coerceAtLeast(48f).dp),
                        enabled = current < pages - 1
                    ) {
                        Icon(Icons.Outlined.ChevronRight, stringResource(R.string.next), Modifier.size((28 * scale).dp))
                    }
                }
            }
        }
    }
}

@Composable internal fun PanelChoiceDialog(
    title: String,
    choices: List<Pair<String, String>>,
    selected: String?,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onChoose: (String) -> Unit
) {
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilledTonalIconButton(
                        onClick = onDismiss,
                        modifier = Modifier.width(76.dp).height(56.dp),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Icon(Icons.Outlined.Close, stringResource(R.string.close_controls), Modifier.size(30.dp))
                    }
                    Text(
                        title,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                PanelMenu(choices, onChoose, Modifier.weight(1f), selected, enabled)
            }
        }
    }
}
