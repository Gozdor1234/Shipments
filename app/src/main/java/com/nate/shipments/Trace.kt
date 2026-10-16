package com.nate.shipments

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/** [code] is Trace's error code, e.g. "monthly_quota_exceeded" or "tracking_sources_unavailable". */
class TraceException(message: String, val http: Int, val code: String?) : Exception(message)

/**
 * Minimal client for the Trace package tracking API.
 * Auth is "Authorization: Bearer trc_...". Every /v1/track call (even a cached one)
 * counts as one lookup against the monthly allowance (1,000 on the free plan);
 * the sandbox endpoint doesn't, so it's used to test the key.
 */
class Trace(private val key: String) {

    private fun post(path: String, body: JSONObject): JSONObject {
        val conn = (URL("$BASE$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 90_000 // live lookups drive a browser on Trace's side and can be slow
            doOutput = true
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val http = conn.responseCode
            val text = (if (http in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (http !in 200..299) {
                val err = json?.obj("error")
                val code = err?.str("code")
                val msg = when (code) {
                    "unauthorized" -> "Trace rejected the API key."
                    "monthly_quota_exceeded" -> "This month's 1,000 Trace lookups are used up."
                    "rate_limited" -> "Too many lookups in a minute. Try again shortly."
                    "tracking_sources_unavailable" -> "No tracking info from the carrier yet."
                    else -> err?.str("message") ?: "Trace returned HTTP $http."
                }
                throw TraceException(msg, http, code)
            }
            return json ?: throw TraceException("Trace sent an unreadable response.", http, null)
        } finally {
            conn.disconnect()
        }
    }

    /** One live (or up-to-30-minute cached) lookup. Uses one lookup from the allowance. */
    fun track(number: String, carrier: String?): JSONObject {
        val body = JSONObject().put("tracking_number", number)
        if (carrier != null) body.put("carrier", carrier)
        return post("/v1/track", body)
    }

    /** Free test call: proves the key works without using a lookup. */
    fun testKey() {
        post("/v1/sandbox/track", JSONObject().put("scenario", "delivered"))
    }

    companion object {
        const val BASE = "https://api.traceapi.dev"
    }
}

/** What one Trace result tells us, flattened for the app. */
data class TrackUpdate(
    val status: Status,
    val events: List<TrackEvent>,
    val etaFrom: String?,
    val etaTo: String?,
    val carrierCode: String?,
    val carrierName: String?,
)

object TrackParser {

    fun parse(r: JSONObject): TrackUpdate {
        val carrier = r.obj("carrier")
        val events = (r.optJSONArray("events")?.objects() ?: emptyList()).mapNotNull { e ->
            val text = e.str("description") ?: e.str("status") ?: return@mapNotNull null
            val utc = e.str("timestamp")
            // Prefer the scan's local time (how carrier sites show it) when it has an offset.
            val local = e.str("timestamp_local")?.takeIf { runCatching { OffsetDateTime.parse(it) }.isSuccess }
            TrackEvent(
                epoch = parseEpoch(utc) ?: parseEpoch(local) ?: parseEpoch(e.str("timestamp_local")),
                timeIso = local,
                description = text,
                location = e.str("location"),
                lat = null, lon = null,
                subStatus = e.str("status"),
            )
        }
            .distinctBy { it.key }
            .sortedWith(compareByDescending<TrackEvent> { it.epoch ?: Long.MIN_VALUE })

        // estimated_delivery is documented as nullable; accept a plain date/time or a from/to object.
        var from: String? = null
        var to: String? = null
        when (val eta = r.opt("estimated_delivery")) {
            is String -> if (eta.isNotBlank()) { from = eta; to = eta }
            is JSONObject -> {
                from = eta.str("from") ?: eta.str("date") ?: eta.str("start")
                to = eta.str("to") ?: eta.str("end") ?: from
            }
        }

        return TrackUpdate(
            status = Status.fromTrace(r.str("status")),
            events = events,
            etaFrom = from,
            etaTo = to,
            carrierCode = carrier?.str("code"),
            carrierName = carrier?.str("name"),
        )
    }

    fun parseEpoch(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        runCatching { return OffsetDateTime.parse(s).toInstant().toEpochMilli() }
        runCatching { return LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }
        runCatching { return LocalDate.parse(s).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
        return null
    }

    fun parseDate(s: String?): LocalDate? {
        if (s.isNullOrBlank()) return null
        runCatching { return OffsetDateTime.parse(s).toLocalDate() }
        runCatching { return LocalDateTime.parse(s).toLocalDate() }
        return runCatching { LocalDate.parse(s.take(10)) }.getOrNull()
    }
}
