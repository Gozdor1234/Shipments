package com.nate.shipments

import android.content.Context
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import java.io.File

/**
 * All packages, kept in one JSON file in app-private storage. The app, the background
 * worker and the widget run in the same process and share this one copy.
 */
object Store {
    private val state = MutableStateFlow<List<Parcel>>(emptyList())
    val parcels: StateFlow<List<Parcel>> = state
    private val mutex = Mutex()
    @Volatile private var loaded = false

    private fun file(ctx: Context) = File(ctx.filesDir, "parcels.json")

    fun load(ctx: Context): List<Parcel> {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                state.value = runCatching {
                    val f = file(ctx)
                    if (f.exists()) JSONArray(f.readText()).objects().map(Parcel::fromJson) else emptyList()
                }.getOrDefault(emptyList())
                loaded = true
            }
        }
        return state.value
    }

    fun get(ctx: Context, id: String) = load(ctx).firstOrNull { it.id == id }

    /** Apply a change to the list, save it, and refresh the widget. */
    suspend fun update(ctx: Context, transform: (List<Parcel>) -> List<Parcel>) {
        mutex.withLock {
            load(ctx)
            val next = transform(state.value)
            state.value = next
            val f = file(ctx)
            val tmp = File(f.parentFile, "parcels.json.tmp")
            tmp.writeText(JSONArray().apply { next.forEach { put(it.toJson()) } }.toString())
            tmp.renameTo(f) // atomic replace, so a crash mid-write can't corrupt the list
        }
        runCatching { ShipmentsWidget().updateAll(ctx) }
    }

    suspend fun edit(ctx: Context, id: String, change: (Parcel) -> Parcel) =
        update(ctx) { list -> list.map { if (it.id == id) change(it) else it } }
}

object Settings {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun apiKey(ctx: Context): String? = prefs(ctx).getString("apiKey", null)?.trim()?.takeIf { it.isNotEmpty() }
    fun setApiKey(ctx: Context, v: String) = prefs(ctx).edit().putString("apiKey", v.trim()).apply()

    /**
     * Background refresh interval in minutes. Every check uses a Trace lookup, so the
     * default (3 hours) keeps a handful of packages well inside 1,000 lookups a month.
     */
    fun interval(ctx: Context): Int = prefs(ctx).getInt("interval", 180).let { if (it < 60) 180 else it }
    fun setInterval(ctx: Context, v: Int) = prefs(ctx).edit().putInt("interval", v).apply()

    /** Lookups this app has used this calendar month (Trace's own count is the real one). */
    fun lookupsThisMonth(ctx: Context): Int {
        val p = prefs(ctx)
        return if (p.getString("lookupMonth", null) == month()) p.getInt("lookups", 0) else 0
    }
    @Synchronized fun countLookup(ctx: Context) {
        val n = lookupsThisMonth(ctx) + 1
        prefs(ctx).edit().putString("lookupMonth", month()).putInt("lookups", n).apply()
    }
    private fun month() = java.time.YearMonth.now().toString()

    /** Delivery address and its map point, for the dotted "still to go" line on route maps. */
    fun homeAddress(ctx: Context): String = prefs(ctx).getString("homeAddress", "") ?: ""
    fun home(ctx: Context): Pair<Double, Double>? {
        val p = prefs(ctx)
        if (!p.contains("homeLat")) return null
        return p.getFloat("homeLat", 0f).toDouble() to p.getFloat("homeLon", 0f).toDouble()
    }
    fun setHome(ctx: Context, address: String, point: Pair<Double, Double>?) {
        val e = prefs(ctx).edit().putString("homeAddress", address.trim())
        if (point == null) e.remove("homeLat").remove("homeLon")
        else e.putFloat("homeLat", point.first.toFloat()).putFloat("homeLon", point.second.toFloat())
        e.apply()
    }

    fun notify(ctx: Context): Boolean = prefs(ctx).getBoolean("notify", true)
    fun setNotify(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("notify", v).apply()

    fun askedNotifPermission(ctx: Context) = prefs(ctx).getBoolean("askedNotif", false)
    fun setAskedNotifPermission(ctx: Context) = prefs(ctx).edit().putBoolean("askedNotif", true).apply()
}
