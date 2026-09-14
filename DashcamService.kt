package com.personal.dashcam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.CamcorderProfile
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Runs camera recording in a foreground service so it keeps working while
 * another app (e.g. Google Maps) is full-screen in front of it.
 *
 * The camera has NO on-screen preview during background recording - it
 * writes straight from the camera sensor to a MediaRecorder Surface.
 * Recording is split into fixed-length clip files (default 5 min) instead
 * of one huge file, similar to a real dashcam.
 */
class DashcamService : Service() {

    companion object {
        private const val TAG = "DashcamService"
        const val ACTION_START = "com.personal.dashcam.action.START"
        const val ACTION_STOP = "com.personal.dashcam.action.STOP"
        const val ACTION_STATE_CHANGED = "com.personal.dashcam.action.STATE_CHANGED"
        const val EXTRA_RECORDING = "recording"
        const val EXTRA_ERROR = "error"
        const val NOTIFICATION_CHANNEL_ID = "dashcam_channel"
        const val NOTIFICATION_ID = 1001

        // Length of each clip file before starting a new one.
        const val CLIP_DURATION_MS = 5 * 60 * 1000L // 5 minutes

        @Volatile
        var isRecording = false
            private set
    }

    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var mediaRecorder: MediaRecorder? = null
    private var cameraId: String = "0"
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var backgroundThread: HandlerThread
    private lateinit var backgroundHandler: Handler
    private val uiHandler = Handler(android.os.Looper.getMainLooper())

    private var clipSwitchRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        backgroundThread = HandlerThread("DashcamBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread.looper)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRecordingAndService()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification("Starting camera..."),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                    else 0
                )
                backgroundHandler.post { openCameraAndStart() }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopEverything()
        broadcastState(false)
        backgroundThread.quitSafely()
        super.onDestroy()
    }

    // ---------- Camera setup ----------

    private fun pickBackCameraId(): String {
        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) return id
        }
        return cameraManager.cameraIdList.firstOrNull() ?: "0"
    }

    @Suppress("MissingPermission") // Permission is checked in MainActivity before starting service
    private fun openCameraAndStart() {
        cameraId = pickBackCameraId()

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Dashcam::RecordingLock")
        wakeLock?.acquire(12 * 60 * 60 * 1000L) // safety cap: 12 hours

        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    startNewClip()
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    cameraDevice = null
                }

                override fun onError(device: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    device.close()
                    cameraDevice = null
                    isRecording = false
                    uiHandler.post {
                        updateNotification("Camera error - stopped")
                        broadcastState(false, "Camera error ($error)")
                    }
                }
            }, backgroundHandler)
        } catch (e: SecurityException) {
            Log.e(TAG, "Camera permission missing", e)
            uiHandler.post { broadcastState(false, "Camera permission missing") }
            stopSelf()
        }
    }

    private fun buildMediaRecorder(outputFile: File): MediaRecorder {
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()

        recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)

        val camIdInt = cameraId.toIntOrNull() ?: 0
        val profile = when {
            CamcorderProfile.hasProfile(camIdInt, CamcorderProfile.QUALITY_720P) ->
                CamcorderProfile.get(camIdInt, CamcorderProfile.QUALITY_720P)
            CamcorderProfile.hasProfile(camIdInt, CamcorderProfile.QUALITY_HIGH) ->
                CamcorderProfile.get(camIdInt, CamcorderProfile.QUALITY_HIGH)
            else -> null
        }

        if (profile != null) {
            recorder.setProfile(profile)
        } else {
            // Fallback manual settings if no profile is available on this device
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setVideoSize(1280, 720)
            recorder.setVideoFrameRate(30)
            recorder.setVideoEncodingBitRate(6_000_000)
        }

        recorder.setOutputFile(outputFile.absolutePath)
        recorder.setMaxDuration(0) // we manage clip length ourselves
        recorder.prepare()
        return recorder
    }

    private fun clipsDir(): File {
        val dir = File(getExternalFilesDir(null), "DashcamClips")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun newClipFile(): File {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        return File(clipsDir(), "clip_$stamp.mp4")
    }

    private fun startNewClip() {
        val device = cameraDevice ?: return

        // Tear down any previous session/recorder first (used when swapping clips)
        try {
            captureSession?.close()
        } catch (_: Exception) {}
        captureSession = null

        val recorder = buildMediaRecorder(newClipFile())
        mediaRecorder = recorder
        val recorderSurface = recorder.surface

        val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        requestBuilder.addTarget(recorderSurface)
        requestBuilder.set(
            CaptureRequest.CONTROL_MODE,
            CaptureRequest.CONTROL_MODE_AUTO
        )

        device.createCaptureSession(
            listOf(recorderSurface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        session.setRepeatingRequest(requestBuilder.build(), null, backgroundHandler)
                        recorder.start()
                        isRecording = true
                        uiHandler.post {
                            updateNotification("Recording...")
                            broadcastState(true)
                        }
                        scheduleClipSwitch()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start recording", e)
                        uiHandler.post { broadcastState(false, "Failed to start recording") }
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Capture session config failed")
                    uiHandler.post { broadcastState(false, "Camera setup failed") }
                }
            },
            backgroundHandler
        )
    }

    private fun scheduleClipSwitch() {
        clipSwitchRunnable?.let { backgroundHandler.removeCallbacks(it) }
        val runnable = Runnable {
            finishCurrentClip()
            if (cameraDevice != null) startNewClip()
        }
        clipSwitchRunnable = runnable
        backgroundHandler.postDelayed(runnable, CLIP_DURATION_MS)
    }

    private fun finishCurrentClip() {
        try {
            mediaRecorder?.apply {
                stop()
                reset()
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finishing clip", e)
        }
        mediaRecorder = null
    }

    // ---------- Stop / cleanup ----------

    private fun stopRecordingAndService() {
        stopEverything()
        broadcastState(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopEverything() {
        clipSwitchRunnable?.let { backgroundHandler.removeCallbacks(it) }
        isRecording = false
        try {
            captureSession?.close()
        } catch (_: Exception) {}
        captureSession = null

        finishCurrentClip()

        try {
            cameraDevice?.close()
        } catch (_: Exception) {}
        cameraDevice = null

        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    // ---------- Notification ----------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Dashcam Recording",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Shows when the background dashcam is recording"
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, DashcamService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("I don't quit")
            .setSmallIcon(R.drawable.ic_blank)
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .addAction(0, "Stop", stopPendingIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ---------- State broadcast (keeps MainActivity's button in sync) ----------

    private fun broadcastState(recording: Boolean, error: String? = null) {
        val intent = Intent(ACTION_STATE_CHANGED).apply {
            setPackage(packageName)
            putExtra(EXTRA_RECORDING, recording)
            error?.let { putExtra(EXTRA_ERROR, it) }
        }
        sendBroadcast(intent)
    }
}
