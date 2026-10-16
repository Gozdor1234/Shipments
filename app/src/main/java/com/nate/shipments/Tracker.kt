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

class MissingKeyException : Exception("Add your Ship24 API key in Settings first.")

object Tracker {

    /** Delivered packages stop being polled after this long. */
    private const val DELIVERED_GRACE_MS = 2L * 86_400_000

    fun newParcel(number: String, name: String, courier: String?, carrierName: String?) = Parcel(
        id = UUID.randomUUID().toString(),
        number = Carriers.clean(number),
        name = name.trim(),
        courier = courier,
        carrierName = carrierName,
    )

    private fun wantsRefresh(p: Parcel, now: Long) = !p.archived &&
        !(p.status == Status.DELIVERED && now - (p.lastChanged ?: p.addedAt) > DELIVERED_GRACE_MS)

    /**
     * Registers new numbers with Ship24 (uses one shipment from the allowance each), then
     * pulls results for everything still moving (or only [onlyIds] if given).
     * Returns the packages whose status or latest scan changed.
     */
    suspend fun refresh(ctx: Context, onlyIds: Set<String>? = null): List<Parcel> = withContext(Dispatchers.IO) {
        val key = Settings.apiKey(ctx) ?: throw MissingKeyException()
        val api = Ship24(key)
        val now = System.currentTimeMillis()
        val targets = Store.load(ctx).filter { if (onlyIds != null) it.id in onlyIds else wantsRefresh(it, now) }
        if (targets.isEmpty()) return@withContext emptyList()

        val updates = mutableMapOf<String, TrackUpdate>()
        val errors = mutableMapOf<String, Pair<String, Boolean>>() // id -> (message, tracker lost)
        for (p in targets) {
            try {
                val tracking = if (p.trackerId == null) api.createAndTrack(p.number, p.courier, p.id)
                else api.results(p.trackerId)
                if (tracking != null) updates[p.id] = TrackParser.parse(tracking)
                else errors[p.id] = "Ship24 returned no result for this number." to false
            } catch (e: Ship24Exception) {
                // A bad key affects every package, so surface it instead of tagging each one.
                if (e.http == 401 || e.http == 403) throw e
                errors[p.id] = (e.message ?: "Ship24 error") to (e.http == 404 && p.trackerId != null)
            }
        }

        val changed = mutableListOf<Parcel>()
        Store.update(ctx) { list ->
            list.map { p ->
                errors[p.id]?.let { (msg, lost) ->
                    return@map p.copy(lastChecked = now, error = msg, trackerId = if (lost) null else p.trackerId)
                }
                val u = updates[p.id] ?: return@map p
                val courier = u.courier ?: p.courier
                val newStatus = if (u.status == Status.PENDING && u.events.isEmpty() &&
                    now - p.addedAt > 86_400_000) Status.NOT_FOUND else u.status
                val isChange = newStatus != p.status || u.events.firstOrNull()?.key != p.latest?.key
                val next = p.copy(
                    trackerId = u.trackerId ?: p.trackerId,
                    status = newStatus,
                    subStatus = u.statusCode,
                    events = u.events.ifEmpty { p.events },
                    etaFrom = u.etaFrom,
                    etaTo = u.etaTo,
                    courier = courier,
                    carrierName = Carriers.name(ctx, courier) ?: p.carrierName,
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
