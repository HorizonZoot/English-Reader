package io.github.zoot.englishreader.ui.component

import android.content.Context
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.zoot.englishreader.R

/** Anchored menu compatible with Material 3 1.2, with independent field/direction choices. */
@Composable
fun <T> SortMenuButton(
    label: String,
    options: List<Pair<T, String>>,
    selectedField: T,
    ascending: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onFieldSelected: (T) -> Unit,
    onAscendingSelected: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    showDirection: Boolean = true,
    preferBelowAnchor: Boolean = false
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    var anchorBottom by remember { mutableFloatStateOf(0f) }
    // Use the inset-aware window height and Material's 48dp popup margin.
    val availableHeight = configuration.screenHeightDp.dp - with(density) { anchorBottom.toDp() } - 48.dp
    val menuModifier = if (preferBelowAnchor && availableHeight >= 144.dp) {
        Modifier.width(240.dp).heightIn(max = availableHeight)
    } else {
        Modifier.width(240.dp)
    }
    Box(modifier.onGloballyPositioned { anchorBottom = it.boundsInWindow().bottom }) {
        TextButton(onClick = { onExpandedChange(true) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(label)
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        MaterialTheme(
            shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(14.dp)),
            colorScheme = MaterialTheme.colorScheme.copy(
                surface = MaterialTheme.colorScheme.surfaceVariant,
                surfaceTint = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { onExpandedChange(false) },
                modifier = menuModifier
            ) {
                // The popup owns this window; dimming also covers the host navigation bar.
                val popupView = LocalView.current.rootView
                SideEffect {
                    val params = popupView.layoutParams as? WindowManager.LayoutParams
                    if (params != null && params.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND == 0) {
                        params.flags = params.flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND
                        params.dimAmount = 0.4f
                        val manager = popupView.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                        manager.updateViewLayout(popupView, params)
                    }
                }
                Text(
                    stringResource(R.string.library_sort_field_heading),
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                options.forEach { (field, text) ->
                    SortMenuItem(text, field == selectedField) {
                        onExpandedChange(false)
                        onFieldSelected(field)
                    }
                }
                if (showDirection) {
                    Divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                    Text(
                        stringResource(R.string.library_sort_direction_heading),
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SortMenuItem(stringResource(R.string.library_sort_descending), !ascending) {
                        onExpandedChange(false)
                        onAscendingSelected(false)
                    }
                    SortMenuItem(stringResource(R.string.library_sort_ascending), ascending) {
                        onExpandedChange(false)
                        onAscendingSelected(true)
                    }
                }
            }
        }
    }
}

@Composable
private fun SortMenuItem(text: String, isSelected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, style = MaterialTheme.typography.bodyLarge) },
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp).semantics { selected = isSelected },
        leadingIcon = {
            if (isSelected) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
            else Spacer(Modifier.size(24.dp))
        }
    )
}
