package com.nate.shipments

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    p: Parcel,
    actions: Actions,
    refreshing: Boolean,
    onBack: () -> Unit,
    onMap: () -> Unit,
    snackHost: @Composable () -> Unit,
) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var pickingCarrier by remember { mutableStateOf(false) }
    var stops by remember { mutableStateOf<List<MapStop>>(emptyList()) }
    LaunchedEffect(p.events) { stops = Geo.stops(ctx.applicationContext, p.events) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(p.title, maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") } },
                actions = {
                    if (refreshing) CircularProgressIndicator(Modifier.size(24.dp).padding(2.dp), strokeWidth = 2.dp)
                    else IconButton(onClick = { actions.refresh(setOf(p.id)) }) { Icon(Icons.Filled.Refresh, "Refresh") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = true })
                        DropdownMenuItem(text = { Text("Change carrier") }, onClick = { menu = false; pickingCarrier = true })
                        DropdownMenuItem(text = { Text("Open carrier website") }, onClick = {
                            menu = false
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Carriers.trackingUrl(p))) }
                        })
                        DropdownMenuItem(text = { Text("Share tracking number") }, onClick = {
                            menu = false
                            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, p.number), null))
                        })
                        DropdownMenuItem(text = { Text(if (p.archived) "Unarchive" else "Archive") },
                            onClick = { menu = false; actions.edit(p.id) { it.copy(archived = !it.archived) } })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; deleting = true })
                    }
                },
            )
        },
        snackbarHost = snackHost,
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 32.dp)) {
            item { Header(p) }
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(p.carrierName ?: "Carrier not detected yet", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            p.number, fontFamily = FontFamily.Monospace, fontSize = 17.sp,
                            modifier = Modifier.clickable { clipboard.setText(AnnotatedString(p.number)) }.padding(vertical = 4.dp),
                        )
                        Text("Tap the number to copy · Checked ${Fmt.ago(p.lastChecked)}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        p.error?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            if (stops.isNotEmpty()) item {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(200.dp)
                    .clip(RoundedCornerShape(16.dp))) {
                    RouteMap(stops, p.status.color, interactive = false, modifier = Modifier.fillMaxSize(), onTap = onMap)
                    MapAttribution(Modifier.align(Alignment.BottomEnd))
                }
            }
            if (p.events.isEmpty()) item {
                Text(
                    if (p.registered) "No scans yet. Some carriers only report once the package is actually handed over, so this can take a day."
                    else "Waiting to register this number with Ship24.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            } else {
                val grouped = p.events.groupBy { Fmt.eventDate(it) }
                grouped.forEach { (date, evs) ->
                    item {
                        Text(date?.let { Fmt.day(it) } ?: "Unknown date", style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
                    }
                    items(evs) { e -> EventRow(e, isLatest = e == p.events.first(), color = p.status.color) }
                }
            }
        }
    }

    if (renaming) RenameDialog(p.name, onDismiss = { renaming = false }) { n ->
        renaming = false; actions.edit(p.id) { it.copy(name = n) }
    }
    if (deleting) ConfirmDelete(p, onDismiss = { deleting = false }) { deleting = false; actions.delete(p); onBack() }
    if (pickingCarrier) CarrierPicker(onDismiss = { pickingCarrier = false }) { c ->
        pickingCarrier = false; actions.changeCarrier(p, c)
    }
}

@Composable
private fun Header(p: Parcel) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusBadge(p.status, 48.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(p.status.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                    color = p.status.color)
                (Fmt.eta(p) ?: Fmt.subStatus(p.subStatus))?.let {
                    Text(it, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        ProgressSteps(p.status)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("Label", "Shipped", "Out", "Delivered").forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EventRow(e: TrackEvent, isLatest: Boolean, color: androidx.compose.ui.graphics.Color) {
    Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min).padding(horizontal = 16.dp)) {
        // Timeline rail
        Box(Modifier.width(24.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
            Box(Modifier.padding(top = 6.dp).size(if (isLatest) 12.dp else 8.dp).clip(CircleShape)
                .background(if (isLatest) color else MaterialTheme.colorScheme.outline))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(bottom = 14.dp)) {
            Text(e.description, style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isLatest) FontWeight.SemiBold else FontWeight.Normal)
            val meta = listOfNotNull(Fmt.eventTime(e), e.location).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun MapAttribution(modifier: Modifier = Modifier) {
    Surface(modifier.padding(4.dp), shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)) {
        Text("© OpenStreetMap contributors", fontSize = 9.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
    }
}

@Composable
fun MapScreen(p: Parcel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var stops by remember { mutableStateOf<List<MapStop>>(emptyList()) }
    LaunchedEffect(p.events) { stops = Geo.stops(ctx.applicationContext, p.events) }
    Box(Modifier.fillMaxSize()) {
        RouteMap(stops, p.status.color, interactive = true, modifier = Modifier.fillMaxSize())
        FilledIconButton(onClick = onBack, modifier = Modifier.statusBarsPadding().padding(12.dp)) {
            Icon(Icons.Filled.ArrowBack, "Back")
        }
        MapAttribution(Modifier.align(Alignment.BottomEnd).padding(bottom = 24.dp))
    }
}
