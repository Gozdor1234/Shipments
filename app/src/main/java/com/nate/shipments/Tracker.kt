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

class MissingKeyException : Exception("Add your Trace API key in Settings first.")

object Tracker {

    /** Delivered packages stop being polled after this long. */
    private const val DELIVERED_GRACE_MS = 2L * 86_400_000
    private const val DAY_MS = 86_400_000L

    fun newParcel(number: String, name: String, courier: String?, carrierName: String?) = Parcel(
        id = UUID.randomUUID().toString(),
        number = Carriers.clean(number),
        name = name.trim(),
        courier = courier,
        carrierName = carrierName,
    )

    private fun wantsRefresh(p: Parcel, now: Long, minAgeMs: Long): Boolean {
        if (p.archived) return false
        if (p.status == Status.DELIVERED && now - (p.lastChanged ?: p.addedAt) > DELIVERED_GRACE_MS) return false
        // Skip anything checked recently (e.g. by a pull-to-refresh) to save lookups.
        return now - (p.lastChecked ?: 0) >= minAgeMs
    }

    /**
     * Looks up every package that's still moving (or only [onlyIds] if given). Each package
     * checked uses one Trace lookup, so packages checked within [minAgeMs] are skipped.
     * Returns the packages whose status or latest scan changed.
     */
    suspend fun refresh(ctx: Context, onlyIds: Set<String>? = null, minAgeMs: Long = 0): List<Parcel> =
        withContext(Dispatchers.IO) {
            val key = Settings.apiKey(ctx) ?: throw MissingKeyException()
            val api = Trace(key)
            val now = System.currentTimeMillis()
            val targets = Store.load(ctx).filter {
                if (onlyIds != null) it.id in onlyIds else wantsRefresh(it, now, minAgeMs)
            }
            if (targets.isEmpty()) return@withContext emptyList()

            val updates = mutableMapOf<String, TrackUpdate>()
            val noInfo = mutableSetOf<String>()
            val errors = mutableMapOf<String, String>()
            var fatal: Exception? = null
            for (p in targets) {
                try {
                    val r = api.track(p.number, p.courier)
                    Settings.countLookup(ctx)
                    updates[p.id] = TrackParser.parse(r)
                } catch (e: TraceException) {
                    when (e.code) {
                        // Account-wide problems: stop, save what we have, then report.
                        "unauthorized", "monthly_quota_exceeded" -> { fatal = e; break }
                        "rate_limited" -> break // the rest get picked up next round
                        "tracking_sources_unavailable" -> noInfo += p.id
                        else -> errors[p.id] = e.message ?: "Trace error"
                    }
                    if (e.http == 401) { fatal = e; break }
                } catch (e: java.io.IOException) {
                    errors[p.id] = "Couldn't reach Trace: ${e.message ?: "network error"}"
                }
            }

            val changed = mutableListOf<Parcel>()
            Store.update(ctx) { list ->
                list.map { p ->
                    when {
                        p.id in noInfo -> p.copy(
                            lastChecked = now, error = null,
                            status = if (p.events.isNotEmpty()) p.status
                            else if (now - p.addedAt > 2 * DAY_MS) Status.NOT_FOUND else Status.PENDING,
                        )
                        p.id in errors -> p.copy(lastChecked = now, error = errors[p.id])
                        p.id in updates -> {
                            val u = updates.getValue(p.id)
                            val isChange = u.status != p.status || u.events.firstOrNull()?.key != p.latest?.key
                            val next = p.copy(
                                status = u.status,
                                subStatus = null,
                                events = u.events.ifEmpty { p.events },
                                etaFrom = u.etaFrom,
                                etaTo = u.etaTo,
                                carrierName = u.carrierName ?: Carriers.name(p.courier) ?: p.carrierName,
                                lastChecked = now,
                                lastChanged = if (isChange) now else p.lastChanged,
                                error = null,
                            )
                            // Ignore "changes" that are just a brand-new package getting its first info.
                            if (isChange && p.events.isNotEmpty()) changed += next
                            next
                        }
                        else -> p
                    }
                }
            }
            fatal?.let { throw it }
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
        val changed = Tracker.refresh(applicationContext, minAgeMs = (Settings.interval(applicationContext) - 10) * 60_000L)
        Tracker.notifyChanges(applicationContext, changed)
        Result.success()
    } catch (_: MissingKeyException) {
        Result.success() // nothing to do until a key is entered
    } catch (_: TraceException) {
        Result.success() // bad key or quota used up: retrying soon won't help
    } catch (_: Exception) {
        Result.retry()
    }
}
