package app.suhani

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import app.suhani.core.Ledger
import app.suhani.spikes.AndroidSqlDriver
import java.io.File
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * App singleton: schedules the daily 8am briefing notification + nightly
 * DB backup - reminders.py reborn as WorkManager, no server awake at 8am.
 */
class SuhaniApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppLog.i("suhani.app", "app started")
        Thread { runCatching { app.suhani.voice.ReminderScheduler.sync(this) } }.start()
        val now = ZonedDateTime.now(Ledger.IST)
        var next = now.with(LocalTime.of(8, 0))
        if (!next.isAfter(now)) next = next.plusDays(1)
        val delay = Duration.between(now, next)

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "daily-briefing",
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<BriefingWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(delay.toMinutes(), TimeUnit.MINUTES)
                .setConstraints(Constraints.NONE)
                .build(),
        )
    }
}

class BriefingWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val dbFile = File(applicationContext.filesDir, "silai.db")
        if (!dbFile.exists()) return Result.success()
        val driver = AndroidSqlDriver(dbFile)
        val ledger = Ledger(driver)

        // Nightly WAL-safe backup via VACUUM INTO, keep the newest 14.
        runCatching {
            val dir = File(applicationContext.filesDir, "backups").apply { mkdirs() }
            val today = java.time.LocalDate.now(Ledger.IST).toString()
            val dest = File(dir, "silai-$today.db")
            if (!dest.exists()) driver.exec("VACUUM INTO '${dest.absolutePath.replace("'", "''")}'")
            dir.listFiles { f -> f.name.startsWith("silai-") }
                ?.sortedBy { it.name }?.dropLast(14)?.forEach { it.delete() }
        }

        // The 8am "आज क्या देना है" notification.
        runCatching {
            val b = ledger.dailyBriefing()
            @Suppress("UNCHECKED_CAST")
            val today = b["due_today"] as List<Map<String, Any?>>
            @Suppress("UNCHECKED_CAST")
            val tomorrow = b["due_tomorrow"] as List<Map<String, Any?>>
            @Suppress("UNCHECKED_CAST")
            val overdue = b["overdue"] as List<Map<String, Any?>>
            if (today.isEmpty() && tomorrow.isEmpty() && overdue.isEmpty()) return Result.success()
            val body = buildString {
                if (today.isNotEmpty()) {
                    append("Today: ")
                    append(today.joinToString(", ") { "${it["client_name"]} - ${it["garment"]}" })
                }
                if (tomorrow.isNotEmpty()) {
                    if (isNotEmpty()) append("\n")
                    append("Tomorrow: ")
                    append(tomorrow.joinToString(", ") { "${it["client_name"]} - ${it["garment"]}" })
                }
                if (overdue.isNotEmpty()) {
                    if (isNotEmpty()) append("\n")
                    append("Late: ${overdue.size}")
                }
            }
            val nm = applicationContext.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel("suhani_reminders", "Daily briefing", NotificationManager.IMPORTANCE_DEFAULT),
            )
            val open = android.app.PendingIntent.getActivity(
                applicationContext, 0,
                android.content.Intent(applicationContext, app.suhani.voice.VoiceActivity::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            nm.notify(
                2,
                NotificationCompat.Builder(applicationContext, "suhani_reminders")
                    .setContentTitle("Suhani - today's work")
                    .setContentText(body.replace('\n', ' '))
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setSmallIcon(app.suhani.spikes.R.drawable.ic_launcher_monochrome)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build(),
            )
        }
        return Result.success()
    }
}
