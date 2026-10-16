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

    /** Background refresh interval in minutes. Android's floor is 15. */
    fun interval(ctx: Context): Int = prefs(ctx).getInt("interval", 30)
    fun setInterval(ctx: Context, v: Int) = prefs(ctx).edit().putInt("interval", v).apply()

    fun notify(ctx: Context): Boolean = prefs(ctx).getBoolean("notify", true)
    fun setNotify(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("notify", v).apply()

    fun askedNotifPermission(ctx: Context) = prefs(ctx).getBoolean("askedNotif", false)
    fun setAskedNotifPermission(ctx: Context) = prefs(ctx).edit().putBoolean("askedNotif", true).apply()
}
