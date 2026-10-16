package com.nate.shipments

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit

class MissingKeyException : Exception("Add your 17TRACK API key in Settings first.")

object Tracker {

    /** Delivered packages stop being polled after this long. */
    private const val DELIVERED_GRACE_MS = 2L * 86_400_000

    fun newParcel(number: String, name: String, carrier: Int, carrierName: String?) = Parcel(
        id = UUID.randomUUID().toString(),
        number = Carriers.clean(number),
        name = name.trim(),
        carrier = carrier,
        carrierName = carrierName,
    )

    private fun wantsRefresh(p: Parcel, now: Long) = !p.archived &&
        !(p.status == Status.DELIVERED && now - (p.lastChanged ?: p.addedAt) > DELIVERED_GRACE_MS)

    /**
     * Registers any new numbers with 17TRACK, then pulls tracking for everything that's
     * still moving (or only [onlyIds] if given). Returns the packages whose status or
     * latest scan changed.
     */
    suspend fun refresh(ctx: Context, onlyIds: Set<String>? = null): List<Parcel> = withContext(Dispatchers.IO) {
        val key = Settings.apiKey(ctx) ?: throw MissingKeyException()
        val api = Track17(key)
        val now = System.currentTimeMillis()
        val targets = Store.load(ctx).filter { if (onlyIds != null) it.id in onlyIds else wantsRefresh(it, now) }
        if (targets.isEmpty()) return@withContext emptyList()

        // 1. Register numbers 17TRACK hasn't seen yet (this is the step that uses quota).
        val regResults = mutableMapOf<String, Pair<Int?, String?>>() // id -> (carrier, error)
        targets.filter { !it.registered }.chunked(40).forEach { chunk ->
            val res = api.register(chunk.map { it.number to it.carrier })
            for (p in chunk) {
                val ok = res.accepted.firstOrNull { it.str("number")?.equals(p.number, true) == true }
                val bad = res.rejected.firstOrNull { it.number.equals(p.number, true) }
                regResults[p.id] = when {
                    ok != null -> ok.optInt("carrier", p.carrier) to null
                    bad != null && bad.alreadyRegistered -> p.carrier to null
                    bad != null -> null to bad.message
                    else -> null to "17TRACK didn't accept this number."
                }
            }
        }
        if (regResults.isNotEmpty()) Store.update(ctx) { list ->
            list.map { p ->
                val (carrier, err) = regResults[p.id] ?: return@map p
                if (err != null) p.copy(error = err, lastChecked = now)
                else p.copy(registered = true, carrier = carrier ?: p.carrier, error = null)
            }
        }

        // 2. Fetch tracking for everything registered.
        val ready = Store.load(ctx).filter { p -> targets.any { it.id == p.id } && p.registered }
        val updates = mutableMapOf<String, TrackUpdate?>()
        val carriers = mutableMapOf<String, Int>()
        val errors = mutableMapOf<String, Rejection>()
        ready.chunked(40).forEach { chunk ->
            val res = api.trackInfo(chunk.map { it.number to it.carrier })
            for (p in chunk) {
                val entry = res.accepted.firstOrNull {
                    it.str("number")?.equals(p.number, true) == true &&
                        (p.carrier <= 0 || it.optInt("carrier", p.carrier) == p.carrier)
                } ?: res.accepted.firstOrNull { it.str("number")?.equals(p.number, true) == true }
                if (entry != null) {
                    updates[p.id] = TrackParser.parse(entry)
                    entry.optInt("carrier", 0).takeIf { it > 0 }?.let { carriers[p.id] = it }
                } else {
                    res.rejected.firstOrNull { it.number.equals(p.number, true) }?.let { errors[p.id] = it }
                }
            }
        }

        val changed = mutableListOf<Parcel>()
        Store.update(ctx) { list ->
            list.map { p ->
                if (p.id !in updates && p.id !in errors) return@map p
                errors[p.id]?.let { rej ->
                    // If 17TRACK lost the registration, re-register next time round.
                    return@map p.copy(lastChecked = now, error = rej.message, registered = !rej.notRegistered)
                }
                val u = updates[p.id] ?: return@map p.copy(lastChecked = now, error = null)
                val carrier = carriers[p.id] ?: p.carrier
                val newStatus = if (u.status == Status.NOT_FOUND && u.events.isEmpty() &&
                    now - p.addedAt < 86_400_000) Status.PENDING else u.status
                val isChange = newStatus != p.status || u.events.firstOrNull()?.key != p.latest?.key
                val next = p.copy(
                    status = newStatus,
                    subStatus = u.subStatus,
                    events = u.events.ifEmpty { p.events },
                    etaFrom = u.etaFrom,
                    etaTo = u.etaTo,
                    carrier = carrier,
                    carrierName = u.carrierName ?: p.carrierName ?: Carriers.name(ctx, carrier),
                    lastChecked = now,
                    lastChanged = if (isChange) now else p.lastChanged,
                    error = null,
                )
                // Ignore "changes" that are just a brand-new package getting its first info.
                if (isChange && p.lastChecked != null) changed += next
                next
            }
        }
        changed
    }

    // ---- notifications ----

    private const val CHANNEL = "updates"

    fun createChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Package updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A package's status or location changed"
            }
        )
    }

    fun notifyChanges(ctx: Context, changed: List<Parcel>) {
        if (!Settings.notify(ctx) || changed.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        createChannel(ctx)
        val nm = NotificationManagerCompat.from(ctx)
        for (p in changed) {
            val open = PendingIntent.getActivity(
                ctx, p.id.hashCode(),
                Intent(ctx, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_PARCEL, p.id)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val text = p.latest?.description ?: p.status.label
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(p.status.color.toArgb())
                .setContentTitle("${p.title}: ${p.status.label}")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    listOfNotNull(text, p.latest?.location).joinToString("\n")))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            try { nm.notify(p.id.hashCode(), n) } catch (_: SecurityException) { }
        }
    }

    // ---- background schedule ----

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<RefreshWorker>(Settings.interval(ctx).toLong(), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("refresh", ExistingPeriodicWorkPolicy.UPDATE, req)
    }
}

class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = try {
        val changed = Tracker.refresh(applicationContext)
        Tracker.notifyChanges(applicationContext, changed)
        Result.success()
    } catch (_: MissingKeyException) {
        Result.success() // nothing to do until a key is entered
    } catch (_: Exception) {
        Result.retry()
    }
}
