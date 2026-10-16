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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
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
    val look = Appearance.get(ctx)
    var editing by remember { mutableStateOf<ColorSlot?>(null) }

    // What each area looks like right now (theme color, or the custom one), for swatches and checks.
    val cs = MaterialTheme.colorScheme
    val barColor = LocalExtraColors.current.bar ?: cs.surface
    val shown: Map<ColorSlot, Color> = mapOf(
        ColorSlot.Background to cs.background,
        ColorSlot.Cards to cs.surfaceContainerHighest,
        ColorSlot.Accent to cs.primary,
        ColorSlot.Text to cs.onSurface,
        ColorSlot.SubText to cs.onSurfaceVariant,
        ColorSlot.Lines to cs.outlineVariant,
        ColorSlot.Bars to barColor,
    )
    val issues = if (!look.customColorsOn) emptyList() else ContrastCheck.issues(
        listOf(
            Triple("Main text on Background", cs.onSurface.toArgb() to cs.background.toArgb(), 4.5),
            Triple("Main text on Cards", cs.onSurface.toArgb() to cs.surfaceContainerHighest.toArgb(), 4.5),
            Triple("Secondary text on Background", cs.onSurfaceVariant.toArgb() to cs.background.toArgb(), 3.0),
            Triple("Accent on Background", cs.primary.toArgb() to cs.background.toArgb(), 3.0),
        ),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                colors = shipmentsTopBarColors(),
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
            Text("Appearance", style = MaterialTheme.typography.titleMedium)
            SwitchRow("Modern style (glass panels, soft shadows)", look.modernStyle) { look.updateModernStyle(it) }
            Hint("Off keeps the standard look. Works with every theme below. The home screen widget always uses the standard look.")
            SwitchRow("Match my phone's colors", look.matchPhoneColors && supportsPhoneColors, enabled = supportsPhoneColors) {
                look.updateMatchPhoneColors(it)
            }
            Hint(
                if (supportsPhoneColors) "Uses the color palette from your phone's wallpaper and style settings. Turn off for the app's own colors."
                else "Matching phone colors needs Android 12 or newer.",
            )
            Text("Theme", style = MaterialTheme.typography.bodyLarge)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("system" to "System", "light" to "Light", "dark" to "Dark", "amoled" to "AMOLED").forEach { (k, label) ->
                    FilterChip(selected = look.themeMode == k, onClick = { look.updateThemeMode(k) }, label = { Text(label) })
                }
            }
            Hint("System follows your phone's light/dark setting. AMOLED is dark mode with pure black backgrounds.")

            HorizontalDivider()
            Text("Custom colors", style = MaterialTheme.typography.titleMedium)
            SwitchRow("Use custom colors", look.customColorsOn) { look.updateCustomColorsOn(it) }
            Hint(
                "Pick your own color for each part of the app. Custom colors sit on top of the theme above; " +
                    "anything left on Default keeps the theme's color. Turning this off keeps your picks for later. " +
                    "Package status colors (blue in transit, green delivered, and so on) stay the same so they keep their meaning.",
            )
            if (look.customColorsOn) {
                ColorSlot.entries.forEach { slot ->
                    ColorSlotRow(slot, shown[slot] ?: Color.Gray, isCustom = slot in look.customColors) { editing = slot }
                }
                if (issues.isNotEmpty()) {
                    Column {
                        Text("Hard to read", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error)
                        issues.forEach { i ->
                            Text(
                                "${i.message}: contrast ${"%.1f".format(i.ratio)}:1 (aim for at least 4.5:1 for text)",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                if (look.customColors.isNotEmpty()) {
                    TextButton(onClick = { look.resetCustomColors() }) { Text("Reset all colors to default") }
                }
            }

            HorizontalDivider()
            Text("About", style = MaterialTheme.typography.titleMedium)
            Hint(
                "Tracking data comes from Trace (traceapi.dev). Maps use OpenFreeMap with data from " +
                    "OpenStreetMap contributors. Personal use only.",
            )
            Text("About the developer", style = MaterialTheme.typography.titleMedium)
            Text(
                "Shipments is a one-person project by ChickenMyBobbers. It started as a late-night attempt to get " +
                    "Parcel and Deliveries on Android and turned into a package tracker with the good parts of both, " +
                    "minus the ads, bulk, and nonsense. I hope you enjoy!",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    editing?.let { slot ->
        // key(): a fresh picker (and fresh starting color) for each area.
        key(slot) {
            ColorPickerDialog(
                title = slot.label,
                initial = (shown[slot] ?: Color.Gray).toArgb(),
                isCustom = slot in look.customColors,
                onApply = { look.updateCustomColor(slot, it); editing = null },
                onReset = { look.updateCustomColor(slot, null); editing = null },
                onDismiss = { editing = null },
            )
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
