package com.nate.shipments

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

class ShipmentsWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val active = Store.load(context)
            .filter { !it.archived && it.status != Status.DELIVERED }
            .sortedByDescending { it.lastChanged ?: it.addedAt }
        provideContent { GlanceTheme { WidgetBody(active) } }
    }
}

class ShipmentsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = ShipmentsWidget()
}

@Composable
private fun WidgetBody(parcels: List<Parcel>) {
    val fg = GlanceTheme.colors.onSurface
    val dim = GlanceTheme.colors.onSurfaceVariant
    Column(
        GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(20.dp).padding(12.dp)
            .clickable(actionStartActivity<MainActivity>())
    ) {
        Text(
            if (parcels.isEmpty()) "Shipments" else "Shipments · ${parcels.size}",
            style = TextStyle(color = fg, fontSize = 14.sp, fontWeight = FontWeight.Bold),
        )
        Spacer(GlanceModifier.height(6.dp))
        if (parcels.isEmpty()) {
            Text("Nothing on the way.", style = TextStyle(color = dim, fontSize = 13.sp))
        } else {
            LazyColumn {
                items(parcels, itemId = { it.id.hashCode().toLong() }) { p ->
                    Row(
                        GlanceModifier.fillMaxWidth().padding(vertical = 4.dp)
                            .clickable(actionStartActivity<MainActivity>()),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(GlanceModifier.size(10.dp).cornerRadius(5.dp)
                            .background(ColorProvider(p.status.color))) {}
                        Spacer(GlanceModifier.width(8.dp))
                        Column {
                            Text(p.title, maxLines = 1,
                                style = TextStyle(color = fg, fontSize = 13.sp, fontWeight = FontWeight.Medium))
                            Text(
                                listOfNotNull(p.status.label, etaShort(p)).joinToString(" · "),
                                maxLines = 1,
                                style = TextStyle(color = ColorProvider(p.status.color.copy(alpha = 0.95f)), fontSize = 12.sp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun etaShort(p: Parcel): String? {
    val d = TrackParser.parseDate(p.etaTo ?: p.etaFrom) ?: return null
    return "due " + Fmt.day(d)
}

