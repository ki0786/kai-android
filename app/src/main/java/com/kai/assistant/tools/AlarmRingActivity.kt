package com.kai.assistant.tools

import android.app.Activity
import android.graphics.Color
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Full-screen ringing alarm. Stop ends the sound. Setting an alarm does not come here. */
class AlarmRingActivity : Activity() {
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        val id = intent.getIntExtra(LocalClockAlarmScheduler.EXTRA_ID, -1)
        if (id >= 0) {
            LocalClockAlarmScheduler(applicationContext).onFired(id)
        }
        setContentView(ringingLayout(clockLabel(hour(), minute())))
        startRinging()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val id = intent.getIntExtra(LocalClockAlarmScheduler.EXTRA_ID, -1)
        if (id >= 0) {
            LocalClockAlarmScheduler(applicationContext).onFired(id)
        }
        setContentView(ringingLayout(clockLabel(hour(), minute())))
    }

    override fun onDestroy() {
        ringtone?.stop()
        vibrator?.cancel()
        super.onDestroy()
    }

    private fun hour(): Int = intent.getIntExtra(LocalClockAlarmScheduler.EXTRA_HOUR, 0)

    private fun minute(): Int = intent.getIntExtra(LocalClockAlarmScheduler.EXTRA_MINUTE, 0)

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun ringingLayout(label: String): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setPadding(48, 48, 48, 48)
        }
        root.addView(TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 48f
            gravity = Gravity.CENTER
        })
        root.addView(Button(this).apply {
            text = "Stop"
            setOnClickListener { finish() }
        })
        return root
    }

    private fun startRinging() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        if (uri != null) {
            val tone = RingtoneManager.getRingtone(this, uri)
            tone.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                tone.isLooping = true
            }
            ringtone = tone
            tone.play()
        }
        val vib = getSystemService(Vibrator::class.java) ?: return
        vibrator = vib
        if (!vib.hasVibrator()) return
        vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0))
    }
}
