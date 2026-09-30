package com.prasbin.shadowlearn.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Group switcher for the consolidated bottom navigation: shows what a
 * group (STUDY, SYSTEM) contains and moves between its destinations.
 * The selected option is a filled primary button (a no-op tap); the
 * other is an outlined action. Both meet the ~48dp touch target.
 */
@Composable
fun GroupSwitcher(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEachIndexed { index, label ->
            val itemModifier = Modifier.weight(1f).height(48.dp)
            if (index == selectedIndex) {
                Button(onClick = {}, modifier = itemModifier) {
                    Text(label, style = MaterialTheme.typography.labelLarge)
                }
            } else {
                OutlinedButton(
                    onClick = { onSelect(index) },
                    modifier = itemModifier
                ) {
                    Text(label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
