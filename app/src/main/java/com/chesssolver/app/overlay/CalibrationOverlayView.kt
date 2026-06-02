package com.chesssolver.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout

class CalibrationOverlayView(context: Context) : FrameLayout(context) {

    var calibrationRect: Rect? = null
        private set

    var onCalibrationComplete: ((Rect) -> Unit)? = null
    var onCalibrationCancelled: (() -> Unit)? = null

    // Drawing state
    private val drawingView = DrawingView(context)
    
    // Buttons
    private val btnSave = Button(context).apply {
        text = "✓ Save"
        setTextColor(Color.WHITE)
        textSize = 16f
        setAllCaps(false)
        setBackgroundColor(Color.parseColor("#238636"))
        setPadding(32, 16, 32, 16)
        visibility = GONE
        setOnClickListener { saveCalibration() }
    }
    
    private val btnCancel = Button(context).apply {
        text = "✕ Cancel"
        setTextColor(Color.WHITE)
        textSize = 16f
        setAllCaps(false)
        setBackgroundColor(Color.parseColor("#F85149"))
        setPadding(32, 16, 32, 16)
        visibility = GONE
        setOnClickListener { cancelCalibration() }
    }

    init {
        addView(drawingView)
        addView(btnSave)
        addView(btnCancel)
    }

    fun startCalibration(existingRect: Rect? = null) {
        drawingView.startCalibration(existingRect)
        updateButtonPositions()
    }

    fun cancelCalibration() {
        drawingView.cancelCalibration()
        btnSave.visibility = GONE
        btnCancel.visibility = GONE
        onCalibrationCancelled?.invoke()
    }

    private fun saveCalibration() {
        val rect = drawingView.getCurrentRect()
        if (rect != null && rect.width() > 50 && rect.height() > 50) {
            calibrationRect = rect
            btnSave.visibility = GONE
            btnCancel.visibility = GONE
            drawingView.finalizeCalibration()
            onCalibrationComplete?.invoke(rect)
        }
    }

    private fun updateButtonPositions() {
        // Position buttons at the bottom
        post {
            val btnHeight = 120
            val btnWidth = 280
            
            btnSave.layoutParams = LayoutParams(btnWidth, btnHeight).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = 200
            }
            
            btnCancel.layoutParams = LayoutParams(btnWidth, btnHeight).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = 60
            }
            
            btnSave.visibility = if (drawingView.hasSelection()) VISIBLE else GONE
            btnCancel.visibility = VISIBLE
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        updateButtonPositions()
    }

    inner class DrawingView(ctx: Context) : View(ctx) {

        private enum class Mode { IDLE, DRAGGING_NEW, DRAGGING_CORNER, MOVING }
        private var mode = Mode.IDLE

        // Selection rect (in view coordinates)
        private var selLeft = 0f
        private var selTop = 0f
        private var selRight = 0f
        private var selBottom = 0f
        private var hasSelection = false

        // Drag state
        private var dragCorner = -1  // 0=TL, 1=TR, 2=BL, 3=BR
        private var dragStartX = 0f
        private var dragStartY = 0f
        private var dragOrigLeft = 0f
        private var dragOrigTop = 0f
        private var dragOrigRight = 0f
        private var dragOrigBottom = 0f

        private val cornerRadius = 24f

        // Paints
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
            color = Color.parseColor("#FF58A6FF")
            style = Paint.Style.STROKE
            strokeWidth = 4f
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

        fun startCalibration(existingRect: Rect?) {
            if (existingRect != null) {
                selLeft = existingRect.left.toFloat()
                selTop = existingRect.top.toFloat()
                selRight = existingRect.right.toFloat()
                selBottom = existingRect.bottom.toFloat()
                hasSelection = true
                mode = Mode.IDLE
                btnSave.visibility = VISIBLE
                btnCancel.visibility = VISIBLE
            } else {
                hasSelection = false
                mode = Mode.IDLE
                btnSave.visibility = GONE
                btnCancel.visibility = VISIBLE
            }
            invalidate()
        }

        fun cancelCalibration() {
            mode = Mode.IDLE
            hasSelection = false
            invalidate()
        }

        fun finalizeCalibration() {
            mode = Mode.IDLE
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
                        // Check if touching a corner handle
                        val cornerHit = hitTestCorner(x, y)
                        if (cornerHit >= 0) {
                            mode = Mode.DRAGGING_CORNER
                            dragCorner = cornerHit
                            dragStartX = x
                            dragStartY = y
                            dragOrigLeft = selLeft
                            dragOrigTop = selTop
                            dragOrigRight = selRight
                            dragOrigBottom = selBottom
                            return true
                        }
                        // Check if touching inside rect (move)
                        if (x in selLeft..selRight && y in selTop..selBottom) {
                            mode = Mode.MOVING
                            dragStartX = x
                            dragStartY = y
                            dragOrigLeft = selLeft
                            dragOrigTop = selTop
                            dragOrigRight = selRight
                            dragOrigBottom = selBottom
                            return true
                        }
                    }

                    // Start new drag
                    mode = Mode.DRAGGING_NEW
                    selLeft = x; selTop = y
                    selRight = x; selBottom = y
                    hasSelection = true
                    btnSave.visibility = GONE
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val x = event.x
                    val y = event.y
                    when (mode) {
                        Mode.DRAGGING_NEW -> {
                            selRight = x; selBottom = y
                            makeSquare()
                        }
                        Mode.DRAGGING_CORNER -> {
                            val dx = x - dragStartX
                            val dy = y - dragStartY
                            when (dragCorner) {
                                0 -> { selLeft = dragOrigLeft + dx; selTop = dragOrigTop + dy }
                                1 -> { selRight = dragOrigRight + dx; selTop = dragOrigTop + dy }
                                2 -> { selLeft = dragOrigLeft + dx; selBottom = dragOrigBottom + dy }
                                3 -> { selRight = dragOrigRight + dx; selBottom = dragOrigBottom + dy }
                            }
                            makeSquare()
                        }
                        Mode.MOVING -> {
                            val dx = x - dragStartX
                            val dy = y - dragStartY
                            val w = dragOrigRight - dragOrigLeft
                            val h = dragOrigBottom - dragOrigTop
                            selLeft = dragOrigLeft + dx
                            selTop = dragOrigTop + dy
                            selRight = selLeft + w
                            selBottom = selTop + h
                        }
                        else -> {}
                    }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (mode == Mode.DRAGGING_NEW || mode == Mode.DRAGGING_CORNER || mode == Mode.MOVING) {
                        normalizeRect()
                        if (hasSelection && selRight - selLeft > 50) {
                            btnSave.visibility = VISIBLE
                        }
                    }
                    mode = Mode.IDLE
                    invalidate()
                    return true
                }
            }
            return false
        }

        private fun hitTestCorner(x: Float, y: Float): Int {
            val hitRadius = cornerRadius * 2.5f
            val corners = listOf(
                selLeft to selTop,       // 0: TL
                selRight to selTop,      // 1: TR
                selLeft to selBottom,    // 2: BL
                selRight to selBottom    // 3: BR
            )
            for ((i, pair) in corners.withIndex()) {
                val dx = x - pair.first
                val dy = y - pair.second
                if (dx * dx + dy * dy < hitRadius * hitRadius) return i
            }
            return -1
        }

        private fun makeSquare() {
            val w = Math.abs(selRight - selLeft)
            val h = Math.abs(selBottom - selTop)
            val size = Math.max(w, h)
            val cx = (selLeft + selRight) / 2f
            val cy = (selTop + selBottom) / 2f
            selLeft = cx - size / 2f
            selRight = cx + size / 2f
            selTop = cy - size / 2f
            selBottom = cy + size / 2f
        }

        private fun normalizeRect() {
            if (selLeft > selRight) { val t = selLeft; selLeft = selRight; selRight = t }
            if (selTop > selBottom) { val t = selTop; selTop = selBottom; selBottom = t }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!hasSelection) {
                // Just draw instruction
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)
                drawInstructionText(canvas, "Drag to select the chess board area")
                return
            }

            normalizeRect()

            // Draw dim overlay
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)

            // Clear the selection area
            canvas.drawRect(selLeft, selTop, selRight, selBottom, clearPaint)

            // Draw 8x8 grid lines inside selection
            val cellW = (selRight - selLeft) / 8f
            val cellH = (selBottom - selTop) / 8f
            for (i in 1 until 8) {
                val x = selLeft + i * cellW
                canvas.drawLine(x, selTop, x, selBottom, gridPaint)
                val y = selTop + i * cellH
                canvas.drawLine(selLeft, y, selRight, y, gridPaint)
            }

            // Draw border
            canvas.drawRect(selLeft, selTop, selRight, selBottom, borderPaint)

            // Draw corner handles
            val corners = listOf(
                selLeft to selTop,
                selRight to selTop,
                selLeft to selBottom,
                selRight to selBottom
            )
            for ((cx, cy) in corners) {
                canvas.drawCircle(cx, cy, cornerRadius, cornerStrokePaint)
                canvas.drawCircle(cx, cy, cornerRadius - 3f, cornerPaint)
            }

            // Draw instruction
            drawInstructionText(canvas, "Drag corners to adjust • Save when done")
        }

        private fun drawInstructionText(canvas: Canvas, text: String) {
            val textY = 80f
            val textWidth = textPaint.measureText(text)
            canvas.drawRect(width / 2f - textWidth / 2f - 20f, textY - 40f, width / 2f + textWidth / 2f + 20f, textY + 15f, textBgPaint)
            canvas.drawText(text, width / 2f, textY, textPaint)
        }
    }
}
