package app.suhani.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import app.suhani.core.Ledger
import app.suhani.spikes.AndroidSqlDriver
import app.suhani.spikes.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Foreground service (microphone type) hosting the conversation engine for the
 * duration of one session - started when she taps the orb, stopped on hang-up.
 * Keeps the loop alive when the screen blanks mid-conversation.
 */
class ConversationService : Service() {

    companion object {
        const val CHANNEL = "suhani_voice"
        var engine: ConversationEngine? = null
            private set
        var listenerProxy: ConversationEngine.Listener? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val binder = object : Binder() {}

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        if (engine == null && !starting) {
            starting = true
            // Engine construction loads two ONNX models (~30MB) and builds the
            // brain prompt - seconds of work that used to run on the MAIN
            // thread, freezing the UI at "Connecting...".
            scope.launch {
                val built = buildEngine()
                engine = built
                built.start()
                starting = false
            }
        }
        return START_NOT_STICKY
    }

    @Volatile
    private var starting = false

    private fun buildEngine(): ConversationEngine {
        val ledger = Ledger(AndroidSqlDriver(File(filesDir, "silai.db")))
        return ConversationEngine(
            this, app.suhani.Keys.sarvam(this), ledger, scope,
                object : ConversationEngine.Listener {
                    override fun onState(state: ConversationEngine.State) {
                        listenerProxy?.onState(state)
                    }
                    override fun onCaption(text: String) {
                        listenerProxy?.onCaption(text)
                    }
                    override fun onLevel(level: Float) {
                        listenerProxy?.onLevel(level)
                    }
                    override fun onSessionEnded() {
                        listenerProxy?.onSessionEnded()
                        stopSelf() // "ठीक है अभी के लिए" - hang up for real
                    }
                },
        )
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app out of recents ENDS the conversation - Suhani must
        // never keep listening and answering the room with nobody in the app.
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        engine?.stop()
        engine = null
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Suhani conversation", NotificationManager.IMPORTANCE_LOW),
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Suhani is listening")
            .setSmallIcon(app.suhani.spikes.R.drawable.ic_launcher_monochrome)
            .setOngoing(true)
            .build()
    }
}
