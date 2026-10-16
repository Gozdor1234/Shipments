package com.nate.shipments

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, toast: (String) -> Unit, snackHost: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf(Settings.apiKey(ctx) ?: "") }
    var showKey by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var quotaText by remember { mutableStateOf<String?>(null) }
    var interval by remember { mutableStateOf(Settings.interval(ctx)) }
    var notify by remember { mutableStateOf(Settings.notify(ctx)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") } },
            )
        },
        snackbarHost = snackHost,
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("17TRACK API key", style = MaterialTheme.typography.titleMedium)
            Text(
                "Sign up free at api.17track.net and copy the API key from your dashboard there. " +
                    "The key is stored only on this phone.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://api.17track.net/en"))) }
            }) { Text("Open api.17track.net") }
            OutlinedTextField(
                value = key, onValueChange = { key = it }, singleLine = true, label = { Text("API key") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") } },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    Settings.setApiKey(ctx, key)
                    toast("Key saved")
                }) { Text("Save") }
                OutlinedButton(enabled = key.isNotBlank() && !testing, onClick = {
                    Settings.setApiKey(ctx, key)
                    testing = true
                    scope.launch {
                        quotaText = try {
                            describeQuota(withContext(Dispatchers.IO) { Track17(key.trim()).quota() })
                        } catch (e: Exception) {
                            "Key test failed: ${e.message ?: e.javaClass.simpleName}"
                        }
                        testing = false
                    }
                }) { Text(if (testing) "Testing..." else "Test key") }
            }
            quotaText?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

            HorizontalDivider()
            Text("Background refresh", style = MaterialTheme.typography.titleMedium)
            Text(
                "How often to check for updates while the app is closed. Android may stretch this to save battery. " +
                    "Checking doesn't use 17TRACK credits; only adding new numbers does.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15 to "15 min", 30 to "30 min", 60 to "1 hr", 120 to "2 hr").forEach { (m, label) ->
                    FilterChip(selected = interval == m, label = { Text(label) }, onClick = {
                        interval = m
                        Settings.setInterval(ctx, m)
                        Tracker.schedule(ctx)
                    })
                }
            }

            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Notifications", style = MaterialTheme.typography.titleMedium)
                    Text("Alert when a package's status or location changes",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = notify, onCheckedChange = { notify = it; Settings.setNotify(ctx, it) })
            }

            HorizontalDivider()
            Spacer(Modifier.height(4.dp))
            Text(
                "Shipments by ChickenMyBobbers. Tracking data from 17TRACK. Maps © OpenStreetMap contributors.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 17TRACK's quota response fields aren't documented in detail, so show what's there. */
private fun describeQuota(data: JSONObject): String {
    val total = data.longOrNull("quota_total")
    val used = data.longOrNull("quota_used")
    val remain = data.longOrNull("quota_remain") ?: if (total != null && used != null) total - used else null
    if (remain != null) {
        return "Key works. " + (if (total != null) "$remain of $total tracking credits left." else "$remain tracking credits left.")
    }
    val nums = data.keys().asSequence().filter { data.opt(it) is Number }.map { "$it: ${data.opt(it)}" }.toList()
    return if (nums.isEmpty()) "Key works." else "Key works. " + nums.joinToString(", ")
}
