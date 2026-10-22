package com.karelisio.speech.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@Composable
fun <T> Dropdown(label: String, options: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text(label) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (v, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onSelect(v) })
            }
        }
    }
}

fun formatMb(bytes: Long): String = if (bytes >= 1_000_000_000) "%.2f Go".format(bytes / 1e9) else "${bytes / 1_000_000} Mo"
