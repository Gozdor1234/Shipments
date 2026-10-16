package com.nate.shipments

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

class Track17Exception(message: String, val code: Int? = null) : Exception(message)

data class Rejection(val number: String, val code: Int?, val message: String) {
    val alreadyRegistered get() = message.contains("already", ignoreCase = true)
    val notRegistered get() = message.contains("not register", ignoreCase = true) ||
        message.contains("no register", ignoreCase = true)
}

class Batch(val accepted: List<JSONObject>, val rejected: List<Rejection>)

/**
 * Minimal client for 17TRACK's Tracking API v2.2.
 * Every call is a POST with a JSON array body and the key in the "17token" header.
 * Only /register uses up quota; /gettrackinfo on an already-registered number is free.
 */
class Track17(private val key: String) {

    private fun post(path: String, body: String): JSONObject {
        val conn = (URL("$BASE/$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("17token", key)
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val http = conn.responseCode
            val text = (if (http in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            val json = runCatching { JSONObject(text) }.getOrNull()
                ?: throw Track17Exception(
                    if (http == 401 || http == 403) "17TRACK rejected the API key (HTTP $http)."
                    else "17TRACK returned HTTP $http."
                )
            val code = json.optInt("code", 0)
            if (code != 0) {
                val msg = json.obj("data")?.optJSONArray("errors")?.objects()?.firstOrNull()?.str("message")
                    ?: json.str("message") ?: "17TRACK error $code"
                throw Track17Exception(msg, code)
            }
            return json
        } finally {
            conn.disconnect()
        }
    }

    private fun batch(path: String, items: JSONArray): Batch {
        val data = post(path, items.toString()).obj("data") ?: return Batch(emptyList(), emptyList())
        val ok = data.optJSONArray("accepted")?.objects() ?: emptyList()
        val bad = data.optJSONArray("rejected")?.objects()?.map {
            val err = it.obj("error")
            Rejection(it.str("number") ?: "", err?.optInt("code"), err?.str("message") ?: "Rejected by 17TRACK")
        } ?: emptyList()
        return Batch(ok, bad)
    }

    private fun item(number: String, carrier: Int) = JSONObject().apply {
        put("number", number)
        if (carrier > 0) put("carrier", carrier)
    }

    fun register(items: List<Pair<String, Int>>) = batch("register", JSONArray().apply {
        items.forEach { (n, c) -> put(item(n, c).apply { if (c <= 0) put("auto_detection", true) }) }
    })

    fun trackInfo(items: List<Pair<String, Int>>) =
        batch("gettrackinfo", JSONArray().apply { items.forEach { (n, c) -> put(item(n, c)) } })

    fun delete(number: String, carrier: Int) = batch("deletetrack", JSONArray().put(item(number, carrier)))

    fun changeCarrier(number: String, old: Int, new: Int) = batch("changecarrier", JSONArray().put(
        JSONObject().put("number", number).put("carrier_old", old).put("carrier_new", new)
    ))

    /** Account quota; the exact field set varies, so the raw "data" object is returned. */
    fun quota(): JSONObject = post("getquota", "[]").obj("data") ?: JSONObject()

    companion object {
        const val BASE = "https://api.17track.net/track/v2.2"
    }
}

/** What one /gettrackinfo entry tells us, already flattened for the app. */
data class TrackUpdate(
    val status: Status,
    val subStatus: String?,
    val events: List<TrackEvent>,
    val etaFrom: String?,
    val etaTo: String?,
    val carrierName: String?,
)

object TrackParser {

    fun parse(entry: JSONObject): TrackUpdate? {
        val info = entry.obj("track_info") ?: return null
        val latestStatus = info.obj("latest_status")
        val providers = info.obj("tracking")?.optJSONArray("providers")?.objects() ?: emptyList()

        // A package can have several providers (e.g. origin post + last-mile carrier).
        // Merge all their events, newest first, dropping exact duplicates.
        val events = providers
            .flatMap { p -> p.optJSONArray("events")?.objects()?.mapNotNull(::event) ?: emptyList() }
            .distinctBy { it.key }
            .sortedWith(compareByDescending<TrackEvent> { it.epoch ?: Long.MIN_VALUE })

        val eta = info.obj("time_metrics")?.obj("estimated_delivery_date")
        // Name the last-mile carrier, which is the one people recognise.
        val carrierName = providers.lastOrNull()?.obj("provider")?.let { it.str("name") ?: it.str("alias") }

        return TrackUpdate(
            status = Status.from17(latestStatus?.str("status")),
            subStatus = latestStatus?.str("sub_status"),
            events = events,
            etaFrom = eta?.str("from"),
            etaTo = eta?.str("to"),
            carrierName = carrierName,
        )
    }

    private fun event(o: JSONObject): TrackEvent? {
        val description = o.str("description") ?: return null
        val iso = o.str("time_iso")
        val utc = o.str("time_utc")
        val addr = o.obj("address")
        val coords = addr?.obj("coordinates")
        val location = o.str("location") ?: addr?.let {
            listOfNotNull(it.str("city"), it.str("state"), it.str("country")).joinToString(", ").ifBlank { null }
        }
        return TrackEvent(
            epoch = parseEpoch(iso) ?: parseEpoch(utc),
            timeIso = iso ?: utc,
            description = description,
            location = location,
            lat = coords?.doubleOrNull("latitude"),
            lon = coords?.doubleOrNull("longitude"),
            subStatus = o.str("sub_status"),
        )
    }

    fun parseEpoch(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        runCatching { return OffsetDateTime.parse(s).toInstant().toEpochMilli() }
        runCatching { return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli() }
        return null
    }

    fun parseDate(s: String?): LocalDate? {
        if (s.isNullOrBlank()) return null
        runCatching { return OffsetDateTime.parse(s).toLocalDate() }
        runCatching { return LocalDateTime.parse(s).toLocalDate() }
        return runCatching { LocalDate.parse(s.take(10)) }.getOrNull()
    }
}
