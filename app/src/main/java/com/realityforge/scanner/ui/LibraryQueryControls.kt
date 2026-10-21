package com.realityforge.scanner.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.realityforge.scanner.data.library.LibraryOrder

@Composable
internal fun LibraryQueryControls(search: String, state: String, order: String,
    onSearch: (String) -> Unit, onState: (String) -> Unit, onOrder: (String) -> Unit) {
    var showOrder by remember { mutableStateOf(false) }
    var showState by remember { mutableStateOf(false) }
    val orders = linkedMapOf(LibraryOrder.NEWEST.name to "Newest first", LibraryOrder.OLDEST.name to "Oldest first",
        LibraryOrder.NAME_ASC.name to "Name A–Z", LibraryOrder.NAME_DESC.name to "Name Z–A")
    val states = linkedMapOf("" to "All states", "NEW" to "New", "READY" to "Ready",
        "PROCESSING" to "Processing", "SPARSE_READY" to "Result available", "INTERRUPTED" to "Interrupted", "FAILED" to "Failed")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(search, { onSearch(it.take(512)) }, label = { Text("Search project names") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box {
                OutlinedButton(onClick = { showOrder = true }) { Text(orders[order] ?: "Order") }
                DropdownMenu(expanded = showOrder, onDismissRequest = { showOrder = false }) {
                    orders.forEach { (value, label) -> DropdownMenuItem(text = { Text(label) },
                        onClick = { showOrder = false; onOrder(value) }) }
                }
            }
            Box {
                OutlinedButton(onClick = { showState = true }) { Text(states[state] ?: "State") }
                DropdownMenu(expanded = showState, onDismissRequest = { showState = false }) {
                    states.forEach { (value, label) -> DropdownMenuItem(text = { Text(label) },
                        onClick = { showState = false; onState(value) }) }
                }
            }
        }
    }
}
