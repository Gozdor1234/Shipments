package com.nate.shipments

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Carrier(val code: String, val name: String, val url: String?)

object Carriers {
    /** Pinned to the top of the picker when found in Ship24's list (matched by name). */
    private val pinnedNames = listOf("USPS", "UPS", "FedEx", "DHL Express", "Amazon", "OnTrac", "LaserShip", "Canada Post")

    @Volatile private var all: List<Carrier>? = null

    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "couriers.json")

    /**
     * Ship24's courier list (1,000+), fetched with the user's key and cached for 30 days.
     * Returns an empty list if there's no key yet and nothing cached.
     */
    fun all(ctx: Context): List<Carrier> {
        all?.let { return it }
        val cache = cacheFile(ctx)
        val fresh = cache.exists() && System.currentTimeMillis() - cache.lastModified() < 30L * 86_400_000
        if (!fresh) {
            Settings.apiKey(ctx)?.let { key ->
                runCatching { Ship24(key).couriers() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { list ->
                    cache.writeText(JSONArray().apply {
                        list.forEach { put(JSONObject().put("code", it.code).put("name", it.name).put("url", it.url ?: JSONObject.NULL)) }
                    }.toString())
                    return list.also { all = it }
                }
            }
        }
        val cached = runCatching {
            JSONArray(cache.readText()).objects().map { Carrier(it.getString("code"), it.getString("name"), it.str("url")) }
        }.getOrNull()
        return (cached ?: emptyList()).also { if (cached != null) all = it }
    }

    fun clearCache(ctx: Context) { all = null; cacheFile(ctx).delete() }

    /** Popular carriers first, for the picker's default view. */
    fun pinned(list: List<Carrier>): List<Carrier> =
        pinnedNames.mapNotNull { n -> list.firstOrNull { it.name.equals(n, true) } ?: list.firstOrNull { it.name.startsWith(n, true) } }
            .distinctBy { it.code }

    fun name(ctx: Context, code: String?): String? {
        code ?: return null
        return (all ?: runCatching { all(ctx) }.getOrNull())?.firstOrNull { it.code == code }?.name
    }

    /**
     * A local guess from the number's shape, only used as a hint in the add screen.
     * Ship24 does the real detection; these patterns are deliberately loose.
     */
    fun guess(raw: String): String? {
        val n = clean(raw)
        return when {
            Regex("^1Z[0-9A-Z]{16}$").matches(n) -> "UPS"
            Regex("^TBA\\d{9,15}$").matches(n) -> "Amazon"
            Regex("^9[1-5]\\d{18,24}$").matches(n) -> "USPS"
            Regex("^420\\d{5}(\\d{4})?9[1-5]\\d{18,24}$").matches(n) -> "USPS"
            Regex("^[A-Z]{2}\\d{9}US$").matches(n) -> "USPS"
            Regex("^[A-Z]{2}\\d{9}CA$").matches(n) -> "Canada Post"
            Regex("^[CD]\\d{14}$").matches(n) -> "OnTrac"
            Regex("^1LS\\d{12,}$|^L[A-Z]\\d{8}$").matches(n) -> "LaserShip"
            Regex("^\\d{12}$|^\\d{15}$|^\\d{20}$").matches(n) -> "FedEx"
            Regex("^\\d{10}$").matches(n) -> "DHL Express"
            Regex("^[A-Z]{2}\\d{9}[A-Z]{2}$").matches(n) -> "International post"
            else -> null
        }
    }

    fun clean(raw: String) = raw.uppercase().filter { it.isLetterOrDigit() }

    /** Pull the first plausible tracking number out of shared text (an email, a message). */
    fun extract(text: String): String? {
        val tokens = Regex("[A-Za-z0-9][A-Za-z0-9 -]{6,40}[A-Za-z0-9]").findAll(text).map { it.value }
        tokens.map { clean(it) }.firstOrNull { guess(it) != null }?.let { return it }
        // Fall back to the longest letters+digits run that has digits in it.
        return Regex("[A-Za-z0-9]{8,40}").findAll(text).map { it.value.uppercase() }
            .filter { s -> s.count { it.isDigit() } >= 6 }.maxByOrNull { it.length }
    }

    /** The carrier's own tracking page when we know it, otherwise Ship24's public page. */
    fun trackingUrl(p: Parcel): Uri {
        val n = Uri.encode(p.number)
        val who = (p.carrierName ?: "").lowercase()
        val url = when {
            p.courier == "us-post" || who == "usps" -> "https://tools.usps.com/go/TrackConfirmAction?tLabels=$n"
            who == "ups" || who.startsWith("ups ") -> "https://www.ups.com/track?tracknum=$n"
            who.startsWith("fedex") -> "https://www.fedex.com/fedextrack/?trknbr=$n"
            who.startsWith("dhl") -> "https://www.dhl.com/us-en/home/tracking.html?tracking-id=$n&submit=1"
            else -> "https://www.ship24.com/tracking?p=$n"
        }
        return Uri.parse(url)
    }
}
