package com.nate.shipments

import android.net.Uri

data class Carrier(val code: String, val name: String, val url: String?)

object Carriers {
    /**
     * Carrier codes Trace documents. Trace auto-detects common formats (1Z = UPS, 9... = USPS,
     * 7... = FedEx); picking one here forces it for numbers it can't place.
     */
    val all = listOf(
        Carrier("usps", "USPS", "https://www.usps.com/"),
        Carrier("ups", "UPS", "https://www.ups.com/"),
        Carrier("fedex", "FedEx", "https://www.fedex.com/"),
        Carrier("dhl", "DHL", "https://www.dhl.com/"),
    )

    fun name(code: String?): String? = all.firstOrNull { it.code == code }?.name

    /**
     * A local guess from the number's shape, only used as a hint in the add screen.
     * Trace does the real detection; these patterns are deliberately loose.
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

    /** The carrier's own tracking page when we know it, otherwise a 17TRACK web search. */
    fun trackingUrl(p: Parcel): Uri {
        val n = Uri.encode(p.number)
        val who = (p.carrierName ?: "").lowercase()
        val url = when {
            p.courier == "usps" || who == "usps" -> "https://tools.usps.com/go/TrackConfirmAction?tLabels=$n"
            who == "ups" || who.startsWith("ups ") -> "https://www.ups.com/track?tracknum=$n"
            who.startsWith("fedex") -> "https://www.fedex.com/fedextrack/?trknbr=$n"
            who.startsWith("dhl") -> "https://www.dhl.com/us-en/home/tracking.html?tracking-id=$n&submit=1"
            else -> "https://t.17track.net/en#nums=$n"
        }
        return Uri.parse(url)
    }
}
