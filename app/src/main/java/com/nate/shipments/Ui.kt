package com.nate.shipments

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun ShipmentsTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFFF2A93B))
        else -> lightColorScheme(primary = Color(0xFFB8741A))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

val Status.icon: ImageVector
    get() = when (this) {
        Status.PENDING, Status.NOT_FOUND, Status.EXPIRED -> Icons.Filled.Info
        Status.INFO_RECEIVED -> Icons.Filled.ShoppingCart
        Status.IN_TRANSIT -> Icons.Filled.Send
        Status.OUT_FOR_DELIVERY -> Icons.Filled.Home
        Status.AVAILABLE_FOR_PICKUP -> Icons.Filled.Place
        Status.DELIVERED -> Icons.Filled.CheckCircle
        Status.DELIVERY_FAILURE, Status.EXCEPTION -> Icons.Filled.Warning
    }

@Composable
fun StatusBadge(status: Status, size: Dp = 40.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(status.color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(status.icon, contentDescription = status.label, tint = status.color, modifier = Modifier.size(size * 0.5f))
    }
}

/** Four-segment bar: label created, shipped, out for delivery, delivered. */
@Composable
fun ProgressSteps(status: Status, modifier: Modifier = Modifier) {
    val reached = if (status == Status.PENDING || status == Status.NOT_FOUND) -1 else status.step
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(4) { i ->
            Box(
                Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(
                    if (i <= reached) status.color else MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    }
}

object Fmt {
    private val dayFmt = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)
    private val shortFmt = DateTimeFormatter.ofPattern("MMM d", Locale.US)
    private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    fun day(d: LocalDate): String {
        val today = LocalDate.now()
        return when (d) {
            today -> "Today"
            today.plusDays(1) -> "Tomorrow"
            today.minusDays(1) -> "Yesterday"
            else -> if (d.isAfter(today.minusDays(6)) && d.isBefore(today.plusDays(6)))
                d.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.US) else d.format(dayFmt)
        }
    }

    fun short(d: LocalDate): String = d.format(shortFmt)

    fun ago(millis: Long?): String {
        if (millis == null) return "never"
        val s = (System.currentTimeMillis() - millis) / 1000
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60}m ago"
            s < 86_400 -> "${s / 3600}h ago"
            else -> "${s / 86_400}d ago"
        }
    }

    /** The scan's date and time in the time zone where it happened, like carrier sites show. */
    fun eventDate(e: TrackEvent): LocalDate? =
        e.timeIso?.let { runCatching { OffsetDateTime.parse(it).toLocalDate() }.getOrNull() }
            ?: e.epoch?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }

    fun eventTime(e: TrackEvent): String? =
        e.timeIso?.let { runCatching { OffsetDateTime.parse(it).format(timeFmt) }.getOrNull() }
            ?: e.epoch?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(timeFmt) }

    /** "Arrives Friday", "Expected Oct 17 to 19", or "Delivered Tuesday". */
    fun eta(p: Parcel): String? {
        if (p.status == Status.DELIVERED) {
            val d = p.latest?.let { eventDate(it) } ?: return "Delivered"
            return "Delivered " + day(d).let { if (it == "Today" || it == "Yesterday") it.lowercase() else it }
        }
        val from = TrackParser.parseDate(p.etaFrom)
        val to = TrackParser.parseDate(p.etaTo)
        return when {
            from != null && to != null && from != to -> "Expected ${short(from)} to ${short(to)}"
            (to ?: from) != null -> "Arrives " + day((to ?: from)!!)
            else -> null
        }
    }

    fun subStatus(s: String?): String? {
        val tail = s?.substringAfter('_', "")?.takeIf { it.isNotEmpty() && it != "Other" } ?: return null
        return tail.replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase().replaceFirstChar { it.uppercase() }
    }
}
