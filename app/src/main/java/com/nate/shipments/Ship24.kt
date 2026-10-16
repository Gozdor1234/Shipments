package com.nate.shipments

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

class Ship24Exception(message: String, val http: Int? = null) : Exception(message)

/**
 * Minimal client for the Ship24 Tracking API (per-shipment plans, including the free one).
 * Auth is "Authorization: Bearer <key>". Creating a tracker uses one shipment from the
 * monthly allowance; fetching results for an existing tracker does not.
 */
class Ship24(private val key: String) {

    private fun call(method: String, path: String, body: JSONObject? = null): JSONObject {
        val conn = (URL("$BASE$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method // Android's HttpURLConnection accepts PATCH (desktop Java's doesn't)
            connectTimeout = 15_000
            readTimeout = 75_000 // first lookup of a new number can take up to a minute
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val http = conn.responseCode
            val text = (if (http in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (http !in 200..299) {
                val msg = json?.optJSONArray("errors")?.objects()?.firstOrNull()?.let { it.str("message") ?: it.str("code") }
                throw Ship24Exception(
                    when {
                        msg != null -> msg
                        http == 401 || http == 403 -> "Ship24 rejected the API key."
                        http == 429 -> "Ship24 limit reached. Try again later."
                        else -> "Ship24 returned HTTP $http."
                    },
                    http,
                )
            }
            return json ?: throw Ship24Exception("Ship24 sent an unreadable response.", http)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Create (or reuse) a tracker and get results in one call. Ship24 treats identical
     * requests as the same tracker, so this is only called once per package.
     */
    fun createAndTrack(number: String, courier: String?, clientId: String): JSONObject? {
        val body = JSONObject().put("trackingNumber", number).put("clientTrackerId", clientId)
        if (courier != null) body.put("courierCode", JSONArray().put(courier))
        return firstTracking(call("POST", "/public/v1/trackers/track", body))
    }

    fun results(trackerId: String): JSONObject? =
        firstTracking(call("GET", "/public/v1/trackers/$trackerId/results"))

    fun setCourier(trackerId: String, courier: String) {
        call("PATCH", "/public/v1/trackers/$trackerId", JSONObject().put("courierCode", JSONArray().put(courier)))
    }

    fun unsubscribe(trackerId: String) {
        call("PATCH", "/public/v1/trackers/$trackerId", JSONObject().put("isSubscribed", false))
    }

    fun couriers(): List<Carrier> =
        call("GET", "/public/v1/couriers").obj("data")?.optJSONArray("couriers")?.objects()?.mapNotNull {
            val code = it.str("courierCode") ?: return@mapNotNull null
            Carrier(code, it.str("courierName") ?: code, it.str("website"))
        }?.distinctBy { it.code } ?: emptyList()

    private fun firstTracking(resp: JSONObject) = resp.obj("data")?.optJSONArray("trackings")?.objects()?.firstOrNull()

    companion object {
        const val BASE = "https://api.ship24.com"
    }
}

/** What one Ship24 tracking result tells us, flattened for the app. */
data class TrackUpdate(
    val trackerId: String?,
    val status: Status,
    val statusCode: String?,
    val events: List<TrackEvent>,
    val etaFrom: String?,
    val etaTo: String?,
    val courier: String?,
)

object TrackParser {

    fun parse(tracking: JSONObject): TrackUpdate {
        val tracker = tracking.obj("tracker")
        val shipment = tracking.obj("shipment")
        val rawEvents = tracking.optJSONArray("events")?.objects() ?: emptyList()

        val events = rawEvents.mapNotNull { e ->
            val text = e.str("status") ?: return@mapNotNull null
            val time = e.str("occurrenceDatetime")
            Triple(
                TrackEvent(
                    epoch = parseEpoch(time),
                    timeIso = time,
                    description = text,
                    location = e.str("location"),
                    lat = null, lon = null,
                    subStatus = e.str("statusCode"),
                ),
                e.optInt("order", 0),
                e.str("courierCode"),
            )
        }
            .sortedWith(compareByDescending<Triple<TrackEvent, Int, String?>> { it.first.epoch ?: Long.MIN_VALUE }
                .thenByDescending { it.second })
            .distinctBy { it.first.key }

        val delivery = shipment?.obj("delivery")
        val window = delivery?.obj("courierEstimatedDeliveryDate")
        val single = delivery?.str("estimatedDeliveryDate")
        val trackerCourier = tracker?.let { t ->
            t.optJSONArray("courierCode")?.let { a -> (0 until a.length()).map { a.optString(it) }.firstOrNull { it.isNotBlank() } }
                ?: t.str("courierCode")
        }

        return TrackUpdate(
            trackerId = tracker?.str("trackerId"),
            status = Status.fromShip24(shipment?.str("statusMilestone")),
            statusCode = shipment?.str("statusCode"),
            events = events.map { it.first },
            etaFrom = window?.str("from") ?: single,
            etaTo = window?.str("to") ?: single,
            // The courier on the newest event is the last-mile carrier people recognise.
            courier = events.firstNotNullOfOrNull { it.third } ?: trackerCourier,
        )
    }

    /** Ship24 times may carry an offset, be UTC ("Z"), be local wall time, or be date-only. */
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
