package com.nate.shipments

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.view.MotionEvent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import java.io.File
import java.util.Locale

data class MapStop(val point: GeoPoint, val label: String, val detail: String)

/**
 * Turns scan locations into map points. 17TRACK sometimes includes coordinates;
 * otherwise the location text ("ROMULUS, MI, US") is geocoded on the phone and cached.
 */
object Geo {
    private const val PREFS = "geocache"

    @Suppress("DEPRECATION") // the callback API is 33+, and this always runs off the main thread
    private fun lookup(ctx: Context, query: String): GeoPoint? {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(query, null)?.let { cached ->
            if (cached.isEmpty()) return null
            val (a, b) = cached.split(",").map { it.toDouble() }
            return GeoPoint(a, b)
        }
        if (!Geocoder.isPresent()) return null
        val hit = runCatching { Geocoder(ctx, Locale.US).getFromLocationName(query, 1)?.firstOrNull() }
            .getOrElse { return null } // network failure: don't cache, try again later
        prefs.edit().putString(query, hit?.let { "${it.latitude},${it.longitude}" } ?: "").apply()
        return hit?.let { GeoPoint(it.latitude, it.longitude) }
    }

    /** Oldest-first stops, merging consecutive scans at the same place. */
    suspend fun stops(ctx: Context, events: List<TrackEvent>): List<MapStop> = withContext(Dispatchers.IO) {
        val out = mutableListOf<MapStop>()
        for (e in events.reversed()) {
            val point = if (e.lat != null && e.lon != null && !(e.lat == 0.0 && e.lon == 0.0)) GeoPoint(e.lat, e.lon)
            else e.location?.takeIf { it.isNotBlank() }?.let { lookup(ctx, it) } ?: continue
            val label = e.location ?: ""
            if (out.lastOrNull()?.label == label && label.isNotEmpty()) {
                out[out.lastIndex] = out.last().copy(detail = e.description)
            } else out += MapStop(point, label, e.description)
        }
        out
    }
}

/**
 * OpenStreetMap view of the route. In [interactive] = false mode it's a static preview
 * that reports taps through [onTap], so it doesn't fight the screen's scrolling.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun RouteMap(stops: List<MapStop>, color: androidx.compose.ui.graphics.Color, interactive: Boolean,
             modifier: Modifier = Modifier, onTap: () -> Unit = {}) {
    val dark = isSystemInDarkTheme()
    val lineColor = color.toArgb()
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            Configuration.getInstance().apply {
                userAgentValue = ctx.packageName
                osmdroidBasePath = File(ctx.cacheDir, "osmdroid")
                osmdroidTileCache = File(osmdroidBasePath, "tiles")
            }
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(interactive)
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                setTilesScaledToDpi(true)
                if (dark) overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
                if (!interactive) setOnTouchListener { _, ev ->
                    if (ev.action == MotionEvent.ACTION_UP) onTap()
                    true
                }
            }
        },
        update = { map ->
            map.overlays.clear()
            if (stops.isEmpty()) return@AndroidView
            val pts = stops.map { it.point }
            if (pts.size > 1) map.overlays += Polyline(map).apply {
                setPoints(pts)
                outlinePaint.color = lineColor
                outlinePaint.strokeWidth = 8f
            }
            stops.forEachIndexed { i, s ->
                map.overlays += Marker(map).apply {
                    position = s.point
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = s.label
                    snippet = s.detail
                    if (i != stops.lastIndex) setAlpha(0.55f)
                }
            }
            map.post {
                if (pts.size == 1) {
                    map.controller.setZoom(10.0)
                    map.controller.setCenter(pts[0])
                } else {
                    val box = BoundingBox.fromGeoPointsSafe(pts)
                    map.zoomToBoundingBox(box, false, 80)
                    if (map.zoomLevelDouble > 12.0) map.controller.setZoom(12.0)
                }
            }
            map.invalidate()
        },
        onRelease = { it.onDetach() },
    )
}
