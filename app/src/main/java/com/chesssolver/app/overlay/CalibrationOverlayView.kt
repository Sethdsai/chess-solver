package com.chesssolver.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout

class CalibrationOverlayView(context: Context) : FrameLayout(context) {

    var calibrationRect: Rect? = null
        private set

    var onCalibrationComplete: ((Rect) -> Unit)? = null
    var onCalibrationCancelled: (() -> Unit)? = null

    private val drawingView = DrawingView(context)
    private val buttonContainer = FrameLayout(context)

    private val btnSave = Button(context).apply {
        text = "SAVE"
        setTextColor(Color.WHITE)
        textSize = 15f
        setAllCaps(true)
        setBackgroundColor(Color.parseColor("#238636"))
        setPadding(40, 20, 40, 20)
        setOnClickListener { saveCalibration() }
    }

    private val btnCancel = Button(context).apply {
        text = "CANCEL"
        setTextColor(Color.WHITE)
        textSize = 15f
        setAllCaps(true)
        setBackgroundColor(Color.parseColor("#6E7681"))
        setPadding(40, 20, 40, 20)
        setOnClickListener { cancelCalibration() }
    }

    private val btnReset = Button(context).apply {
        text = "RESET"
        setTextColor(Color.WHITE)
        textSize = 13f
        setAllCaps(true)
        setBackgroundColor(Color.parseColor("#D29922"))
        setPadding(24, 12, 24, 12)
        setOnClickListener { resetCalibration() }
    }

    init {
        addView(drawingView)
        addView(buttonContainer)
        buttonContainer.addView(btnSave)
        buttonContainer.addView(btnCancel)
        buttonContainer.addView(btnReset)
    }

    fun startCalibration(existingRect: Rect? = null) {
        drawingView.startCalibration(existingRect)
        showButtons()
    }

    fun cancelCalibration() {
        drawingView.cancelCalibration()
        hideButtons()
        onCalibrationCancelled?.invoke()
    }

    private fun resetCalibration() {
        drawingView.resetSelection()
        btnSave.visibility = GONE
        val prefs = context.getSharedPreferences("chess_solver", Context.MODE_PRIVATE)
        prefs.edit().remove("incomplete_cal_left").remove("incomplete_cal_top")
            .remove("incomplete_cal_right").remove("incomplete_cal_bottom")
            .putBoolean("calibration_incomplete", false).apply()
    }

    private fun saveCalibration() {
        val rect = drawingView.getCurrentRect()
        if (rect != null && rect.width() > 50 && rect.height() > 50) {
            calibrationRect = rect
            hideButtons()
            drawingView.finalizeCalibration()
            val prefs = context.getSharedPreferences("chess_solver", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("calibration_incomplete", false).apply()
            onCalibrationComplete?.invoke(rect)
        }
    }

    private fun showButtons() {
        post {
            val density = context.resources.displayMetrics.density
            val btnWidth = (160 * density).toInt()
            val btnHeight = (56 * density).toInt()
            val smallBtnWidth = (120 * density).toInt()
            val smallBtnHeight = (44 * density).toInt()
            val margin = (16 * density).toInt()

            btnSave.layoutParams = LayoutParams(btnWidth, btnHeight, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = (150 * density).toInt()
            }
            btnCancel.layoutParams = LayoutParams(btnWidth, btnHeight, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = (80 * density).toInt()
            }
            btnReset.layoutParams = LayoutParams(smallBtnWidth, smallBtnHeight, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = margin
            }

            btnSave.visibility = if (drawingView.hasSelection()) VISIBLE else GONE
            btnCancel.visibility = VISIBLE
            btnReset.visibility = VISIBLE
        }
    }

    private fun hideButtons() {
        btnSave.visibility = GONE
        btnCancel.visibility = GONE
        btnReset.visibility = GONE
    }

    fun updateSaveButtonState() {
        btnSave.visibility = if (drawingView.hasSelection()) VISIBLE else GONE
        val rect = drawingView.getCurrentRect()
        val prefs = context.getSharedPreferences("chess_solver", Context.MODE_PRIVATE)
        if (rect != null && rect.width() > 50 && rect.height() > 50) {
            prefs.edit()
                .putInt("incomplete_cal_left", rect.left)
                .putInt("incomplete_cal_top", rect.top)
                .putInt("incomplete_cal_right", rect.right)
                .putInt("incomplete_cal_bottom", rect.bottom)
                .putBoolean("calibration_incomplete", true)
                .apply()
        }
    }

    inner class DrawingView(ctx: Context) : View(ctx) {

        private var mode = 0 // 0=IDLE, 1=DRAGGING_NEW, 2=DRAGGING_CORNER, 3=MOVING

        private var selLeft = 0f
        private var selTop = 0f
        private var selRight = 0f
        private var selBottom = 0f
        private var hasSelection = false

        private var dragCorner = -1
        private var dragStartX = 0f
        private var dragStartY = 0f
        private var dragOrigLeft = 0f
        private var dragOrigTop = 0f
        private var dragOrigRight = 0f
        private var dragOrigBottom = 0f

        private val cornerRadius = 28f

        private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#BB000000")
            style = Paint.Style.FILL
        }
        private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.TRANSPARENT
            style = Paint.Style.FILL
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF58A6FF")
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }
        private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFFFFFFF")
            style = Paint.Style.FILL
        }
        private val cornerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF58A6FF")
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 42f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        private val textBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CC000000")
            style = Paint.Style.FILL
        }
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4458A6FF")
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#9958A6FF")
            style = Paint.Style.STROKE
            strokeWidth = 3f
            pathEffect = DashPathEffect(floatArrayOf(20f, 10f), 0f)
        }

        fun startCalibration(existingRect: Rect?) {
            if (existingRect != null) {
                selLeft = existingRect.left.toFloat()
                selTop = existingRect.top.toFloat()
                selRight = existingRect.right.toFloat()
                selBottom = existingRect.bottom.toFloat()
                hasSelection = true
                mode = 0
            } else {
                // Check for incomplete calibration to resume
                val prefs = context.getSharedPreferences("chess_solver", Context.MODE_PRIVATE)
                val incomplete = prefs.getBoolean("calibration_incomplete", false)
                if (incomplete) {
                    val l = prefs.getInt("incomplete_cal_left", -1)
                    val t = prefs.getInt("incomplete_cal_top", -1)
                    val r = prefs.getInt("incomplete_cal_right", -1)
                    val b = prefs.getInt("incomplete_cal_bottom", -1)
                    if (l >= 0 && t >= 0 && r > l && b > t) {
                        selLeft = l.toFloat()
                        selTop = t.toFloat()
                        selRight = r.toFloat()
                        selBottom = b.toFloat()
                        hasSelection = true
                        mode = 0
                    } else {
                        hasSelection = false
                        mode = 0
                    }
                } else {
                    hasSelection = false
                    mode = 0
                }
            }
            invalidate()
        }

        fun cancelCalibration() {
            mode = 0
            hasSelection = false
            invalidate()
        }

        fun finalizeCalibration() {
            mode = 0
            invalidate()
        }

        fun resetSelection() {
            mode = 0
            hasSelection = false
            selLeft = 0f; selTop = 0f; selRight = 0f; selBottom = 0f
            invalidate()
        }

        fun hasSelection(): Boolean = hasSelection

        fun getCurrentRect(): Rect? {
            if (!hasSelection) return null
            return Rect(
                selLeft.toInt().coerceAtLeast(0),
                selTop.toInt().coerceAtLeast(0),
                selRight.toInt().coerceAtMost(width),
                selBottom.toInt().coerceAtMost(height)
            )
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val x = event.x
                    val y = event.y
                    if (hasSelection) {
                        val cornerHit = hitTestCorner(x, y)
                        if (cornerHit >= 0) {
                            mode = 2
                            dragCorner = cornerHit
                            dragStartX = x; dragStartY = y
                            dragOrigLeft = selLeft; dragOrigTop = selTop
                            dragOrigRight = selRight; dragOrigBottom = selBottom
                            return true
                        }
                        if (x in selLeft..selRight && y in selTop..selBottom) {
                            mode = 3
                            dragStartX = x; dragStartY = y
                            dragOrigLeft = selLeft; dragOrigTop = selTop
                            dragOrigRight = selRight; dragOrigBottom = selBottom
                            return true
                        }
                    }
                    mode = 1
                    selLeft = x; selTop = y; selRight = x; selBottom = y
                    hasSelection = true
                    btnSave.visibility = GONE
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val x = event.x; val y = event.y
                    when (mode) {
                        1 -> { selRight = x; selBottom = y; makeSquare() }
                        2 -> {
                            val dx = x - dragStartX; val dy = y - dragStartY
                            when (dragCorner) {
                                0 -> { selLeft = dragOrigLeft + dx; selTop = dragOrigTop + dy }
                                1 -> { selRight = dragOrigRight + dx; selTop = dragOrigTop + dy }
                                2 -> { selLeft = dragOrigLeft + dx; selBottom = dragOrigBottom + dy }
                                3 -> { selRight = dragOrigRight + dx; selBottom = dragOrigBottom + dy }
                            }
                            makeSquare()
                        }
                        3 -> {
                            val dx = x - dragStartX; val dy = y - dragStartY
                            val w = dragOrigRight - dragOrigLeft
                            val h = dragOrigBottom - dragOrigTop
                            selLeft = dragOrigLeft + dx; selTop = dragOrigTop + dy
                            selRight = selLeft + w; selBottom = selTop + h
                        }
                    }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (mode == 1 || mode == 2 || mode == 3) {
                        normalizeRect()
                        if (hasSelection && selRight - selLeft > 50) {
                            btnSave.visibility = VISIBLE
                            updateSaveButtonState()
                        }
                    }
                    mode = 0
                    invalidate()
                    return true
                }
            }
            return false
        }

        private fun hitTestCorner(x: Float, y: Float): Int {
            val hitRadius = cornerRadius * 2.5f
            val corners = listOf(
                selLeft to selTop, selRight to selTop,
                selLeft to selBottom, selRight to selBottom
            )
            for ((i, pair) in corners.withIndex()) {
                val dx = x - pair.first; val dy = y - pair.second
                if (dx * dx + dy * dy < hitRadius * hitRadius) return i
            }
            return -1
        }

        private fun makeSquare() {
            val w = Math.abs(selRight - selLeft); val h = Math.abs(selBottom - selTop)
            val size = Math.max(w, h)
            val cx = (selLeft + selRight) / 2f; val cy = (selTop + selBottom) / 2f
            selLeft = cx - size / 2f; selRight = cx + size / 2f
            selTop = cy - size / 2f; selBottom = cy + size / 2f
        }

        private fun normalizeRect() {
            if (selLeft > selRight) { val t = selLeft; selLeft = selRight; selRight = t }
            if (selTop > selBottom) { val t = selTop; selTop = selBottom; selBottom = t }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!hasSelection) {
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)
                drawInstructionText(canvas, "Drag to select the chess board area")
                val cx = width / 2f; val cy = height / 2f; val hintSize = 200f
                canvas.drawRect(cx - hintSize, cy - hintSize, cx + hintSize, cy + hintSize, hintPaint)
                return
            }
            normalizeRect()
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)
            canvas.drawRect(selLeft, selTop, selRight, selBottom, clearPaint)

            val cellW = (selRight - selLeft) / 8f; val cellH = (selBottom - selTop) / 8f
            for (i in 1 until 8) {
                val x = selLeft + i * cellW; canvas.drawLine(x, selTop, x, selBottom, gridPaint)
                val y = selTop + i * cellH; canvas.drawLine(selLeft, y, selRight, y, gridPaint)
            }
            canvas.drawRect(selLeft, selTop, selRight, selBottom, borderPaint)

            val corners = listOf(selLeft to selTop, selRight to selTop, selLeft to selBottom, selRight to selBottom)
            for ((cx, cy) in corners) {
                canvas.drawCircle(cx, cy, cornerRadius, cornerStrokePaint)
                canvas.drawCircle(cx, cy, cornerRadius - 3f, cornerPaint)
            }
            drawInstructionText(canvas, "Drag corners to adjust, then SAVE")
        }

        private fun drawInstructionText(canvas: Canvas, text: String) {
            val textY = 100f
            val textWidth = textPaint.measureText(text)
            canvas.drawRect(width / 2f - textWidth / 2f - 24f, textY - 44f,
                width / 2f + textWidth / 2f + 24f, textY + 15f, textBgPaint)
            canvas.drawText(text, width / 2f, textY, textPaint)
        }
    }
}
