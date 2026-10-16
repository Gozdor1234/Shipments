package com.nate.shipments

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(
    parcels: List<Parcel>,
    actions: Actions,
    refreshing: Boolean,
    hasKey: Boolean,
    addPrefill: String?,
    onPrefillUsed: () -> Unit,
    onOpen: (Parcel) -> Unit,
    onSettings: () -> Unit,
    snackHost: @Composable () -> Unit,
) {
    var showAdd by remember { mutableStateOf(false) }
    var prefill by remember { mutableStateOf("") }
    var showArchived by remember { mutableStateOf(false) }
    LaunchedEffect(addPrefill) {
        if (addPrefill != null) { prefill = addPrefill; showAdd = true; onPrefillUsed() }
    }

    val active = parcels.filter { !it.archived && it.status != Status.DELIVERED }
        .sortedWith(compareBy<Parcel> { -it.status.step }.thenByDescending { it.lastChanged ?: it.addedAt })
    val delivered = parcels.filter { !it.archived && it.status == Status.DELIVERED }
        .sortedByDescending { it.lastChanged ?: it.addedAt }
    val archived = parcels.filter { it.archived }.sortedByDescending { it.lastChanged ?: it.addedAt }

    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Shipments") },
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings") } },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { prefill = ""; showAdd = true }) { Icon(Icons.Filled.Add, "Add package") }
        },
        snackbarHost = snackHost,
    ) { pad ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { actions.refresh() },
            modifier = Modifier.fillMaxSize().padding(pad),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                if (!hasKey) item {
                    Card(
                        Modifier.fillMaxWidth().padding(16.dp).clickable(onClick = onSettings),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Add your Ship24 key", fontWeight = FontWeight.SemiBold)
                            Text("Tracking needs a free API key. Tap to open Settings.",
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (parcels.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(top = 96.dp, start = 32.dp, end = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No packages yet", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(8.dp))
                        Text("Tap + to add a tracking number, or share one to Shipments from any app.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (active.isNotEmpty()) {
                    item { SectionHeader("On the way") }
                    items(active, key = { it.id }) { ParcelRow(it, actions, onOpen) }
                }
                if (delivered.isNotEmpty()) {
                    item { SectionHeader("Delivered") }
                    items(delivered, key = { it.id }) { ParcelRow(it, actions, onOpen) }
                }
                if (archived.isNotEmpty()) {
                    item {
                        Row(Modifier.fillMaxWidth().clickable { showArchived = !showArchived }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("Archived (${archived.size})", style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            Icon(if (showArchived) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null)
                        }
                    }
                    if (showArchived) items(archived, key = { it.id }) { ParcelRow(it, actions, onOpen) }
                }
            }
        }
    }

    if (showAdd) AddSheet(prefill, onDismiss = { showAdd = false }) { number, name, carrier ->
        if (actions.add(number, name, carrier?.code, carrier?.name)) showAdd = false
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ParcelRow(p: Parcel, actions: Actions, onOpen: (Parcel) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().combinedClickable(onClick = { onOpen(p) }, onLongClick = { menu = true })
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusBadge(p.status)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(p.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    p.error?.let { "⚠ $it" } ?: p.latest?.description ?: p.status.label,
                    style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (p.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    listOfNotNull(
                        Fmt.eta(p) ?: p.status.label,
                        p.latest?.location,
                        p.carrierName,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = p.status.color, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = true })
            DropdownMenuItem(text = { Text(if (p.archived) "Unarchive" else "Archive") },
                onClick = { menu = false; actions.edit(p.id) { it.copy(archived = !it.archived) } })
            DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; deleting = true })
        }
    }
    if (renaming) RenameDialog(p.name, onDismiss = { renaming = false }) { n ->
        renaming = false; actions.edit(p.id) { it.copy(name = n) }
    }
    if (deleting) ConfirmDelete(p, onDismiss = { deleting = false }) { deleting = false; actions.delete(p) }
}

@Composable
fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Name") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)) },
        confirmButton = { TextButton(onClick = { onSave(text.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDelete(p: Parcel, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete ${p.title}?") },
        text = { Text("It will be removed from this phone and Ship24 will stop tracking it. Adding it again later may use another shipment from your monthly allowance.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSheet(prefill: String, onDismiss: () -> Unit, onAdd: (String, String, Carrier?) -> Unit) {
    val clipboard = LocalClipboardManager.current
    var number by remember { mutableStateOf(prefill) }
    var name by remember { mutableStateOf("") }
    var carrier by remember { mutableStateOf<Carrier?>(null) }
    var picking by remember { mutableStateOf(false) }
    val guess = Carriers.guess(number)

    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().imePadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Add a package", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = number,
                onValueChange = { number = it },
                label = { Text("Tracking number") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                supportingText = {
                    when {
                        guess == "Amazon" -> Text("Looks like Amazon Logistics. Third-party tracking for these is often limited.")
                        guess != null -> Text("Looks like $guess")
                    }
                },
                trailingIcon = {
                    TextButton(onClick = {
                        clipboard.getText()?.text?.let { number = Carriers.extract(it) ?: it.trim() }
                    }) { Text("Paste") }
                },
            )
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Name (optional)") }, placeholder = { Text("e.g. New headphones") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            Row(
                Modifier.fillMaxWidth().clickable { picking = true }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Carrier", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(carrier?.name ?: "Detect automatically", style = MaterialTheme.typography.bodyLarge)
                }
                Icon(Icons.Filled.ArrowDropDown, null)
            }
            Button(onClick = { onAdd(number, name, carrier) }, enabled = number.isNotBlank(),
                modifier = Modifier.fillMaxWidth()) { Text("Track package") }
        }
    }
    if (picking) CarrierPicker(onDismiss = { picking = false }) { carrier = it; picking = false }
}

/** Search all of Ship24's carriers. Picking null means "detect automatically". */
@Composable
fun CarrierPicker(onDismiss: () -> Unit, onPick: (Carrier?) -> Unit) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var all by remember { mutableStateOf<List<Carrier>?>(null) }
    LaunchedEffect(Unit) { all = withContext(Dispatchers.IO) { Carriers.all(ctx.applicationContext) } }
    val list = all ?: emptyList()
    val shown = remember(query, all) {
        if (query.isBlank()) Carriers.pinned(list)
        else list.filter { it.name.contains(query.trim(), ignoreCase = true) }
            .sortedBy { !it.name.startsWith(query.trim(), ignoreCase = true) }.take(100)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Carrier") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Filled.Search, null) }, placeholder = { Text("Search carriers") })
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.height(320.dp)) {
                    if (query.isBlank()) item {
                        Text("Detect automatically", Modifier.fillMaxWidth().clickable { onPick(null) }
                            .padding(vertical = 12.dp), fontWeight = FontWeight.SemiBold)
                        HorizontalDivider()
                    }
                    items(shown, key = { it.code }) { c ->
                        Text(c.name, Modifier.fillMaxWidth().clickable { onPick(c) }.padding(vertical = 12.dp))
                    }
                    if (shown.isEmpty()) item {
                        Text(
                            when {
                                all == null -> "Loading carriers..."
                                list.isEmpty() -> "Couldn't load the carrier list. Check your Ship24 key in Settings."
                                else -> "No carriers match."
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
