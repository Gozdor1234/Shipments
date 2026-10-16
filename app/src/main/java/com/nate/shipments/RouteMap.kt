package com.nate.shipments

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class MapStop(val lat: Double, val lon: Double, val label: String, val detail: String)

/**
 * Turns scan locations into map points. Trace gives locations as text, so the text
 * ("ROMULUS, MI, US") is geocoded on the phone and cached.
 */
object Geo {
    private const val PREFS = "geocache"

    @Suppress("DEPRECATION") // the callback API is 33+, and this always runs off the main thread
    private fun lookup(ctx: Context, query: String): Pair<Double, Double>? {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(query, null)?.let { cached ->
            if (cached.isEmpty()) return null
            val (a, b) = cached.split(",").map { it.toDouble() }
            return a to b
        }
        if (!Geocoder.isPresent()) return null
        val hit = runCatching { Geocoder(ctx, Locale.US).getFromLocationName(query, 1)?.firstOrNull() }
            .getOrElse { return null } // network failure: don't cache, try again later
        prefs.edit().putString(query, hit?.let { "${it.latitude},${it.longitude}" } ?: "").apply()
        return hit?.let { it.latitude to it.longitude }
    }

    /** Oldest-first stops, merging consecutive scans at the same place. */
    suspend fun stops(ctx: Context, events: List<TrackEvent>): List<MapStop> = withContext(Dispatchers.IO) {
        val out = mutableListOf<MapStop>()
        for (e in events.reversed()) {
            val point = if (e.lat != null && e.lon != null && !(e.lat == 0.0 && e.lon == 0.0)) e.lat to e.lon
            else e.location?.takeIf { it.isNotBlank() }?.let { lookup(ctx, it) } ?: continue
            val label = e.location ?: ""
            if (out.lastOrNull()?.label == label && label.isNotEmpty()) {
                out[out.lastIndex] = out.last().copy(detail = e.description)
            } else out += MapStop(point.first, point.second, label, e.description)
        }
        out
    }
}

private const val ASSET_HOST = "appassets.androidplatform.net"
private const val PAGE = "https://$ASSET_HOST/assets/route.html"

private fun hex(c: Color) = ColorMath.toHex(c.toArgb())
private fun mix(a: Color, b: Color, t: Float) = Color(ColorMath.mix(a.toArgb(), b.toArgb(), t))

/**
 * Vector route map (MapLibre + OpenFreeMap), repainted in the app's current theme colors.
 * With [interactive] = false it's a static preview that reports taps through [onTap],
 * so it doesn't fight the screen's scrolling.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun RouteMap(stops: List<MapStop>, color: Color, interactive: Boolean,
             modifier: Modifier = Modifier, onTap: () -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    val bg = cs.background
    val ink = cs.onSurface
    val dark = ColorMath.isDark(bg.toArgb())
    val theme = JSONObject()
        .put("interactive", interactive)
        .put("bg", hex(bg))
        .put("land", hex(mix(bg, ink, 0.035f)))
        .put("building", hex(mix(bg, ink, if (dark) 0.08f else 0.06f)))
        .put("water", hex(mix(bg, Color(0xFF4A7DB0), if (dark) 0.28f else 0.20f)))
        .put("road", hex(mix(bg, ink, if (dark) 0.14f else 0.10f)))
        .put("major", hex(mix(bg, ink, if (dark) 0.24f else 0.18f)))
        .put("boundary", hex(mix(bg, ink, 0.28f)))
        .put("label", hex(cs.onSurfaceVariant))
        .put("accent", hex(color))
        .put("past", hex(mix(bg, ink, 0.45f)))
        .toString()
    val stopsJson = JSONArray().apply {
        stops.forEach { put(JSONObject().put("lat", it.lat).put("lon", it.lon)) }
    }.toString()
    val tap = rememberUpdatedState(onTap)

    // A theme change rebuilds the map with the new colors.
    key(theme) {
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                val assets = WebViewAssetLoader.Builder()
                    .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx.applicationContext))
                    .build()
                WebView(ctx).apply {
                    // Without explicit MATCH_PARENT the page sees a zero viewport and the map never draws.
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    setBackgroundColor(bg.toArgb())
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    tag = stopsJson
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                            if (request.url.host == ASSET_HOST) assets.shouldInterceptRequest(request.url) else null

                        override fun onPageFinished(view: WebView, url: String?) {
                            view.evaluateJavascript("init($theme); setStops(${view.tag as String})", null)
                        }
                    }
                    if (!interactive) setOnTouchListener { _, ev ->
                        if (ev.action == MotionEvent.ACTION_UP) tap.value()
                        true
                    }
                    loadUrl(PAGE)
                }
            },
            update = { web ->
                if (web.tag != stopsJson) {
                    web.tag = stopsJson
                    web.evaluateJavascript("if (window.T) setStops($stopsJson)", null)
                }
            },
            onRelease = { it.destroy() },
        )
    }
}
