package com.chesssolver.app.overlay

import android.content.Context
import android.graphics.*
import android.view.View

class ArrowOverlayView(context: Context) : View(context) {

    private var boardRect: Rect? = null
    private var fromX = 0f
    private var fromY = 0f
    private var toX = 0f
    private var toY = 0f
    private var hasArrow = false

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF1744")
        style = Paint.Style.STROKE
        strokeWidth = 12f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val arrowOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 18f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val arrowFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF1744")
        style = Paint.Style.FILL
    }

    private val arrowFillOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    fun drawArrow(boardRect: Rect, fromSquare: String, toSquare: String, isFlipped: Boolean) {
        this.boardRect = boardRect

        val cellWidth = boardRect.width() / 8f
        val cellHeight = boardRect.height() / 8f

        fromX = boardRect.left + getColumn(fromSquare[0], isFlipped) * cellWidth + cellWidth / 2f
        fromY = boardRect.top + getRow(fromSquare[1], isFlipped) * cellHeight + cellHeight / 2f
        toX = boardRect.left + getColumn(toSquare[0], isFlipped) * cellWidth + cellWidth / 2f
        toY = boardRect.top + getRow(toSquare[1], isFlipped) * cellHeight + cellHeight / 2f

        hasArrow = true
        invalidate()
    }

    fun clearArrow() {
        hasArrow = false
        boardRect = null
        invalidate()
    }

    private fun getColumn(fileChar: Char, isFlipped: Boolean): Int {
        val col = fileChar - 'a'
        return if (isFlipped) 7 - col else col
    }

    private fun getRow(rankChar: Char, isFlipped: Boolean): Int {
        val row = 8 - (rankChar - '0')
        return if (isFlipped) 7 - row else row
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!hasArrow) return

        // Draw outline first (behind)
        canvas.drawLine(fromX, fromY, toX, toY, arrowOutlinePaint)

        // Draw main arrow line
        canvas.drawLine(fromX, fromY, toX, toY, arrowPaint)

        // Draw arrowhead
        val angle = Math.atan2((toY - fromY).toDouble(), (toX - fromX).toDouble())
        val arrowLen = 40f
        val arrowAngle = Math.toRadians(30.0)

        val x1 = toX - arrowLen * Math.cos(angle - arrowAngle).toFloat()
        val y1 = toY - arrowLen * Math.sin(angle - arrowAngle).toFloat()
        val x2 = toX - arrowLen * Math.cos(angle + arrowAngle).toFloat()
        val y2 = toY - arrowLen * Math.sin(angle + arrowAngle).toFloat()

        val path = Path().apply {
            moveTo(toX, toY)
            lineTo(x1, y1)
            lineTo(x2, y2)
            close()
        }

        // Outline of arrowhead
        canvas.drawPath(path, arrowFillOutlinePaint)
        // Fill of arrowhead
        canvas.drawPath(path, arrowFillPaint)
    }
}
