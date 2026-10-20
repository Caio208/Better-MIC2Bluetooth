package io.github.thrillfall.mic2bluetooth

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Foreground service that owns the microphone loopback, so it keeps running with the
 * screen off, on the lock screen / AOD, or with the app in the background.
 */
class LoopbackService : Service() {

    private var loopback: AudioLoopback? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var stopping = false
    private val handler = Handler(Looper.getMainLooper())

    // If the headphones disappear, audio would fall back to the speaker and howl: stop.
    private val headphonesGone = Runnable {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Routing.headphoneOutput(am) == null && !stopping) {
            lastError = getString(R.string.err_headphones_gone)
            notifyState()
            stopSelf()
        }
    }
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            handler.removeCallbacks(headphonesGone)
            handler.postDelayed(headphonesGone, 1500)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (loopback?.isRunning == true) return START_NOT_STICKY

        val mode = runCatching {
            LoopbackMode.valueOf(intent?.getStringExtra(EXTRA_MODE) ?: "")
        }.getOrDefault(LoopbackMode.PHONE_MIC)
        val raw = intent?.getBooleanExtra(EXTRA_RAW, false) ?: false

        startInForeground()

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mic2bluetooth:loopback")
            .apply { acquire() }

        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.registerAudioDeviceCallback(deviceCallback, handler)

        lastError = null
        val lb = AudioLoopback(applicationContext)
        loopback = lb
        running = true
        lb.start(
            mode, raw,
            onError = { msg ->
                lastError = msg
                notifyState()
                if (!stopping) stopSelf()
            },
            onEnded = { if (!stopping) stopSelf() }
        )
        notifyState()
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW
        ).apply {
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, LoopbackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        // Works from the lock screen without having to unlock the phone
        val stopAction = NotificationCompat.Action.Builder(0, getString(R.string.notif_stop), stopIntent)
            .setAuthenticationRequired(false)
            .build()

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.notif_title))
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(stopAction)
            .build()
        ServiceCompat.startForeground(
            this, NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
    }

    override fun onDestroy() {
        stopping = true
        handler.removeCallbacks(headphonesGone)
        runCatching {
            (getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                .unregisterAudioDeviceCallback(deviceCallback)
        }
        loopback?.stop()
        loopback = null
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        running = false
        level = 0f
        reductionDb = 0f
        notifyState()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_RAW = "raw"
        const val ACTION_STOP = "io.github.thrillfall.mic2bluetooth.STOP"
        private const val CHANNEL_ID = "loopback"
        private const val NOTIF_ID = 1

        @Volatile var running = false
        @Volatile var lastError: String? = null
        /** Linear amplification applied to the mic signal (1.0 = untouched). */
        @Volatile var gain = 1f
        /** Volume normalization: 0 off, 1 soft, 2 strong. */
        @Volatile var norm = 0
        /** Live output level (RMS 0..1) and normalization reduction (dB), for the UI. */
        @Volatile var level = 0f
        @Volatile var reductionDb = 0f
        /** Called on the main thread whenever running/error state changes. */
        @Volatile var onStateChange: ((String?) -> Unit)? = null

        private fun notifyState() {
            Handler(Looper.getMainLooper()).post { onStateChange?.invoke(lastError) }
        }
    }
}
