package com.nate.shipments

import androidx.compose.ui.graphics.Color
import org.json.JSONArray
import org.json.JSONObject

/** 17TRACK's main statuses, plus PENDING for "added here, nothing back from 17TRACK yet". */
enum class Status(val label: String, val color: Color, val step: Int) {
    PENDING("Waiting for info", Color(0xFF8A94A6), 0),
    NOT_FOUND("Not found yet", Color(0xFF8A94A6), 0),
    INFO_RECEIVED("Label created", Color(0xFF6C8EBF), 0),
    IN_TRANSIT("In transit", Color(0xFF3D7BF7), 1),
    OUT_FOR_DELIVERY("Out for delivery", Color(0xFFF29A1F), 2),
    AVAILABLE_FOR_PICKUP("Ready for pickup", Color(0xFF9C5CF2), 2),
    DELIVERED("Delivered", Color(0xFF2FB561), 3),
    DELIVERY_FAILURE("Delivery attempted", Color(0xFFE5484D), 2),
    EXCEPTION("Problem", Color(0xFFE5484D), 1),
    EXPIRED("No updates", Color(0xFF8A94A6), 1);

    val isProblem get() = this == DELIVERY_FAILURE || this == EXCEPTION

    companion object {
        fun from17(s: String?): Status = when (s) {
            "NotFound" -> NOT_FOUND
            "InfoReceived" -> INFO_RECEIVED
            "InTransit" -> IN_TRANSIT
            "OutForDelivery" -> OUT_FOR_DELIVERY
            "AvailableForPickup" -> AVAILABLE_FOR_PICKUP
            "Delivered" -> DELIVERED
            "DeliveryFailure" -> DELIVERY_FAILURE
            "Exception" -> EXCEPTION
            "Expired" -> EXPIRED
            else -> PENDING
        }
    }
}

data class TrackEvent(
    /** Epoch millis for sorting; null when the carrier gave no usable time. */
    val epoch: Long?,
    /** ISO time as the carrier reported it (keeps the scan's own local time zone). */
    val timeIso: String?,
    val description: String,
    val location: String?,
    val lat: Double?,
    val lon: Double?,
    val subStatus: String?,
) {
    /** Identity used to tell "new event" from "same event seen again". */
    val key get() = "${epoch ?: timeIso}|$description"

    fun toJson() = JSONObject().apply {
        put("epoch", epoch ?: JSONObject.NULL)
        put("timeIso", timeIso ?: JSONObject.NULL)
        put("description", description)
        put("location", location ?: JSONObject.NULL)
        put("lat", lat ?: JSONObject.NULL)
        put("lon", lon ?: JSONObject.NULL)
        put("subStatus", subStatus ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject) = TrackEvent(
            epoch = o.longOrNull("epoch"),
            timeIso = o.str("timeIso"),
            description = o.str("description") ?: "",
            location = o.str("location"),
            lat = o.doubleOrNull("lat"),
            lon = o.doubleOrNull("lon"),
            subStatus = o.str("subStatus"),
        )
    }
}

data class Parcel(
    val id: String,
    val number: String,
    val name: String,
    /** 17TRACK carrier code; 0 = let 17TRACK auto-detect. */
    val carrier: Int = 0,
    val carrierName: String? = null,
    val registered: Boolean = false,
    val status: Status = Status.PENDING,
    val subStatus: String? = null,
    val events: List<TrackEvent> = emptyList(),
    val etaFrom: String? = null,
    val etaTo: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val lastChecked: Long? = null,
    val lastChanged: Long? = null,
    val archived: Boolean = false,
    val error: String? = null,
) {
    val latest: TrackEvent? get() = events.firstOrNull()
    val title get() = name.ifBlank { number }

    fun toJson() = JSONObject().apply {
        put("id", id); put("number", number); put("name", name)
        put("carrier", carrier); put("carrierName", carrierName ?: JSONObject.NULL)
        put("registered", registered); put("status", status.name)
        put("subStatus", subStatus ?: JSONObject.NULL)
        put("events", JSONArray().apply { events.forEach { put(it.toJson()) } })
        put("etaFrom", etaFrom ?: JSONObject.NULL); put("etaTo", etaTo ?: JSONObject.NULL)
        put("addedAt", addedAt)
        put("lastChecked", lastChecked ?: JSONObject.NULL)
        put("lastChanged", lastChanged ?: JSONObject.NULL)
        put("archived", archived); put("error", error ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject) = Parcel(
            id = o.getString("id"),
            number = o.getString("number"),
            name = o.str("name") ?: "",
            carrier = o.optInt("carrier", 0),
            carrierName = o.str("carrierName"),
            registered = o.optBoolean("registered", false),
            status = runCatching { Status.valueOf(o.getString("status")) }.getOrDefault(Status.PENDING),
            subStatus = o.str("subStatus"),
            events = o.optJSONArray("events")?.objects()?.map(TrackEvent::fromJson) ?: emptyList(),
            etaFrom = o.str("etaFrom"),
            etaTo = o.str("etaTo"),
            addedAt = o.optLong("addedAt", System.currentTimeMillis()),
            lastChecked = o.longOrNull("lastChecked"),
            lastChanged = o.longOrNull("lastChanged"),
            archived = o.optBoolean("archived", false),
            error = o.str("error"),
        )
    }
}

// org.json's optString returns the text "null" for JSON nulls, so use these instead.
fun JSONObject.str(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

fun JSONObject.longOrNull(key: String): Long? =
    if (!has(key) || isNull(key)) null else (opt(key) as? Number)?.toLong()

fun JSONObject.doubleOrNull(key: String): Double? =
    if (!has(key) || isNull(key)) null else (opt(key) as? Number)?.toDouble()
        ?: optString(key).toDoubleOrNull()

fun JSONObject.obj(key: String): JSONObject? = if (!has(key) || isNull(key)) null else optJSONObject(key)

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
