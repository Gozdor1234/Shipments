package com.nate.shipments

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.JavascriptInterface
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

/** One package's mapped route, plus scan locations that couldn't be placed (shown as a note, never silently dropped). */
data class RouteInfo(val stops: List<MapStop>, val unplaced: List<String>)

/**
 * Turns scan locations into map points. Trace gives locations as text ("ROMULUS, MI, US"),
 * so the text is looked up with the phone's geocoder, falling back to OpenStreetMap's
 * Nominatim when the phone can't find it. Hits are cached for good; misses only for a day,
 * so a one-off failure doesn't hide a stop forever.
 */
object Geo {
    private const val PREFS = "geocache2"
    private const val MISS_TTL_MS = 86_400_000L
    private var lastNominatim = 0L

    private fun lookup(ctx: Context, query: String): Pair<Double, Double>? {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(query, null)?.let { cached ->
            if (cached.startsWith("miss:")) {
                val at = cached.removePrefix("miss:").toLongOrNull() ?: 0L
                if (System.currentTimeMillis() - at < MISS_TTL_MS) return null
            } else {
                val parts = cached.split(",").mapNotNull { it.toDoubleOrNull() }
                if (parts.size == 2) return parts[0] to parts[1]
            }
        }
        var networkTrouble = false
        val hit = phoneGeocoder(ctx, query) { networkTrouble = true }
            ?: nominatim(query) { networkTrouble = true }
        when {
            hit != null -> prefs.edit().putString(query, "${hit.first},${hit.second}").apply()
            // Only remember a miss when the lookups actually answered "not found".
            !networkTrouble -> prefs.edit().putString(query, "miss:${System.currentTimeMillis()}").apply()
        }
        return hit
    }

    @Suppress("DEPRECATION") // the callback API is 33+, and this always runs off the main thread
    private fun phoneGeocoder(ctx: Context, query: String, onError: () -> Unit): Pair<Double, Double>? {
        if (!Geocoder.isPresent()) return null
        return try {
            Geocoder(ctx, Locale.US).getFromLocationName(query, 1)?.firstOrNull()?.let { it.latitude to it.longitude }
        } catch (_: Exception) { onError(); null }
    }

    /** OpenStreetMap's free geocoder. Its usage policy asks for at most one request a second and a real User-Agent. */
    private fun nominatim(query: String, onError: () -> Unit): Pair<Double, Double>? = synchronized(this) {
        val wait = 1100 - (System.currentTimeMillis() - lastNominatim)
        if (wait > 0) Thread.sleep(wait)
        lastNominatim = System.currentTimeMillis()
        return try {
            val url = java.net.URL("https://nominatim.openstreetmap.org/search?format=json&limit=1&q=" +
                java.net.URLEncoder.encode(query, "UTF-8"))
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 10_000; readTimeout = 15_000
                setRequestProperty("User-Agent", "Shipments/1.0 (personal Android package tracker)")
            }
            try {
                if (conn.responseCode != 200) { onError(); return null }
                val arr = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
                val o = arr.optJSONObject(0) ?: return null
                val lat = o.optString("lat").toDoubleOrNull() ?: return null
                val lon = o.optString("lon").toDoubleOrNull() ?: return null
                lat to lon
            } finally { conn.disconnect() }
        } catch (_: Exception) { onError(); null }
    }

    private fun pointOf(ctx: Context, e: TrackEvent): Pair<Double, Double>? =
        if (e.lat != null && e.lon != null && !(e.lat == 0.0 && e.lon == 0.0)) e.lat to e.lon
        else e.location?.takeIf { it.isNotBlank() }?.let { lookup(ctx, it) }

    /** Where each package is now (its newest scan that has a findable location), for the overview map. */
    suspend fun pins(ctx: Context, parcels: List<Parcel>): List<MapPin> = withContext(Dispatchers.IO) {
        parcels.mapNotNull { p ->
            val point = p.events.firstNotNullOfOrNull { pointOf(ctx, it) } ?: return@mapNotNull null
            MapPin(p.id, point.first, point.second, p.status.color, p.title)
        }
    }

    /** Geocode a typed address (e.g. the delivery address in Settings). Null if it can't be found. */
    suspend fun place(ctx: Context, query: String): Pair<Double, Double>? =
        withContext(Dispatchers.IO) { query.trim().takeIf { it.isNotEmpty() }?.let { lookup(ctx, it) } }

    /**
     * Oldest-first stops. Back-to-back scans at the same spot (within about 2 km, so
     * "SAINT PAUL, MN" and "Saint Paul, MN, US" count as one) become one stop.
     */
    suspend fun route(ctx: Context, events: List<TrackEvent>): RouteInfo = withContext(Dispatchers.IO) {
        val out = mutableListOf<MapStop>()
        val unplaced = mutableListOf<String>()
        for (e in events.reversed()) {
            val loc = e.location?.takeIf { it.isNotBlank() }
            val point = pointOf(ctx, e)
            if (point == null) { if (loc != null && loc !in unplaced) unplaced += loc; continue }
            val prev = out.lastOrNull()
            if (prev != null && Math.abs(prev.lat - point.first) < 0.02 && Math.abs(prev.lon - point.second) < 0.02) {
                out[out.lastIndex] = prev.copy(detail = e.description)
            } else out += MapStop(point.first, point.second, loc ?: "", e.description)
        }
        RouteInfo(out, unplaced)
    }
}

private const val ASSET_HOST = "appassets.androidplatform.net"
private const val PAGE = "https://$ASSET_HOST/assets/route.html"

private fun hex(c: Color) = ColorMath.toHex(c.toArgb())
private fun mix(a: Color, b: Color, t: Float) = Color(ColorMath.mix(a.toArgb(), b.toArgb(), t))

/** A package's current spot for the home overview map. */
data class MapPin(val id: String, val lat: Double, val lon: Double, val color: Color, val name: String)

/** The map colors for the current theme, as the page's init() expects them. */
@Composable
private fun themeJson(interactive: Boolean, accent: Color): String {
    val cs = MaterialTheme.colorScheme
    val bg = cs.background
    val ink = cs.onSurface
    val dark = ColorMath.isDark(bg.toArgb())
    return JSONObject()
        .put("interactive", interactive)
        .put("bg", hex(bg))
        .put("land", hex(mix(bg, ink, 0.035f)))
        .put("building", hex(mix(bg, ink, if (dark) 0.08f else 0.06f)))
        .put("water", hex(mix(bg, Color(0xFF4A7DB0), if (dark) 0.28f else 0.20f)))
        .put("road", hex(mix(bg, ink, if (dark) 0.14f else 0.10f)))
        .put("major", hex(mix(bg, ink, if (dark) 0.24f else 0.18f)))
        .put("boundary", hex(mix(bg, ink, 0.28f)))
        .put("label", hex(cs.onSurfaceVariant))
        .put("accent", hex(accent))
        .put("past", hex(mix(bg, ink, 0.45f)))
        .toString()
}

/**
 * One package's route (MapLibre + OpenFreeMap vector map), repainted in the app's theme.
 * With [interactive] = false it's a static preview that reports taps through [onTap],
 * so it doesn't fight the screen's scrolling.
 */
@Composable
fun RouteMap(stops: List<MapStop>, color: Color, interactive: Boolean, destination: Pair<Double, Double>?,
             modifier: Modifier = Modifier, onTap: () -> Unit = {}) {
    val data = JSONArray().apply {
        stops.forEach { put(JSONObject().put("lat", it.lat).put("lon", it.lon)) }
    }.toString()
    val dest = destination?.let { JSONObject().put("lat", it.first).put("lon", it.second).toString() } ?: "null"
    MapWebView(themeJson(interactive, color), "setStops($data, $dest)", interactive, modifier, onTap) {}
}

/**
 * Every package on the way as a dot in its status color, zoomed out only as far as needed
 * to fit them all. Interactive mode reports dot taps through [onOpen] with the package id.
 */
@Composable
fun OverviewMap(pins: List<MapPin>, interactive: Boolean, modifier: Modifier = Modifier,
                onTap: () -> Unit = {}, onOpen: (String) -> Unit = {}) {
    val data = JSONArray().apply {
        pins.forEach {
            put(JSONObject().put("id", it.id).put("lat", it.lat).put("lon", it.lon)
                .put("color", hex(it.color)).put("name", it.name))
        }
    }.toString()
    MapWebView(themeJson(interactive, MaterialTheme.colorScheme.primary), "setPackages($data)", interactive, modifier, onTap, onOpen)
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility", "JavascriptInterface")
@Composable
private fun MapWebView(theme: String, dataCall: String, interactive: Boolean, modifier: Modifier,
                       onTap: () -> Unit, onOpen: (String) -> Unit) {
    val tap = rememberUpdatedState(onTap)
    val open = rememberUpdatedState(onOpen)
    val bg = MaterialTheme.colorScheme.background

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
                    tag = dataCall
                    if (interactive) addJavascriptInterface(MapBridge(this) { id -> open.value(id) }, "ShipmentsMap")
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                            if (request.url.host == ASSET_HOST) assets.shouldInterceptRequest(request.url) else null

                        override fun onPageFinished(view: WebView, url: String?) {
                            view.evaluateJavascript("init($theme); ${view.tag as String}", null)
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
                if (web.tag != dataCall) {
                    web.tag = dataCall
                    web.evaluateJavascript("if (window.T) $dataCall", null)
                }
            },
            onRelease = { it.destroy() },
        )
    }
}

/** What the map page can call back into. Public class so the WebView bridge can reach it. */
class MapBridge(private val view: WebView, private val onOpen: (String) -> Unit) {
    /** Called on a WebView background thread; hops to the UI thread. */
    @JavascriptInterface
    fun open(id: String) { view.post { onOpen(id) } }
}
