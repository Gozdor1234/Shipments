package com.nate.shipments

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class Carrier(val key: Int, val name: String, val url: String?)

object Carriers {
    const val USPS = 21051
    const val UPS = 100002
    const val FEDEX = 100003
    const val DHL = 100001
    const val ONTRAC = 100049
    const val LASERSHIP = 100052
    const val CANADA_POST = 3041

    /** Shown at the top of the picker; codes confirmed against 17TRACK's carrier list. */
    val common = listOf(
        Carrier(USPS, "USPS", "https://www.usps.com/"),
        Carrier(UPS, "UPS", "https://www.ups.com/"),
        Carrier(FEDEX, "FedEx", "https://www.fedex.com/"),
        Carrier(DHL, "DHL Express", "https://www.dhl.com/"),
        Carrier(ONTRAC, "OnTrac", "https://www.ontrac.com/"),
        Carrier(LASERSHIP, "LaserShip", "https://www.lasership.com/"),
        Carrier(CANADA_POST, "Canada Post", "https://www.canadapost-postescanada.ca/"),
    )

    private const val LIST_URL = "https://res.17track.net/asset/carrier/info/apicarrier.all.json"
    @Volatile private var all: List<Carrier>? = null

    /** The full 17TRACK carrier list (~3,000), downloaded once and cached on the phone. */
    fun all(ctx: Context): List<Carrier> {
        all?.let { return it }
        val cache = File(ctx.filesDir, "carriers.json")
        val text = runCatching {
            if (cache.exists() && System.currentTimeMillis() - cache.lastModified() < 30L * 86_400_000) {
                cache.readText()
            } else {
                val conn = URL(LIST_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000; conn.readTimeout = 30_000
                conn.inputStream.bufferedReader().use { it.readText() }.also { cache.writeText(it) }
            }
        }.getOrElse { if (cache.exists()) cache.readText() else null }
        val parsed = text?.let { t ->
            runCatching {
                JSONArray(t).objects().mapNotNull { o ->
                    val key = o.optInt("key", 0)
                    val name = o.str("_name") ?: return@mapNotNull null
                    if (key > 0) Carrier(key, name, o.str("_url")) else null
                }.distinctBy { it.key }
            }.getOrNull()
        }
        return (parsed ?: common).also { if (parsed != null) all = it }
    }

    fun name(ctx: Context, key: Int): String? =
        common.firstOrNull { it.key == key }?.name ?: all?.firstOrNull { it.key == key }?.name
            ?: if (key > 0) runCatching { all(ctx).firstOrNull { it.key == key }?.name }.getOrNull() else null

    /**
     * A local guess from the number's shape, only used as a hint in the add screen.
     * 17TRACK does the real detection; these patterns are deliberately loose.
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

    fun trackingUrl(ctx: Context, p: Parcel): Uri {
        val n = Uri.encode(p.number)
        val url = when (p.carrier) {
            USPS -> "https://tools.usps.com/go/TrackConfirmAction?tLabels=$n"
            UPS -> "https://www.ups.com/track?tracknum=$n"
            FEDEX -> "https://www.fedex.com/fedextrack/?trknbr=$n"
            DHL -> "https://www.dhl.com/us-en/home/tracking/tracking-express.html?submit=1&tracking-id=$n"
            else -> "https://t.17track.net/en#nums=$n"
        }
        return Uri.parse(url)
    }
}
