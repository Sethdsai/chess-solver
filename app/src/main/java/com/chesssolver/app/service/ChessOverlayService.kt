package com.chesssolver.app.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.chesssolver.app.detection.ChessBoardDetector
import com.chesssolver.app.engine.StockfishEngine
import com.chesssolver.app.overlay.ArrowOverlayView
import com.chesssolver.app.overlay.CalibrationOverlayView
import com.chesssolver.app.R
import kotlinx.coroutines.*

class ChessOverlayService : Service() {

    companion object {
        const val ACTION_START = "com.chesssolver.app.ACTION_START"
        const val ACTION_STOP = "com.chesssolver.app.ACTION_STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "chess_solver_channel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "ChessSolver"
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var latestBitmap: Bitmap? = null
    private val handlerThread = HandlerThread("ScreenCaptureThread")
    private var captureHandler: Handler? = null

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var arrowOverlayView: ArrowOverlayView? = null
    private var calibrationOverlayView: CalibrationOverlayView? = null
    private var statusTextView: TextView? = null
    private var engineLabelView: TextView? = null

    private val engine = StockfishEngine()
    private val detector = ChessBoardDetector()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var screenWidth = 0
    private var screenHeight = 0
    private var densityDpi = 0

    private var calibratedRect: Rect? = null

    @Volatile
    private var isProcessing = false

    override fun onCreate() {
        super.onCreate()
        handlerThread.start()
        captureHandler = Handler(handlerThread.looper)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val metrics = DisplayMetrics()
        windowManager!!.defaultDisplay.getMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        densityDpi = metrics.densityDpi

        // Load saved calibration
        val prefs = getSharedPreferences("chess_solver", Context.MODE_PRIVATE)
        val calLeft = prefs.getInt("cal_left", -1)
        val calTop = prefs.getInt("cal_top", -1)
        val calRight = prefs.getInt("cal_right", -1)
        val calBottom = prefs.getInt("cal_bottom", -1)
        if (calLeft >= 0 && calTop >= 0 && calRight > calLeft && calBottom > calTop) {
            calibratedRect = Rect(calLeft, calTop, calRight, calBottom)
        }

        // Initialize engine (async, won't block)
        Thread {
            val success = engine.initialize(this)
            Log.d(TAG, "Engine initialized: $success, using fallback: ${!engine.isInitialized || engine.useFallback}")
        }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_START) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_RESULT_DATA)
            }

            if (data != null) {
                createNotificationChannel()
                startForeground(NOTIFICATION_ID, createNotification())
                setupMediaProjection(resultCode, data)
                setupOverlay()
            }
        }

        return START_STICKY
    }

    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        val captureWidth = screenWidth
        val captureHeight = screenHeight

        imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)

        imageReader!!.setOnImageAvailableListener({ reader ->
            var image: Image? = null
            try {
                image = reader.acquireLatestImage()
                if (image != null && !isProcessing) {
                    val bitmap = imageToBitmap(image)
                    if (bitmap != null) {
                        val old = latestBitmap
                        latestBitmap = bitmap
                        old?.recycle()
                    }
                }
            } catch (e: Exception) {
                // Ignore
            } finally {
                image?.close()
            }
        }, captureHandler)

        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "ChessSolverScreenWatch",
            captureWidth, captureHeight, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, captureHandler
        )

        mediaProjection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, captureHandler)
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * screenWidth

        val bitmapWidth = screenWidth + rowPadding / pixelStride
        val bitmap = Bitmap.createBitmap(bitmapWidth, screenHeight, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)

        return if (rowPadding == 0) bitmap else {
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
            bitmap.recycle()
            cropped
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupOverlay() {
        val density = resources.displayMetrics.density

        // === Floating control panel ===
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 20; y = 100 }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (10 * density).toInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E0181818"))
                cornerRadius = 20f * density
            }
        }

        // Status line
        statusTextView = TextView(this).apply {
            text = "Ready"
            setTextColor(Color.parseColor("#8B949E"))
            textSize = 11f
            gravity = Gravity.CENTER
            val m = (4 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(m, 0, m, 2) }
        }
        container.addView(statusTextView)

        // Engine label
        engineLabelView = TextView(this).apply {
            text = if (engine.isInitialized) "Stockfish" else "Kotlin Engine"
            setTextColor(Color.parseColor("#484F58"))
            textSize = 9f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        container.addView(engineLabelView)

        // Button row
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val m = (2 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, m, 0, 0) }
        }

        val btnMyMove = Button(this).apply {
            text = "♞ Solve"
            setTextColor(Color.WHITE)
            textSize = 13f
            setAllCaps(false)
            background = GradientDrawable().apply { setColor(Color.parseColor("#238636")); cornerRadius = 14f * density }
            setPadding((14 * density).toInt(), (8 * density).toInt(), (14 * density).toInt(), (8 * density).toInt())
            setOnClickListener { if (!isProcessing) onMyMoveNow() }
        }

        val btnCalibrate = Button(this).apply {
            text = "◎"
            setTextColor(Color.WHITE)
            textSize = 13f
            setAllCaps(false)
            background = GradientDrawable().apply { setColor(Color.parseColor("#D29922")); cornerRadius = 14f * density }
            setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
            setOnClickListener { startCalibration() }
        }

        val btnAutoDetect = Button(this).apply {
            text = "⊞"
            setTextColor(Color.WHITE)
            textSize = 13f
            setAllCaps(false)
            background = GradientDrawable().apply { setColor(Color.parseColor("#58A6FF")); cornerRadius = 14f * density }
            setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
            setOnClickListener { autoDetectBoard() }
        }

        val btnClose = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams((28 * density).toInt(), (28 * density).toInt())
            setOnClickListener { stopSelf() }
        }

        buttonRow.addView(btnMyMove)
        buttonRow.addView(btnCalibrate)
        buttonRow.addView(btnAutoDetect)
        buttonRow.addView(btnClose)
        container.addView(buttonRow)
        overlayView = container

        // Drag handling
        var initialX = 0; var initialY = 0; var initialTouchX = 0f; var initialTouchY = 0f; var isDragging = false
        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { initialX = params.x; initialY = params.y; initialTouchX = event.rawX; initialTouchY = event.rawY; isDragging = false; true }
                MotionEvent.ACTION_MOVE -> { val dx = event.rawX - initialTouchX; val dy = event.rawY - initialTouchY; if (Math.abs(dx) > 10 || Math.abs(dy) > 10) isDragging = true; if (isDragging) { params.x = initialX + dx.toInt(); params.y = initialY + dy.toInt(); windowManager?.updateViewLayout(container, params) }; true }
                MotionEvent.ACTION_UP -> { if (isDragging) true else { container.performClick(); false } }
                else -> false
            }
        }
        windowManager?.addView(container, params)

        // Arrow overlay
        val arrowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        arrowOverlayView = ArrowOverlayView(this)
        windowManager?.addView(arrowOverlayView, arrowParams)

        // Calibration overlay (hidden initially)
        setupCalibrationOverlay()
    }

    private fun setupCalibrationOverlay() {
        val calParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        calibrationOverlayView = CalibrationOverlayView(this)
        calibrationOverlayView?.onCalibrationComplete = { rect ->
            calibratedRect = rect
            makeCalibrationNotTouchable()
            saveCalibration(rect)
            statusTextView?.text = "Calibrated ✓"
            engineLabelView?.text = if (engine.isInitialized) "Stockfish" else "Kotlin Engine"
            Toast.makeText(this, "Board saved! Tap ♞ Solve to find moves.", Toast.LENGTH_SHORT).show()
        }
        calibrationOverlayView?.onCalibrationCancelled = {
            makeCalibrationNotTouchable()
            statusTextView?.text = "Ready"
        }
        windowManager?.addView(calibrationOverlayView, calParams)
    }

    private fun makeCalibrationTouchable() {
        val calParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        try { windowManager?.updateViewLayout(calibrationOverlayView, calParams) } catch (e: Exception) { Log.e(TAG, "Error making calibration touchable", e) }
    }

    private fun makeCalibrationNotTouchable() {
        val calParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        try { windowManager?.updateViewLayout(calibrationOverlayView, calParams) } catch (e: Exception) { Log.e(TAG, "Error making calibration not touchable", e) }
    }

    private fun startCalibration() {
        statusTextView?.text = "Calibrating..."
        makeCalibrationTouchable()
        calibrationOverlayView?.startCalibration(calibratedRect)
    }

    private fun autoDetectBoard() {
        val bitmap = latestBitmap
        if (bitmap == null) {
            Toast.makeText(this, "No screen data yet.", Toast.LENGTH_SHORT).show()
            return
        }
        statusTextView?.text = "Scanning..."
        serviceScope.launch {
            val result = withContext(Dispatchers.Default) { detector.detectBoard(bitmap) }
            if (result != null) {
                calibratedRect = result.boardRect
                saveCalibration(result.boardRect)
                withContext(Dispatchers.Main) {
                    statusTextView?.text = "Auto-detected ✓"
                    Toast.makeText(this@ChessOverlayService, "Board detected! Tap ♞ Solve.", Toast.LENGTH_SHORT).show()
                }
            } else {
                withContext(Dispatchers.Main) {
                    statusTextView?.text = "Not found — calibrate"
                    Toast.makeText(this@ChessOverlayService, "Couldn't auto-detect. Tap ◎ to calibrate manually.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun saveCalibration(rect: Rect) {
        getSharedPreferences("chess_solver", Context.MODE_PRIVATE).edit().apply {
            putInt("cal_left", rect.left); putInt("cal_top", rect.top)
            putInt("cal_right", rect.right); putInt("cal_bottom", rect.bottom)
            apply()
        }
    }

    private fun onMyMoveNow() {
        val currentBitmap = latestBitmap
        if (currentBitmap == null) {
            Toast.makeText(this, "No screen data yet.", Toast.LENGTH_SHORT).show()
            return
        }

        isProcessing = true
        statusTextView?.text = "Analyzing..."

        serviceScope.launch {
            try {
                // Detect board
                val boardResult = withContext(Dispatchers.Default) {
                    if (calibratedRect != null) {
                        detector.detectBoardInRegion(currentBitmap, calibratedRect!!)
                    } else {
                        detector.detectBoard(currentBitmap)
                    }
                }

                if (boardResult == null) {
                    withContext(Dispatchers.Main) {
                        statusTextView?.text = "No board — tap ◎ or ⊞"
                        Toast.makeText(this@ChessOverlayService, "No board found.\nTap ◎ to calibrate or ⊞ to auto-detect.", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                val fen = withContext(Dispatchers.Default) { detector.boardToFEN(boardResult) }
                statusTextView?.text = "Thinking..."
                engineLabelView?.text = if (engine.isInitialized) "Stockfish..." else "Kotlin..."

                val bestMove = withContext(Dispatchers.Default) {
                    try {
                        if (engine.isInitialized) {
                            engine.getBestMove(fen, depth = 16, timeMs = 3000)
                        } else {
                            // Fallback
                            val ke = com.chesssolver.app.engine.ChessEngine()
                            ke.setPosition(fen)
                            ke.getBestMove(depth = 4, timeMs = 3000)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Engine error", e)
                        try {
                            val ke = com.chesssolver.app.engine.ChessEngine()
                            ke.setPosition(fen)
                            ke.getBestMove(depth = 4, timeMs = 2000)
                        } catch (e2: Exception) { null }
                    }
                }

                if (bestMove == null || bestMove.length < 4) {
                    withContext(Dispatchers.Main) {
                        statusTextView?.text = "No move found"
                        Toast.makeText(this@ChessOverlayService, "Engine couldn't find a move.", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    val from = bestMove.substring(0, 2)
                    val to = bestMove.substring(2, 4)
                    arrowOverlayView?.drawArrow(boardResult.boardRect, from, to, boardResult.isFlipped)
                    statusTextView?.text = "$from → $to"
                    engineLabelView?.text = if (engine.isInitialized) "Stockfish ✓" else "Kotlin ✓"
                }

                delay(5000)
                withContext(Dispatchers.Main) {
                    arrowOverlayView?.clearArrow()
                    statusTextView?.text = "Ready"
                    engineLabelView?.text = if (engine.isInitialized) "Stockfish" else "Kotlin Engine"
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusTextView?.text = "Error"
                    Toast.makeText(this@ChessOverlayService, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                isProcessing = false
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
            channel.description = "Chess Solver overlay"
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true).build()
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        overlayView?.let { windowManager?.removeView(it) }
        arrowOverlayView?.let { windowManager?.removeView(it) }
        calibrationOverlayView?.let { windowManager?.removeView(it) }
        handlerThread.quitSafely()
        engine.destroy()
        getSharedPreferences("chess_solver", Context.MODE_PRIVATE).edit().putBoolean("is_running", false).apply()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
