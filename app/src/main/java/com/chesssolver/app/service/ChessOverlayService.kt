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
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import com.chesssolver.app.detection.ChessBoardDetector
import com.chesssolver.app.engine.StockfishEngine
import com.chesssolver.app.overlay.ArrowOverlayView
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

    private val engine = StockfishEngine()
    private val detector = ChessBoardDetector()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var screenWidth = 0
    private var screenHeight = 0
    private var densityDpi = 0

    // Continuously track the latest frame
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

        // Initialize Stockfish engine
        engine.ensureInitialized(this)
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

        // Create ImageReader that receives continuous frames from virtual display
        // We use a lower resolution for performance while still being accurate enough
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
                        // Keep the latest frame always available
                        val old = latestBitmap
                        latestBitmap = bitmap
                        old?.recycle()
                    }
                }
            } catch (e: Exception) {
                // Ignore frame processing errors
            } finally {
                image?.close()
            }
        }, captureHandler)

        // Create virtual display that continuously mirrors the screen
        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "ChessSolverScreenWatch",
            captureWidth, captureHeight, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, captureHandler
        )

        // Register callback to know when projection stops
        mediaProjection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
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

        // Crop to remove padding
        return if (rowPadding == 0) {
            bitmap
        } else {
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
            bitmap.recycle()
            cropped
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupOverlay() {
        // Create floating button overlay
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 100
        }

        // Create overlay layout programmatically
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#CC000000"))
            val pad = (4 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            gravity = Gravity.CENTER_VERTICAL
        }

        // Rounded background
        val bgDrawable = GradientDrawable().apply {
            setColor(Color.parseColor("#CC000000"))
            cornerRadius = 20f * resources.displayMetrics.density
        }
        container.background = bgDrawable

        val btnMyMove = Button(this).apply {
            text = "My Move Now"
            setTextColor(Color.WHITE)
            textSize = 13f
            setAllCaps(false)
            val moveBg = GradientDrawable().apply {
                setColor(Color.parseColor("#4CAF50"))
                cornerRadius = 16f * resources.displayMetrics.density
            }
            background = moveBg
            val hPad = (12 * resources.displayMetrics.density).toInt()
            val vPad = (8 * resources.displayMetrics.density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            minWidth = (120 * resources.displayMetrics.density).toInt()

            setOnClickListener {
                onMyMoveNow()
            }
        }

        val btnClose = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(Color.TRANSPARENT)
            val size = (32 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
            setOnClickListener {
                stopSelf()
            }
        }

        container.addView(btnMyMove)
        container.addView(btnClose)
        overlayView = container

        // Drag handling
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        windowManager?.updateViewLayout(container, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        // Was dragging, don't click
                        true
                    } else {
                        // Not dragging, let click handlers work
                        container.performClick()
                        false
                    }
                }
                else -> false
            }
        }

        windowManager?.addView(container, params)

        // Setup arrow overlay (full screen, transparent, draws the arrow)
        setupArrowOverlay()
    }

    private fun setupArrowOverlay() {
        val arrowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        arrowOverlayView = ArrowOverlayView(this)
        windowManager?.addView(arrowOverlayView, arrowParams)
    }

    private fun onMyMoveNow() {
        val currentBitmap = latestBitmap
        if (currentBitmap == null) {
            Toast.makeText(this, "No screen data yet. Wait a moment.", Toast.LENGTH_SHORT).show()
            return
        }

        isProcessing = true

        serviceScope.launch {
            try {
                // Step 1: Detect chess board from the live screen frame
                val boardResult = withContext(Dispatchers.Default) {
                    detector.detectBoard(currentBitmap)
                }

                if (boardResult == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ChessOverlayService, "No chess board detected. Make sure a board is visible.", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                // Step 2: Convert board to FEN
                val fen = withContext(Dispatchers.Default) {
                    detector.boardToFEN(boardResult)
                }

                // Step 3: Get best move from Stockfish
                val bestMove = withContext(Dispatchers.Default) {
                    if (!engine.ensureInitialized(this@ChessOverlayService)) {
                        null
                    } else {
                        engine.getBestMove(fen)
                    }
                }

                if (bestMove == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ChessOverlayService, "Engine failed to find a move.", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                // Step 4: Draw arrow on overlay
                withContext(Dispatchers.Main) {
                    val fromSquare = bestMove.substring(0, 2)
                    val toSquare = bestMove.substring(2, 4)
                    arrowOverlayView?.drawArrow(
                        boardResult.boardRect,
                        fromSquare,
                        toSquare,
                        boardResult.isFlipped
                    )
                }

                // Auto-hide arrow after 4 seconds
                delay(4000)
                withContext(Dispatchers.Main) {
                    arrowOverlayView?.clearArrow()
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ChessOverlayService, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                isProcessing = false
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Chess Solver overlay notification"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .build()
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()

        overlayView?.let { windowManager?.removeView(it) }
        arrowOverlayView?.let { windowManager?.removeView(it) }

        handlerThread.quitSafely()
        engine.destroy()

        getSharedPreferences("chess_solver", Context.MODE_PRIVATE)
            .edit().putBoolean("is_running", false).apply()

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
