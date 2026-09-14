package com.personal.dashcam

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var startStopButton: Button
    private lateinit var clipsListView: ListView

    // True while we've asked the service to start/stop but haven't heard
    // back yet - button stays disabled during this window to avoid double-taps.
    private var waitingForConfirmation = false

    private val requiredPermissions: Array<String>
        get() {
            val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            return perms.toTypedArray()
        }

    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            startRecordingService()
        } else {
            Toast.makeText(this, "Camera and microphone permissions are required", Toast.LENGTH_LONG).show()
        }
    }

    // Receives the actual state (recording started / stopped / failed) from the service.
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            waitingForConfirmation = false
            val error = intent?.getStringExtra(DashcamService.EXTRA_ERROR)
            if (error != null) {
                Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
            }
            refreshUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        startStopButton = findViewById(R.id.startStopButton)
        clipsListView = findViewById(R.id.clipsListView)

        startStopButton.setOnClickListener {
            if (waitingForConfirmation) return@setOnClickListener // ignore taps mid-transition

            if (DashcamService.isRecording) {
                stopRecordingService()
            } else {
                requestPermissionsAndStart()
            }
        }

        clipsListView.setOnItemClickListener { _, _, position, _ ->
            openClip(clipFiles()[position])
        }

        clipsListView.setOnItemLongClickListener { _, _, position, _ ->
            deleteClip(clipFiles()[position])
            true
        }

        refreshUi()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(DashcamService.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stateReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(stateReceiver)
        } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        // In case state changed while this screen wasn't visible at all
        // (e.g. app was fully closed and reopened), sync immediately.
        waitingForConfirmation = false
        refreshUi()
    }

    private fun requestPermissionsAndStart() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startRecordingService()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startRecordingService() {
        waitingForConfirmation = true
        refreshUi() // shows "Starting..." immediately, button disabled
        val intent = Intent(this, DashcamService::class.java).apply { action = DashcamService.ACTION_START }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopRecordingService() {
        waitingForConfirmation = true
        refreshUi() // shows "Stopping..." immediately, button disabled
        val intent = Intent(this, DashcamService::class.java).apply { action = DashcamService.ACTION_STOP }
        startService(intent)
    }

    private fun refreshUi() {
        val recording = DashcamService.isRecording

        startStopButton.isEnabled = !waitingForConfirmation

        when {
            waitingForConfirmation && !recording -> {
                statusText.text = "Starting..."
                startStopButton.text = "Starting..."
            }
            waitingForConfirmation && recording -> {
                statusText.text = "Stopping..."
                startStopButton.text = "Stopping..."
            }
            recording -> {
                statusText.text = "\u25CF Recording"
                startStopButton.text = "Stop Recording"
            }
            else -> {
                statusText.text = "Not recording"
                startStopButton.text = "Start Recording"
            }
        }

        loadClipsList()
    }

    private fun clipsDir(): File = File(getExternalFilesDir(null), "DashcamClips")

    private fun clipFiles(): List<File> {
        val dir = clipsDir()
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.extension == "mp4" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    private fun loadClipsList() {
        val files = clipFiles()
        val labels = files.map { "${it.name}  (${it.length() / (1024 * 1024)} MB)" }
        clipsListView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
    }

    private fun openClip(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Open clip"))
    }

    private fun deleteClip(file: File) {
        if (file.delete()) {
            Toast.makeText(this, "Deleted ${file.name}", Toast.LENGTH_SHORT).show()
            loadClipsList()
        }
    }
}
