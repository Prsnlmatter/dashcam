package com.personal.dashcam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var startStopButton: Button
    private lateinit var clipsListView: ListView

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        startStopButton = findViewById(R.id.startStopButton)
        clipsListView = findViewById(R.id.clipsListView)

        startStopButton.setOnClickListener {
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

    override fun onResume() {
        super.onResume()
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
        val intent = Intent(this, DashcamService::class.java).apply { action = DashcamService.ACTION_START }
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, "Recording started - minimize and open Maps freely", Toast.LENGTH_LONG).show()
        refreshUi()
    }

    private fun stopRecordingService() {
        val intent = Intent(this, DashcamService::class.java).apply { action = DashcamService.ACTION_STOP }
        startService(intent)
        Toast.makeText(this, "Recording stopped", Toast.LENGTH_SHORT).show()
        refreshUi()
    }

    private fun refreshUi() {
        val recording = DashcamService.isRecording
        statusText.text = if (recording) "● Recording" else "Not recording"
        startStopButton.text = if (recording) "Stop Recording" else "Start Recording"
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
