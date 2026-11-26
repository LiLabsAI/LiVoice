package com.aliahad.wovoice.voice

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.aliahad.wovoice.R
import java.io.File

class VoiceCaptureService : Service() {
    interface Listener {
        fun onRecordingStarted()
        fun onLevel(rms: Float)
        fun onRecordingComplete(result: WavRecorder.RecordingResult)
        fun onRecordingError(message: String)
    }

    inner class LocalBinder : Binder() {
        fun startCapture(listener: Listener): Boolean = this@VoiceCaptureService.startCapture(listener)
        fun finishCapture() = recorder?.finish()
        fun cancelCapture() = cancelCaptureInternal()
    }

    private val binder = LocalBinder()
    private val mainHandler = android.os.Handler(Looper.getMainLooper())
    private var recorder: WavRecorder? = null
    private var listener: Listener? = null

    override fun onCreate() {
        super.onCreate()
        cleanupStaleRecordings()
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (recorder != null) {
            if (!startMicrophoneForeground()) {
                val activeListener = listener
                cancelCaptureInternal()
                activeListener?.onRecordingError("Microphone access could not be started. Open LiVoice settings and try again.")
            }
        } else {
            // Avoid leaving a stuck "LiVoice is listening" notification when capture was
            // cancelled before onStartCommand arrived, while still honoring Android's
            // startForegroundService contract if requested.
            if (intent?.getBooleanExtra(EXTRA_FOREGROUND_REQUEST, false) == true) {
                startMicrophoneForeground()
            }
            leaveForeground()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cancelCaptureInternal()
        mainHandler.removeCallbacksAndMessages(null)
        cleanupStaleRecordings()
        super.onDestroy()
    }

    private fun startCapture(newListener: Listener): Boolean {
        if (recorder != null) return false
        if (!hasRecordAudioPermission()) {
            newListener.onRecordingError("Allow microphone access in LiVoice settings.")
            return false
        }
        if (!startMicrophoneForeground()) {
            newListener.onRecordingError("The microphone could not start. Open LiVoice settings and try again.")
            return false
        }
        listener = newListener
        val target = File(cacheDir, "$FILE_PREFIX${System.currentTimeMillis()}.wav")
        lateinit var capture: WavRecorder
        capture = WavRecorder(applicationContext, target, object : WavRecorder.Callback {
            override fun onStarted() = post {
                if (recorder !== capture) return@post
                listener?.onRecordingStarted()
            }
            override fun onLevel(rms: Float) = post {
                if (recorder !== capture) return@post
                listener?.onLevel(rms)
            }
            override fun onComplete(result: WavRecorder.RecordingResult) = post {
                if (recorder !== capture) {
                    result.file.delete()
                    return@post
                }
                recorder = null
                val currentListener = listener
                listener = null
                leaveForeground()
                currentListener?.onRecordingComplete(result)
            }
            override fun onError(message: String) = post {
                if (recorder !== capture) return@post
                recorder = null
                val currentListener = listener
                listener = null
                leaveForeground()
                currentListener?.onRecordingError(message)
            }
        })
        recorder = capture
        capture.start()
        return true
    }

    private fun cancelCaptureInternal() {
        recorder?.cancel()
        recorder = null
        listener = null
        leaveForeground()
    }

    private fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startMicrophoneForeground(): Boolean {
        if (!hasRecordAudioPermission()) return false
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic_small)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.recording_notification))
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        return try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                foregroundType,
            )
            true
        } catch (_: SecurityException) {
            false
        } catch (error: RuntimeException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && error is ForegroundServiceStartNotAllowedException) {
                false
            } else {
                false
            }
        }
    }

    private fun leaveForeground() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.recording_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun cleanupStaleRecordings() {
        cacheDir.listFiles { file -> file.name.startsWith(FILE_PREFIX) }?.forEach(File::delete)
    }

    companion object {
        const val EXTRA_FOREGROUND_REQUEST = "com.aliahad.wovoice.EXTRA_FOREGROUND_REQUEST"
        private const val CHANNEL_ID = "wovoice_recording"
        private const val NOTIFICATION_ID = 42
        private const val FILE_PREFIX = "active-voice-"
    }
}
