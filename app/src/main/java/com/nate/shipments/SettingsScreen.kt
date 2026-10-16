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
            Text("Trace API key", style = MaterialTheme.typography.titleMedium)
            Text(
                "Sign up free at traceapi.dev (email only), then create an API key in your dashboard. It starts with trc_. " +
                    "The key is stored only on this phone.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://traceapi.dev/dashboard/api-keys"))) }
            }) { Text("Open Trace dashboard") }
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
                            withContext(Dispatchers.IO) {
                                Trace(key.trim()).testKey()
                                "Key works. (The test doesn't use a lookup.)"
                            }
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
                    "Every check of every package uses one of your 1,000 free Trace lookups a month, so slower is cheaper. " +
                    "At 3 hours, 5 packages in transit use about 40 a day.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(60 to "1 hr", 120 to "2 hr", 180 to "3 hr", 360 to "6 hr").forEach { (m, label) ->
                    FilterChip(selected = interval == m, label = { Text(label) }, onClick = {
                        interval = m
                        Settings.setInterval(ctx, m)
                        Tracker.schedule(ctx)
                    })
                }
            }

            Text(
                "About ${Settings.lookupsThisMonth(ctx)} lookups used this month on this phone. " +
                    "Your Trace dashboard has the exact count.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

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
                "Shipments by ChickenMyBobbers. Tracking data from Trace (traceapi.dev). Maps © OpenStreetMap contributors.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
