package com.chesssolver.app.detection

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.Color
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

data class BoardResult(
    val boardRect: Rect,
    val pieces: Array<Array<PieceType>>,
    val isFlipped: Boolean
)

enum class PieceType(val fenChar: Char) {
    EMPTY('.'),
    WHITE_PAWN('P'), WHITE_KNIGHT('N'), WHITE_BISHOP('B'), WHITE_ROOK('R'), WHITE_QUEEN('Q'), WHITE_KING('K'),
    BLACK_PAWN('p'), BLACK_KNIGHT('n'), BLACK_BISHOP('b'), BLACK_ROOK('r'), BLACK_QUEEN('q'), BLACK_KING('k');
}

class ChessBoardDetector {

    companion object {
        private const val TAG = "ChessBoardDetector"

        // Extended chess board color themes (light square RGB, dark square RGB)
        private val KNOWN_THEMES = listOf(
            // Chess.com themes
            intArrayOf(238, 216, 192) to intArrayOf(171, 122, 101),  // Default brown
            intArrayOf(240, 217, 181) to intArrayOf(181, 136, 99),   // Classic
            intArrayOf(237, 214, 176) to intArrayOf(175, 121, 82),   // Wood
            intArrayOf(224, 217, 190) to intArrayOf(145, 129, 93),   // Tournament
            intArrayOf(232, 235, 238) to intArrayOf(115, 135, 149),  // Ice
            intArrayOf(255, 255, 206) to intArrayOf(122, 149, 91),   // Green
            intArrayOf(255, 226, 198) to intArrayOf(199, 146, 107),  // Coral
            intArrayOf(255, 224, 198) to intArrayOf(186, 118, 79),   // Cherry
            intArrayOf(216, 200, 168) to intArrayOf(144, 104, 64),   // Walnut
            intArrayOf(246, 246, 229) to intArrayOf(168, 149, 149),  // Slate
            // Lichess themes
            intArrayOf(215, 192, 160) to intArrayOf(170, 130, 96),   // Brown
            intArrayOf(224, 224, 240) to intArrayOf(136, 136, 204),  // Blue
            intArrayOf(232, 216, 240) to intArrayOf(152, 120, 168),  // Purple
            intArrayOf(222, 194, 150) to intArrayOf(160, 114, 76),   // Wood
            intArrayOf(216, 216, 216) to intArrayOf(152, 152, 152),  // Grey
            intArrayOf(240, 200, 180) to intArrayOf(180, 120, 100),  // Coral
            intArrayOf(235, 215, 180) to intArrayOf(170, 128, 90),   // Tournament
            intArrayOf(255, 238, 218) to intArrayOf(191, 155, 121),  // Sand
            intArrayOf(186, 238, 186) to intArrayOf(118, 150, 86),   // Green
            intArrayOf(230, 220, 206) to intArrayOf(162, 134, 106),  // Marble
            // Generic dark/light patterns
            intArrayOf(240, 217, 181) to intArrayOf(181, 136, 99),
            intArrayOf(255, 255, 255) to intArrayOf(128, 128, 128),
            intArrayOf(255, 239, 213) to intArrayOf(205, 133, 63),
            intArrayOf(245, 245, 220) to intArrayOf(160, 82, 45),
            intArrayOf(250, 235, 215) to intArrayOf(210, 105, 30)
        )

        private const val THEME_THRESHOLD = 100
    }

    /**
     * Detect chess board from a screen bitmap.
     * Uses a multi-pass approach: coarse scan, then refinement.
     */
    fun detectBoard(bitmap: Bitmap): BoardResult? {
        try {
            val boardRect = findBoardRegion(bitmap)
            if (boardRect == null) {
                Log.w(TAG, "No board region found")
                return null
            }

            Log.d(TAG, "Board region found: $boardRect")

            // Refine the board rect - make it square and better aligned
            val refinedRect = refineBoardRect(bitmap, boardRect)
            Log.d(TAG, "Refined board rect: $refinedRect")

            val theme = detectTheme(bitmap, refinedRect)
            Log.d(TAG, "Detected theme: light=${theme?.first?.toList()}, dark=${theme?.second?.toList()}")

            val cellWidth = refinedRect.width() / 8f
            val cellHeight = refinedRect.height() / 8f
            val pieces = Array(8) { Array(8) { PieceType.EMPTY } }

            for (row in 0 until 8) {
                for (col in 0 until 8) {
                    val left = refinedRect.left + (col * cellWidth).toInt()
                    val top = refinedRect.top + (row * cellHeight).toInt()
                    val right = refinedRect.left + ((col + 1) * cellWidth).toInt()
                    val bottom = refinedRect.top + ((row + 1) * cellHeight).toInt()
                    val piece = analyzeSquare(bitmap, left, top, right - left, bottom - top, row, col, theme)
                    pieces[row][col] = piece
                }
            }

            val isFlipped = detectOrientation(pieces)
            return BoardResult(refinedRect, pieces, isFlipped)
        } catch (e: Exception) {
            Log.e(TAG, "Board detection failed", e)
            return null
        }
    }

    /**
     * Detect board using a manually specified region (from calibration).
     */
    fun detectBoardInRegion(bitmap: Bitmap, boardRect: Rect): BoardResult? {
        try {
            val theme = detectTheme(bitmap, boardRect)
            val cellWidth = boardRect.width() / 8f
            val cellHeight = boardRect.height() / 8f
            val pieces = Array(8) { Array(8) { PieceType.EMPTY } }

            for (row in 0 until 8) {
                for (col in 0 until 8) {
                    val left = boardRect.left + (col * cellWidth).toInt()
                    val top = boardRect.top + (row * cellHeight).toInt()
                    val right = boardRect.left + ((col + 1) * cellWidth).toInt()
                    val bottom = boardRect.top + ((row + 1) * cellHeight).toInt()
                    val piece = analyzeSquare(bitmap, left, top, right - left, bottom - top, row, col, theme)
                    pieces[row][col] = piece
                }
            }

            val isFlipped = detectOrientation(pieces)
            return BoardResult(boardRect, pieces, isFlipped)
        } catch (e: Exception) {
            Log.e(TAG, "Board detection in region failed", e)
            return null
        }
    }

    /**
     * Find the chess board region in the screenshot.
     * Strategy: Multi-resolution scan with gradient-based detection.
     */
    private fun findBoardRegion(bitmap: Bitmap): Rect? {
        // Pass 1: Coarse scan at very low resolution
        val scale1 = 0.10f
        val smallW1 = (bitmap.width * scale1).toInt()
        val smallH1 = (bitmap.height * scale1).toInt()
        val small1 = Bitmap.createScaledBitmap(bitmap, smallW1, smallH1, false)

        val pixels1 = IntArray(smallW1 * smallH1)
        small1.getPixels(pixels1, 0, smallW1, 0, 0, smallW1, smallH1)

        var bestRect: Rect? = null
        var bestScore = -1.0

        val sizeRatios = floatArrayOf(0.98f, 0.92f, 0.85f, 0.78f, 0.70f, 0.60f, 0.50f)
        val stepSize = (smallW1 / 16).coerceAtLeast(1)

        for (sizeRatio in sizeRatios) {
            val boardSize = (minOf(smallW1, smallH1) * sizeRatio).toInt()
            if (boardSize < 12) continue

            for (startX in 0..(smallW1 - boardSize) step stepSize) {
                for (startY in 0..(smallH1 - boardSize) step stepSize) {
                    val score = scoreChessRegion(pixels1, smallW1, smallH1, startX, startY, boardSize)
                    if (score > bestScore) {
                        bestScore = score
                        bestRect = Rect(
                            (startX / scale1).toInt(),
                            (startY / scale1).toInt(),
                            ((startX + boardSize) / scale1).toInt(),
                            ((startY + boardSize) / scale1).toInt()
                        )
                    }
                }
            }
        }

        small1.recycle()

        if (bestScore < 0.25) {
            Log.w(TAG, "Best chess score too low: $bestScore")
            return null
        }

        Log.d(TAG, "Best chess score: $bestScore")

        // Pass 2: Refine around the best area at higher resolution
        if (bestRect != null) {
            val refineMargin = (bestRect.width() * 0.15).toInt()
            val refineLeft = (bestRect.left - refineMargin).coerceAtLeast(0)
            val refineTop = (bestRect.top - refineMargin).coerceAtLeast(0)
            val refineRight = (bestRect.right + refineMargin).coerceAtMost(bitmap.width)
            val refineBottom = (bestRect.bottom + refineMargin).coerceAtMost(bitmap.height)
            val refineW = refineRight - refineLeft
            val refineH = refineBottom - refineTop

            if (refineW > 20 && refineH > 20) {
                val scale2 = 0.25f
                val smallW2 = (refineW * scale2).toInt()
                val smallH2 = (refineH * scale2).toInt()
                if (smallW2 > 8 && smallH2 > 8) {
                    val cropped = Bitmap.createBitmap(bitmap, refineLeft, refineTop, refineW, refineH)
                    val small2 = Bitmap.createScaledBitmap(cropped, smallW2, smallH2, false)
                    cropped.recycle()

                    val pixels2 = IntArray(smallW2 * smallH2)
                    small2.getPixels(pixels2, 0, smallW2, 0, 0, smallW2, smallH2)

                    var refinedBestRect: Rect? = null
                    var refinedBestScore = -1.0
                    val refineStep = (smallW2 / 20).coerceAtLeast(1)

                    for (sizeRatio in floatArrayOf(0.95f, 0.88f, 0.80f, 0.72f, 0.64f)) {
                        val boardSize = (minOf(smallW2, smallH2) * sizeRatio).toInt()
                        if (boardSize < 8) continue

                        for (sx in 0..(smallW2 - boardSize) step refineStep) {
                            for (sy in 0..(smallH2 - boardSize) step refineStep) {
                                val score = scoreChessRegion(pixels2, smallW2, smallH2, sx, sy, boardSize)
                                if (score > refinedBestScore) {
                                    refinedBestScore = score
                                    refinedBestRect = Rect(
                                        refineLeft + (sx / scale2).toInt(),
                                        refineTop + (sy / scale2).toInt(),
                                        refineLeft + ((sx + boardSize) / scale2).toInt(),
                                        refineTop + ((sy + boardSize) / scale2).toInt()
                                    )
                                }
                            }
                        }
                    }

                    small2.recycle()

                    if (refinedBestScore > bestScore * 0.8 && refinedBestRect != null) {
                        bestRect = refinedBestRect
                        Log.d(TAG, "Refined score: $refinedBestScore, rect: $bestRect")
                    }
                }
            }
        }

        return bestRect
    }

    /**
     * Refine board rect: make it perfectly square and aligned to grid.
     */
    private fun refineBoardRect(bitmap: Bitmap, rect: Rect): Rect {
        // Make it square
        val w = rect.width(); val h = rect.height()
        val size = minOf(w, h)
        val cx = rect.left + w / 2; val cy = rect.top + h / 2

        // Try to detect grid edges using brightness gradients
        val scale = 0.5f
        val sw = (bitmap.width * scale).toInt()
        val sh = (bitmap.height * scale).toInt()
        if (sw < 16 || sh < 16) {
            return Rect(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
        }

        val small = Bitmap.createScaledBitmap(bitmap, sw, sh, false)
        val sLeft = (rect.left * scale).toInt().coerceAtLeast(0)
        val sTop = (rect.top * scale).toInt().coerceAtLeast(0)
        val sRight = (rect.right * scale).toInt().coerceAtMost(sw)
        val sBottom = (rect.bottom * scale).toInt().coerceAtMost(sh)

        // Analyze edge gradients to find exact board boundaries
        val edgeLeft = findVerticalEdge(small, sLeft, sTop, sBottom, true)
        val edgeRight = findVerticalEdge(small, sRight, sTop, sBottom, false)
        val edgeTop = findHorizontalEdge(small, sLeft, sRight, sTop, true)
        val edgeBottom = findHorizontalEdge(small, sLeft, sRight, sBottom, false)

        small.recycle()

        val fLeft = (edgeLeft / scale).toInt()
        val fRight = (edgeRight / scale).toInt()
        val fTop = (edgeTop / scale).toInt()
        val fBottom = (edgeBottom / scale).toInt()

        // Make square again from edges
        val fw = fRight - fLeft; val fh = fBottom - fTop
        val fSize = minOf(fw, fh)
        val fcx = fLeft + fw / 2; val fcy = fTop + fh / 2

        return Rect(fcx - fSize / 2, fcy - fSize / 2, fcx + fSize / 2, fcy + fSize / 2)
    }

    private fun findVerticalEdge(bitmap: Bitmap, startX: Int, top: Int, bottom: Int, searchLeft: Boolean): Int {
        val step = if (searchLeft) 1 else -1
        val limit = if (searchLeft) startX + (bitmap.width / 8) else startX - (bitmap.width / 8)
        var bestX = startX
        var maxGradient = 0.0

        val margin = ((bottom - top) * 0.1).toInt()
        val sampleTop = top + margin; val sampleBottom = bottom - margin
        if (sampleTop >= sampleBottom) return startX

        var x = startX
        while (if (searchLeft) x < limit else x > limit) {
            var gradient = 0.0; var count = 0
            for (y in sampleTop..sampleBottom step 4) {
                if (x + step < 0 || x + step >= bitmap.width || x < 0 || x >= bitmap.width) continue
                val p1 = bitmap.getPixel(x, y); val p2 = bitmap.getPixel(x + step, y)
                val b1 = Color.red(p1) + Color.green(p1) + Color.blue(p1)
                val b2 = Color.red(p2) + Color.green(p2) + Color.blue(p2)
                gradient += Math.abs(b2 - b1); count++
            }
            if (count > 0) gradient /= count.toDouble()
            if (gradient > maxGradient) { maxGradient = gradient; bestX = x }
            x += step
        }
        return bestX
    }

    private fun findHorizontalEdge(bitmap: Bitmap, left: Int, right: Int, startY: Int, searchUp: Boolean): Int {
        val step = if (searchUp) 1 else -1
        val limit = if (searchUp) startY + (bitmap.height / 8) else startY - (bitmap.height / 8)
        var bestY = startY
        var maxGradient = 0.0

        val margin = ((right - left) * 0.1).toInt()
        val sampleLeft = left + margin; val sampleRight = right - margin
        if (sampleLeft >= sampleRight) return startY

        var y = startY
        while (if (searchUp) y < limit else y > limit) {
            var gradient = 0.0; var count = 0
            for (x in sampleLeft..sampleRight step 4) {
                if (y + step < 0 || y + step >= bitmap.height || y < 0 || y >= bitmap.height) continue
                val p1 = bitmap.getPixel(x, y); val p2 = bitmap.getPixel(x, y + step)
                val b1 = Color.red(p1) + Color.green(p1) + Color.blue(p1)
                val b2 = Color.red(p2) + Color.green(p2) + Color.blue(p2)
                gradient += Math.abs(b2 - b1); count++
            }
            if (count > 0) gradient /= count.toDouble()
            if (gradient > maxGradient) { maxGradient = gradient; bestY = y }
            y += step
        }
        return bestY
    }

    /**
     * Score a region for "chess-ness".
     */
    private fun scoreChessRegion(pixels: IntArray, width: Int, height: Int, x: Int, y: Int, size: Int): Double {
        val cellSize = size / 8
        if (cellSize < 2) return 0.0

        var alternatingCount = 0
        var themeMatchCount = 0
        var totalChecks = 0
        val lightColors = mutableListOf<IntArray>()
        val darkColors = mutableListOf<IntArray>()

        // Sample points within each cell (not just center)
        for (row in 0 until 8) {
            for (col in 0 until 8) {
                val isLight = (row + col) % 2 == 0

                // Sample multiple points within the cell for robustness
                var rSum = 0L; var gSum = 0L; var bSum = 0L; var sampleCount = 0

                for (dy in intArrayOf(cellSize / 4, cellSize / 2, cellSize * 3 / 4)) {
                    for (dx in intArrayOf(cellSize / 4, cellSize / 2, cellSize * 3 / 4)) {
                        val sx = x + col * cellSize + dx
                        val sy = y + row * cellSize + dy
                        if (sx >= width || sy >= height || sx < 0 || sy < 0) continue
                        val pixel = pixels[sy * width + sx]
                        rSum += Color.red(pixel); gSum += Color.green(pixel); bSum += Color.blue(pixel)
                        sampleCount++
                    }
                }

                if (sampleCount == 0) continue
                val r = (rSum / sampleCount).toInt()
                val g = (gSum / sampleCount).toInt()
                val b = (bSum / sampleCount).toInt()

                if (isLight) lightColors.add(intArrayOf(r, g, b))
                else darkColors.add(intArrayOf(r, g, b))

                // Check alternating pattern with neighbors
                val neighbors = listOf(
                    row to (col + 1), (row + 1) to col
                )
                for ((nr, nc) in neighbors) {
                    if (nr >= 8 || nc >= 8) continue
                    val ncx = x + nc * cellSize + cellSize / 2
                    val ncy = y + nr * cellSize + cellSize / 2
                    if (ncx >= width || ncy >= height || ncx < 0 || ncy < 0) continue

                    val np = pixels[ncy * width + ncx]
                    val nr2 = Color.red(np); val ng = Color.green(np); val nb = Color.blue(np)
                    val colorDist = colorDistance(r, g, b, nr2, ng, nb)

                    val isNeighborLight = (nr + nc) % 2 == 0
                    // Alternating: neighbors of different parity should have different colors
                    if (isLight != isNeighborLight && colorDist > 20) alternatingCount++
                    // Same parity should have similar colors
                    totalChecks++
                }
            }
        }

        if (totalChecks == 0) return 0.0

        val alternationScore = alternatingCount.toDouble() / totalChecks.toDouble()

        // Check theme matching with relaxed threshold
        if (lightColors.isNotEmpty() && darkColors.isNotEmpty()) {
            val avgLight = averageColor(lightColors)
            val avgDark = averageColor(darkColors)
            for ((themeLight, themeDark) in KNOWN_THEMES) {
                val lightDist = colorDistance(avgLight[0], avgLight[1], avgLight[2], themeLight[0], themeLight[1], themeLight[2])
                val darkDist = colorDistance(avgDark[0], avgDark[1], avgDark[2], themeDark[0], themeDark[1], themeDark[2])
                if (lightDist < THEME_THRESHOLD && darkDist < THEME_THRESHOLD) {
                    themeMatchCount++
                }
            }
        }

        val themeScore = when {
            themeMatchCount > 2 -> 0.4
            themeMatchCount > 0 -> 0.25
            else -> 0.0
        }

        // Bonus for having well-separated light and dark colors
        val separationScore = if (lightColors.isNotEmpty() && darkColors.isNotEmpty()) {
            val avgLight = averageColor(lightColors)
            val avgDark = averageColor(darkColors)
            val sep = colorDistance(avgLight[0], avgLight[1], avgLight[2], avgDark[0], avgDark[1], avgDark[2])
            if (sep > 60) 0.1 else 0.0
        } else 0.0

        return alternationScore + themeScore + separationScore
    }

    private fun detectTheme(bitmap: Bitmap, boardRect: Rect): Pair<IntArray, IntArray>? {
        val cellWidth = boardRect.width() / 8f
        val cellHeight = boardRect.height() / 8f
        val lightColors = mutableListOf<IntArray>()
        val darkColors = mutableListOf<IntArray>()

        for (row in 0 until 8) {
            for (col in 0 until 8) {
                val cx = boardRect.left + (col * cellWidth + cellWidth / 2).toInt()
                val cy = boardRect.top + (row * cellHeight + cellHeight / 2).toInt()
                if (cx >= bitmap.width || cy >= bitmap.height || cx < 0 || cy < 0) continue

                val pixel = bitmap.getPixel(cx, cy)
                val r = Color.red(pixel); val g = Color.green(pixel); val b = Color.blue(pixel)

                if ((row + col) % 2 == 0) lightColors.add(intArrayOf(r, g, b))
                else darkColors.add(intArrayOf(r, g, b))
            }
        }

        if (lightColors.isEmpty() || darkColors.isEmpty()) return null
        return averageColor(lightColors) to averageColor(darkColors)
    }

    /**
     * Analyze a single square to determine what piece is on it.
     * Improved with multi-region sampling and better piece detection.
     */
    private fun analyzeSquare(
        bitmap: Bitmap, left: Int, top: Int, width: Int, height: Int,
        row: Int, col: Int, theme: Pair<IntArray, IntArray>?
    ): PieceType {
        if (width <= 4 || height <= 4) return PieceType.EMPTY

        // Analyze center region, avoid borders
        val margin = (minOf(width, height) * 0.15f).toInt()
        val safeLeft = (left + margin).coerceAtLeast(0)
        val safeTop = (top + margin).coerceAtLeast(0)
        val safeWidth = (width - 2 * margin).coerceAtMost(bitmap.width - safeLeft).coerceAtLeast(1)
        val safeHeight = (height - 2 * margin).coerceAtMost(bitmap.height - safeTop).coerceAtLeast(1)

        if (safeWidth <= 0 || safeHeight <= 0) return PieceType.EMPTY
        if (safeLeft + safeWidth > bitmap.width || safeTop + safeHeight > bitmap.height) return PieceType.EMPTY

        val squareBitmap = Bitmap.createBitmap(bitmap, safeLeft, safeTop, safeWidth, safeHeight)
        val pixels = IntArray(safeWidth * safeHeight)
        squareBitmap.getPixels(pixels, 0, safeWidth, 0, 0, safeWidth, safeHeight)

        val isLightSquare = (row + col) % 2 == 0
        val expectedBg = if (isLightSquare) theme?.first else theme?.second

        var differentPixels = 0
        var darkDifferent = 0
        var lightDifferent = 0
        var diffR = 0L; var diffG = 0L; var diffB = 0L

        for (pixel in pixels) {
            val r = Color.red(pixel); val g = Color.green(pixel); val b = Color.blue(pixel)

            if (expectedBg != null) {
                val dist = colorDistance(r, g, b, expectedBg[0], expectedBg[1], expectedBg[2])
                if (dist > 35) {
                    differentPixels++
                    diffR += r; diffG += g; diffB += b
                    val brightness = (r * 0.299 + g * 0.587 + b * 0.114).toInt()
                    if (brightness < 100) darkDifferent++
                    else if (brightness > 160) lightDifferent++
                }
            } else {
                // No theme - use local average
                val avgR = diffR / (differentPixels.coerceAtLeast(1))
                val avgG = diffG / (differentPixels.coerceAtLeast(1))
                val avgB = diffB / (differentPixels.coerceAtLeast(1))
                val dist = colorDistance(r, g, b, avgR.toInt(), avgG.toInt(), avgB.toInt())
                val brightness = (r * 0.299 + g * 0.587 + b * 0.114).toInt()
                // Check if pixel deviates from both light and dark square backgrounds
                val isDarkPixel = brightness < 120
                val isLightPixel = brightness > 180
                if (isLightSquare && isDarkPixel) { differentPixels++; darkDifferent++ }
                else if (!isLightSquare && isLightPixel) { differentPixels++; lightDifferent++ }
                else if (brightness > 200 || brightness < 50) { differentPixels++; if (brightness < 100) darkDifferent++ else lightDifferent++ }
            }
        }

        val totalPixels = pixels.size
        val diffRatio = differentPixels.toFloat() / totalPixels

        if (diffRatio < 0.08f) return PieceType.EMPTY

        val isWhitePiece = lightDifferent > darkDifferent
        return classifyPiece(diffRatio, isWhitePiece)
    }

    private fun classifyPiece(diffRatio: Float, isWhitePiece: Boolean): PieceType {
        return when {
            diffRatio > 0.55 -> if (isWhitePiece) PieceType.WHITE_KING else PieceType.BLACK_KING
            diffRatio > 0.46 -> if (isWhitePiece) PieceType.WHITE_QUEEN else PieceType.BLACK_QUEEN
            diffRatio > 0.38 -> if (isWhitePiece) PieceType.WHITE_ROOK else PieceType.BLACK_ROOK
            diffRatio > 0.30 -> if (isWhitePiece) PieceType.WHITE_BISHOP else PieceType.BLACK_BISHOP
            diffRatio > 0.22 -> if (isWhitePiece) PieceType.WHITE_KNIGHT else PieceType.BLACK_KNIGHT
            diffRatio > 0.08 -> if (isWhitePiece) PieceType.WHITE_PAWN else PieceType.BLACK_PAWN
            else -> PieceType.EMPTY
        }
    }

    private fun detectOrientation(pieces: Array<Array<PieceType>>): Boolean {
        var topBlackCount = 0; var bottomBlackCount = 0
        var topWhiteCount = 0; var bottomWhiteCount = 0

        for (col in 0 until 8) {
            val top1 = pieces[0][col]; val top2 = pieces[1][col]
            if (top1 != PieceType.EMPTY && top1.fenChar.isLowerCase()) topBlackCount++
            if (top2 != PieceType.EMPTY && top2.fenChar.isLowerCase()) topBlackCount++
            if (top1 != PieceType.EMPTY && top1.fenChar.isUpperCase()) topWhiteCount++
            if (top2 != PieceType.EMPTY && top2.fenChar.isUpperCase()) topWhiteCount++

            val bot1 = pieces[6][col]; val bot2 = pieces[7][col]
            if (bot1 != PieceType.EMPTY && bot1.fenChar.isLowerCase()) bottomBlackCount++
            if (bot2 != PieceType.EMPTY && bot2.fenChar.isLowerCase()) bottomBlackCount++
            if (bot1 != PieceType.EMPTY && bot1.fenChar.isUpperCase()) bottomWhiteCount++
            if (bot2 != PieceType.EMPTY && bot2.fenChar.isUpperCase()) bottomWhiteCount++
        }

        return bottomBlackCount > topBlackCount
    }

    fun boardToFEN(boardResult: BoardResult): String {
        val builder = StringBuilder()
        for (row in 0 until 8) {
            var emptyCount = 0
            for (col in 0 until 8) {
                val piece = boardResult.pieces[row][col]
                if (piece == PieceType.EMPTY) { emptyCount++ }
                else {
                    if (emptyCount > 0) { builder.append(emptyCount); emptyCount = 0 }
                    builder.append(piece.fenChar)
                }
            }
            if (emptyCount > 0) builder.append(emptyCount)
            if (row < 7) builder.append("/")
        }
        builder.append(" w KQkq - 0 1")
        return builder.toString()
    }

    private fun colorDistance(r1: Int, g1: Int, b1: Int, r2: Int, g2: Int, b2: Int): Double {
        val dr = r1 - r2; val dg = g1 - g2; val db = b1 - b2
        return sqrt((dr * dr + dg * dg + db * db).toDouble())
    }

    private fun averageColor(colors: List<IntArray>): IntArray {
        if (colors.isEmpty()) return intArrayOf(128, 128, 128)
        var r = 0L; var g = 0L; var b = 0L
        for (c in colors) { r += c[0]; g += c[1]; b += c[2] }
        val n = colors.size
        return intArrayOf((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }
}
