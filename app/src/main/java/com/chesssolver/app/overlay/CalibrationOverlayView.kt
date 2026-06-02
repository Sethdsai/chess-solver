package com.chesssolver.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View

/**
 * Full-screen overlay that lets the user drag a rectangle to select the chess board region.
 * Shows a semi-transparent overlay with a clear rectangle cutout.
 */
class CalibrationOverlayView(context: Context) : View(context) {

    var calibrationRect: Rect? = null
        private set

    var isCalibrating: Boolean = false
        private set

    var onCalibrationComplete: ((Rect) -> Unit)? = null

    private var startX = 0f
    private var startY = 0f
    private var currentX = 0f
    private var currentY = 0f
    private var isDragging = false

    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AA000000")
        style = Paint.Style.FILL
    }

    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.TRANSPARENT
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF4CAF50")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF4CAF50")
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 48f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC000000")
        style = Paint.Style.FILL
    }

    fun startCalibration() {
        isCalibrating = true
        calibrationRect = null
        isDragging = false
        invalidate()
    }

    fun cancelCalibration() {
        isCalibrating = false
        calibrationRect = null
        isDragging = false
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isCalibrating) return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                currentX = event.x
                currentY = event.y
                isDragging = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                currentX = event.x
                currentY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                currentX = event.x
                currentY = event.y
                isDragging = false

                // Create the rect
                val left = minOf(startX, currentX).toInt()
                val top = minOf(startY, currentY).toInt()
                val right = maxOf(startX, currentX).toInt()
                val bottom = maxOf(startY, currentY).toInt()

                // Make it square (use the larger dimension)
                val width = right - left
                val height = bottom - top
                val size = maxOf(width, height, 100)

                val rect = Rect(left, top, left + size, top + size)
                calibrationRect = rect
                isCalibrating = false
                invalidate()

                onCalibrationComplete?.invoke(rect)
                return true
            }
        }
        return false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!isCalibrating && calibrationRect == null) return

        if (isCalibrating && isDragging) {
            // Draw full overlay
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)

            // Draw selection rectangle
            val left = minOf(startX, currentX)
            val top = minOf(startY, currentY)
            val right = maxOf(startX, currentX)
            val bottom = maxOf(startY, currentY)

            // Make it square
            val w = right - left
            val h = bottom - top
            val size = maxOf(w, h)
            val adjustedRight = left + size
            val adjustedBottom = top + size

            // Clear the selection area
            canvas.drawRect(left, top, adjustedRight, adjustedBottom, clearPaint)

            // Draw border
            canvas.drawRect(left, top, adjustedRight, adjustedBottom, borderPaint)

            // Draw corner indicators
            val cornerSize = 20f
            canvas.drawCircle(left, top, cornerSize, cornerPaint)
            canvas.drawCircle(adjustedRight, top, cornerSize, cornerPaint)
            canvas.drawCircle(left, adjustedBottom, cornerSize, cornerPaint)
            canvas.drawCircle(adjustedRight, adjustedBottom, cornerSize, cornerPaint)

            // Draw instruction text at top
            val textY = top - 30f
            if (textY > 60f) {
                canvas.drawRect(width / 2f - 300f, textY - 40f, width / 2f + 300f, textY + 10f, bgPaint)
                canvas.drawText("Drag to select the chess board", width / 2f, textY, textPaint)
            }
        } else if (calibrationRect != null) {
            // Show the finalized calibration rect
            val rect = calibrationRect!!
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)
            canvas.drawRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(), clearPaint)
            canvas.drawRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(), borderPaint)
        }
    }
}
