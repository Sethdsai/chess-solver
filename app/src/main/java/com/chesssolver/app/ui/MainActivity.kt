package com.chesssolver.app.ui

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.chesssolver.app.R
import com.chesssolver.app.service.ChessOverlayService

class MainActivity : AppCompatActivity() {

    companion object {
        private const val OVERLAY_PERMISSION_REQUEST_CODE = 1001
        private const val MEDIA_PROJECTION_REQUEST_CODE = 1002
    }

    private lateinit var btnStartStop: Button
    private lateinit var tvStatus: TextView
    private var isRunning = false
    private var mediaProjectionResultCode: Int = 0
    private var mediaProjectionResultData: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStartStop = findViewById(R.id.btnStartStop)
        tvStatus = findViewById(R.id.tvStatus)

        btnStartStop.setOnClickListener {
            if (!isRunning) {
                startSolver()
            } else {
                stopSolver()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Check if service is running
        val prefs = getSharedPreferences("chess_solver", Context.MODE_PRIVATE)
        isRunning = prefs.getBoolean("is_running", false)
        updateUI()
    }

    private fun startSolver() {
        // Step 1: Check overlay permission
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE)
            return
        }

        // Step 2: Request media projection (screen capture for continuous watching)
        requestMediaProjection()
    }

    private fun requestMediaProjection() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(
            projectionManager.createScreenCaptureIntent(),
            MEDIA_PROJECTION_REQUEST_CODE
        )
    }

    private fun launchOverlayService() {
        val serviceIntent = Intent(this, ChessOverlayService::class.java).apply {
            action = ChessOverlayService.ACTION_START
            putExtra(ChessOverlayService.EXTRA_RESULT_CODE, mediaProjectionResultCode)
            putExtra(ChessOverlayService.EXTRA_RESULT_DATA, mediaProjectionResultData)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        isRunning = true
        getSharedPreferences("chess_solver", Context.MODE_PRIVATE)
            .edit().putBoolean("is_running", true).apply()
        updateUI()
        Toast.makeText(this, "Chess Solver started! Open your chess app and tap 'My Move Now'", Toast.LENGTH_LONG).show()
    }

    private fun stopSolver() {
        val serviceIntent = Intent(this, ChessOverlayService::class.java).apply {
            action = ChessOverlayService.ACTION_STOP
        }
        startService(serviceIntent)

        isRunning = false
        getSharedPreferences("chess_solver", Context.MODE_PRIVATE)
            .edit().putBoolean("is_running", false).apply()
        updateUI()
    }

    private fun updateUI() {
        if (isRunning) {
            btnStartStop.text = "Stop Solver"
            btnStartStop.setBackgroundColor(getColor(R.color.colorAccent))
            tvStatus.text = "Solver: Running"
            tvStatus.setTextColor(getColor(R.color.colorAccent))
        } else {
            btnStartStop.text = "Start Solver"
            btnStartStop.setBackgroundColor(getColor(R.color.button_green))
            tvStatus.text = "Solver: Stopped"
            tvStatus.setTextColor(getColor(R.color.arrow_color))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            OVERLAY_PERMISSION_REQUEST_CODE -> {
                if (Settings.canDrawOverlays(this)) {
                    requestMediaProjection()
                } else {
                    Toast.makeText(this, "Overlay permission is required!", Toast.LENGTH_LONG).show()
                }
            }
            MEDIA_PROJECTION_REQUEST_CODE -> {
                if (resultCode == RESULT_OK && data != null) {
                    mediaProjectionResultCode = resultCode
                    mediaProjectionResultData = data
                    launchOverlayService()
                } else {
                    Toast.makeText(this, "Screen capture permission is required!", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
