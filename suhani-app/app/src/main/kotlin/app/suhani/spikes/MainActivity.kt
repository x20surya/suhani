package app.suhani.spikes

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "Suhani - Phase 0 spikes"
            textSize = 22f
        })
        root.addView(TextView(this).apply {
            text = "Run each spike on WiFi. Results show on screen; screenshots go to Surya."
            setPadding(0, pad / 2, 0, pad)
        })
        root.addView(Button(this).apply {
            text = "S1 - AEC echo test"
            setOnClickListener { startActivity(Intent(this@MainActivity, S1EchoActivity::class.java)) }
        })
        root.addView(Button(this).apply {
            text = "S3 - direct-path latency"
            setOnClickListener { startActivity(Intent(this@MainActivity, S3LatencyActivity::class.java)) }
        })
        root.addView(Button(this).apply {
            text = "Text chat (on-device brain)"
            setOnClickListener { startActivity(Intent(this@MainActivity, ChatActivity::class.java)) }
        })
        root.addView(Button(this).apply {
            text = "सुहानी - voice (P2)"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, app.suhani.voice.VoiceActivity::class.java))
            }
        })
        root.addView(Button(this).apply {
            text = "Voice enrollment (speaker gate)"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, app.suhani.ui.EnrollActivity::class.java))
            }
        })
        root.addView(Button(this).apply {
            text = "API key (rotate)"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, app.suhani.ui.SettingsActivity::class.java))
            }
        })
        root.addView(Button(this).apply {
            text = "Error log"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, app.suhani.ui.LogActivity::class.java))
            }
        })
        root.addView(Button(this).apply {
            text = "Alarm permission (Alarms & reminders)"
            setOnClickListener {
                runCatching {
                    startActivity(
                        Intent(
                            android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            android.net.Uri.parse("package:$packageName"),
                        ),
                    )
                }.onFailure {
                    startActivity(
                        Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:$packageName"),
                        ),
                    )
                }
            }
        })
        root.addView(Button(this).apply {
            text = "Full-screen alarm permission"
            setOnClickListener {
                runCatching {
                    startActivity(
                        Intent(
                            android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                            android.net.Uri.parse("package:$packageName"),
                        ),
                    )
                }.onFailure {
                    startActivity(
                        Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:$packageName"),
                        ),
                    )
                }
            }
        })
        root.addView(Button(this).apply {
            text = "Test 8am reminder now"
            setOnClickListener {
                androidx.work.WorkManager.getInstance(this@MainActivity).enqueue(
                    androidx.work.OneTimeWorkRequest.from(app.suhani.BriefingWorker::class.java),
                )
            }
        })
        if (app.suhani.Keys.sarvam(this).isBlank()) {
            root.addView(TextView(this).apply {
                text = "WARNING: SARVAM_API_KEY missing (built without repo .env)"
                setTextColor(0xFFCC0000.toInt())
            })
        }
        setContentView(root)
    }
}
