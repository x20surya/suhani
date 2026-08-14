package app.suhani.voice

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.suhani.AppLog
import app.suhani.Keys
import app.suhani.core.Ledger
import app.suhani.spikes.AndroidSqlDriver
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Custom reminders: exact alarms + Suhani's voice, pre-synthesized AT SET TIME
 * so the alarm speaks even with no network. sync() is idempotent - call it
 * after any turn that wrote, at app start, and after boot.
 */
object ReminderScheduler {

    private const val TAG = "suhani.reminder"

    fun ledger(context: Context): Ledger =
        Ledger(AndroidSqlDriver(File(context.applicationContext.filesDir, "silai.db")))

    fun sync(context: Context) {
        val app = context.applicationContext
        val ledger = ledger(app)
        val speech = Speech(Keys.sarvam(app), app.cacheDir, Warmup.http)
        val pending = ledger.listReminders(pendingOnly = true)
        AppLog.i(TAG, "sync: ${pending.size} pending reminder(s)")
        for (r in pending) {
            val id = (r["id"] as Number).toLong()
            if (runCatching { LocalDateTime.parse(r["due_at"] as String) }.getOrNull()
                    ?.isBefore(LocalDateTime.now(ZoneId.of("Asia/Kolkata"))) == true
            ) {
                AppLog.w(TAG, "#$id is past due (${r["due_at"]}) - not scheduling")
                continue
            }
            // Pre-synthesize her spoken line once, cache next to the बही.
            if (r["audio_path"] == null) {
                runCatching {
                    val line = "मालकिन, याद दिलाया था - ${r["content"]}"
                    val pcm = speech.synthesize(line)
                    val f = File(app.filesDir, "reminder_$id.pcm")
                    f.writeBytes(pcm)
                    ledger.setReminderAudio(id, f.absolutePath)
                }.onFailure { AppLog.w(TAG, "pre-synth failed for #$id: ${it.message}") }
            }
            schedule(app, id, r["due_at"] as String)
        }
    }

    fun canExact(context: Context): Boolean {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    fun schedule(context: Context, id: Long, dueAt: String) {
        val at = runCatching { LocalDateTime.parse(dueAt) }.getOrNull() ?: return
        val millis = at.atZone(ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli()
        if (millis <= System.currentTimeMillis()) return
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context, id.toInt(),
            Intent(context, ReminderReceiver::class.java).putExtra("reminder_id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            if (am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
                AppLog.i(TAG, "scheduled EXACT #$id at $dueAt")
            } else {
                // No exact-alarm permission: fire inexactly (minutes of drift)
                // rather than not at all, and SAY SO in the log.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
                AppLog.w(TAG, "scheduled INEXACT #$id at $dueAt - grant 'Alarms & reminders' for exact")
            }
        } catch (e: SecurityException) {
            AppLog.e(TAG, "exact alarm rejected for #$id - falling back inexact", e)
            runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi) }
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("reminder_id", -1)
        if (id < 0) return
        AppLog.i("suhani.reminder", "fired #$id")
        val ctx = context.applicationContext
        val ledger = ReminderScheduler.ledger(ctx)
        val r = ledger.getReminder(id) ?: return
        if ((r["done"] as Number).toInt() == 1) return
        ledger.markReminderDone(id)

        // Full-screen alarm notification (receivers may not start activities
        // directly on modern Android - this is the sanctioned path).
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(
            android.app.NotificationChannel(
                "suhani_alarms", "Reminders",
                android.app.NotificationManager.IMPORTANCE_HIGH,
            ).apply { setSound(null, null) }, // Suhani's voice IS the sound
        )
        val full = PendingIntent.getActivity(
            ctx, id.toInt(),
            Intent(ctx, app.suhani.ui.ReminderAlarmActivity::class.java)
                .putExtra("reminder_id", id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(
            2000 + id.toInt(),
            androidx.core.app.NotificationCompat.Builder(ctx, "suhani_alarms")
                .setContentTitle("Suhani - याद दिलाना था")
                .setContentText(r["content"] as String)
                .setSmallIcon(app.suhani.spikes.R.drawable.ic_launcher_monochrome)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
                .setCategory(androidx.core.app.NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(full, true)
                .setContentIntent(full)
                .setAutoCancel(true)
                .build(),
        )

        // Fallback screen wake: when full-screen intents are revoked (Android
        // 14+ default for sideloaded apps) the notification alone leaves the
        // screen dark - a wake lock physically lights it so her voice arrives
        // on a lit lock screen.
        runCatching {
            val pm = ctx.getSystemService(android.os.PowerManager::class.java)
            @Suppress("DEPRECATION")
            pm.newWakeLock(
                android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "suhani:reminder",
            ).acquire(10_000)
        }

        // Speak from the receiver (goAsync gives us the seconds we need) - the
        // cached clip plays on the ALARM channel so it cuts through media volume.
        val audioPath = r["audio_path"] as? String
        if (audioPath != null && File(audioPath).exists()) {
            val pending = goAsync()
            Thread {
                try {
                    playAlarmPcm(File(audioPath).readBytes())
                } catch (e: Exception) {
                    AppLog.w("suhani.reminder", "alarm audio failed: ${e.message}")
                } finally {
                    pending.finish()
                }
            }.start()
        }
    }

    private fun playAlarmPcm(pcm: ByteArray) {
        val track = android.media.AudioTrack.Builder()
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                android.media.AudioFormat.Builder()
                    .setSampleRate(Speech.RATE)
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(Speech.RATE)
            .setTransferMode(android.media.AudioTrack.MODE_STREAM)
            .build()
        track.play()
        var off = 0
        val frames = pcm.size / 2
        while (off < pcm.size) {
            val n = track.write(pcm, off, minOf(4800, pcm.size - off))
            if (n <= 0) break
            off += n
        }
        val deadline = System.currentTimeMillis() + 8000
        while (track.playbackHeadPosition < frames && System.currentTimeMillis() < deadline) {
            Thread.sleep(30)
        }
        track.stop()
        track.release()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Thread { runCatching { ReminderScheduler.sync(context) } }.start()
        }
    }
}
