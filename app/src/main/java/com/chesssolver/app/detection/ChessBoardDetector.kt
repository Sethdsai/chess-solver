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
        
        // Known chess board color themes (light square RGB, dark square RGB)
        // These are common themes from Chess.com and Lichess
        private val KNOWN_THEMES = listOf(
            // Chess.com default (green theme)
            intArrayOf(238, 216, 192) to intArrayOf(171, 122, 101),
            // Chess.com green
            intArrayOf(240, 217, 181) to intArrayOf(181, 136, 99),
            // Lichess default  
            intArrayOf(240, 217, 181) to intArrayOf(181, 136, 99),
            // Lichess brown
            intArrayOf(215, 192, 160) to intArrayOf(170, 130, 96),
            // Blue theme
            intArrayOf(224, 224, 240) to intArrayOf(136, 136, 204),
            // Purple theme
            intArrayOf(232, 216, 240) to intArrayOf(152, 120, 168),
            // Wood theme
            intArrayOf(222, 194, 150) to intArrayOf(160, 114, 76),
            // Grey theme
            intArrayOf(216, 216, 216) to intArrayOf(152, 152, 152),
            // Coral theme
            intArrayOf(240, 200, 180) to intArrayOf(180, 120, 100),
            // Tournament theme
            intArrayOf(235, 215, 180) to intArrayOf(170, 128, 90)
        )
        
        // Color distance threshold for theme matching
        private const val THEME_THRESHOLD = 80
    }

    /**
     * Detect chess board from a screen bitmap.
     * Returns null if no board is found.
     */
    fun detectBoard(bitmap: Bitmap): BoardResult? {
        try {
            // Step 1: Find the board region
            val boardRect = findBoardRegion(bitmap)
            if (boardRect == null) {
                Log.w(TAG, "No board region found")
                return null
            }

            Log.d(TAG, "Board region found: $boardRect")

            // Step 2: Detect which color theme the board uses
            val theme = detectTheme(bitmap, boardRect)
            Log.d(TAG, "Detected theme: light=${theme?.first?.toList()}, dark=${theme?.second?.toList()}")

            // Step 3: Analyze each square
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

            // Step 4: Detect board orientation
            val isFlipped = detectOrientation(pieces)

            return BoardResult(boardRect, pieces, isFlipped)
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
     * Strategy: Look for the largest square region with alternating dark/light patterns
     * that match known chess board color themes.
     */
    private fun findBoardRegion(bitmap: Bitmap): Rect? {
        // Downscale for performance
        val scale = 0.15f
        val smallW = (bitmap.width * scale).toInt()
        val smallH = (bitmap.height * scale).toInt()
        val small = Bitmap.createScaledBitmap(bitmap, smallW, smallH, false)

        val pixels = IntArray(smallW * smallH)
        small.getPixels(pixels, 0, smallW, 0, 0, smallW, smallH)

        // Find regions with high "chess-ness" score
        var bestRect: Rect? = null
        var bestScore = -1.0

        // Try different board sizes (relative to screen)
        val sizeRatios = floatArrayOf(0.95f, 0.85f, 0.75f, 0.65f, 0.55f, 0.45f)
        val stepSize = smallW / 12  // Scan step

        for (sizeRatio in sizeRatios) {
            val boardSize = (minOf(smallW, smallH) * sizeRatio).toInt()
            if (boardSize < 16) continue

            for (startX in 0..(smallW - boardSize) step stepSize) {
                for (startY in 0..(smallH - boardSize) step stepSize) {
                    val score = scoreChessRegion(pixels, smallW, smallH, startX, startY, boardSize)
                    if (score > bestScore) {
                        bestScore = score
                        bestRect = Rect(
                            (startX / scale).toInt(),
                            (startY / scale).toInt(),
                            ((startX + boardSize) / scale).toInt(),
                            ((startY + boardSize) / scale).toInt()
                        )
                    }
                }
            }
        }

        small.recycle()

        // Only return if we found a reasonably good match
        if (bestScore < 0.35) {
            Log.w(TAG, "Best chess score too low: $bestScore")
            return null
        }

        Log.d(TAG, "Best chess score: $bestScore")
        return bestRect
    }

    /**
     * Score a region for "chess-ness" - how well it matches a chess board pattern.
     */
    private fun scoreChessRegion(pixels: IntArray, width: Int, height: Int, x: Int, y: Int, size: Int): Double {
        val cellSize = size / 8
        if (cellSize < 2) return 0.0

        var alternatingCount = 0
        var themeMatchCount = 0
        var totalChecks = 0
        val lightColors = mutableListOf<IntArray>()
        val darkColors = mutableListOf<IntArray>()

        for (row in 0 until 8) {
            for (col in 0 until 8) {
                val cx = x + col * cellSize + cellSize / 2
                val cy = y + row * cellSize + cellSize / 2
                if (cx >= width || cy >= height || cx < 0 || cy < 0) continue

                val pixel = pixels[cy * width + cx]
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val brightness = (r * 0.299 + g * 0.587 + b * 0.114).toInt()
                val isLight = (row + col) % 2 == 0

                // Collect colors for theme detection
                if (isLight) lightColors.add(intArrayOf(r, g, b))
                else darkColors.add(intArrayOf(r, g, b))

                // Check alternating pattern with right neighbor
                if (col < 7) {
                    val nx = x + (col + 1) * cellSize + cellSize / 2
                    if (nx < width && cy < height) {
                        val np = pixels[cy * width + nx]
                        val nr = Color.red(np)
                        val ng = Color.green(np)
                        val nb = Color.blue(np)
                        val nbright = (nr * 0.299 + ng * 0.587 + nb * 0.114).toInt()
                        val colorDist = colorDistance(r, g, b, nr, ng, nb)
                        if (colorDist > 25) alternatingCount++
                        totalChecks++
                    }
                }

                // Check alternating pattern with bottom neighbor
                if (row < 7) {
                    val ny = y + (row + 1) * cellSize + cellSize / 2
                    if (cx < width && ny < height) {
                        val np = pixels[ny * width + cx]
                        val nr = Color.red(np)
                        val ng = Color.green(np)
                        val nb = Color.blue(np)
                        val colorDist = colorDistance(r, g, b, nr, ng, nb)
                        if (colorDist > 25) alternatingCount++
                        totalChecks++
                    }
                }
            }
        }

        if (totalChecks == 0) return 0.0

        val alternationScore = alternatingCount.toDouble() / totalChecks.toDouble()

        // Check if colors match any known chess theme
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

        val themeScore = if (themeMatchCount > 0) 0.3 else 0.0

        return alternationScore + themeScore
    }

    /**
     * Detect the color theme of the board.
     */
    private fun detectTheme(bitmap: Bitmap, boardRect: Rect): Pair<IntArray, IntArray>? {
        val cellWidth = boardRect.width() / 8f
        val cellHeight = boardRect.height() / 8f
        val lightColors = mutableListOf<IntArray>()
        val darkColors = mutableListOf<IntArray>()

        // Sample center pixels of each square
        for (row in 0 until 8) {
            for (col in 0 until 8) {
                val cx = boardRect.left + (col * cellWidth + cellWidth / 2).toInt()
                val cy = boardRect.top + (row * cellHeight + cellHeight / 2).toInt()
                if (cx >= bitmap.width || cy >= bitmap.height || cx < 0 || cy < 0) continue

                val pixel = bitmap.getPixel(cx, cy)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                if ((row + col) % 2 == 0) {
                    lightColors.add(intArrayOf(r, g, b))
                } else {
                    darkColors.add(intArrayOf(r, g, b))
                }
            }
        }

        if (lightColors.isEmpty() || darkColors.isEmpty()) return null

        // Return average colors as the detected theme
        return averageColor(lightColors) to averageColor(darkColors)
    }

    /**
     * Analyze a single square to determine what piece (if any) is on it.
     */
    private fun analyzeSquare(
        bitmap: Bitmap,
        left: Int, top: Int,
        width: Int, height: Int,
        row: Int, col: Int,
        theme: Pair<IntArray, IntArray>?
    ): PieceType {
        if (width <= 4 || height <= 4) return PieceType.EMPTY

        // Analyze center region of the square (avoid borders/lines)
        val margin = (minOf(width, height) * 0.2f).toInt()
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

        // Count pixels that differ significantly from the expected background
        var differentPixels = 0
        var darkDifferent = 0
        var lightDifferent = 0
        var totalR = 0L
        var totalG = 0L
        var totalB = 0L
        var diffR = 0L
        var diffG = 0L
        var diffB = 0L

        for (pixel in pixels) {
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            totalR += r
            totalG += g
            totalB += b

            if (expectedBg != null) {
                val dist = colorDistance(r, g, b, expectedBg[0], expectedBg[1], expectedBg[2])
                if (dist > 40) {
                    differentPixels++
                    diffR += r
                    diffG += g
                    diffB += b
                    val brightness = (r * 0.299 + g * 0.587 + b * 0.114).toInt()
                    if (brightness < 100) darkDifferent++
                    else if (brightness > 160) lightDifferent++
                }
            } else {
                // No theme - use variance-based detection
                val avgBrightness = (totalR * 0.299 + totalG * 0.587 + totalB * 0.114) / pixels.size
                val brightness = (r * 0.299 + g * 0.587 + b * 0.114).toInt()
                if (abs(brightness - avgBrightness.toInt()) > 40) {
                    differentPixels++
                    if (brightness < 100) darkDifferent++
                    else if (brightness > 160) lightDifferent++
                }
            }
        }

        val totalPixels = pixels.size
        val diffRatio = differentPixels.toFloat() / totalPixels

        // If very few pixels differ from background, it's an empty square
        if (diffRatio < 0.10f) return PieceType.EMPTY

        // Determine piece color
        val isWhitePiece = lightDifferent > darkDifferent

        // Determine piece type by the amount and pattern of different pixels
        return classifyPiece(diffRatio, isWhitePiece)
    }

    /**
     * Classify a piece based on the ratio of different pixels and its color.
     */
    private fun classifyPiece(diffRatio: Float, isWhitePiece: Boolean): PieceType {
        // These thresholds are approximate and based on piece silhouette sizes
        // relative to the square area
        val piece = when {
            diffRatio > 0.55 -> if (isWhitePiece) PieceType.WHITE_KING else PieceType.BLACK_KING
            diffRatio > 0.46 -> if (isWhitePiece) PieceType.WHITE_QUEEN else PieceType.BLACK_QUEEN
            diffRatio > 0.38 -> if (isWhitePiece) PieceType.WHITE_ROOK else PieceType.BLACK_ROOK
            diffRatio > 0.30 -> if (isWhitePiece) PieceType.WHITE_BISHOP else PieceType.BLACK_BISHOP
            diffRatio > 0.22 -> if (isWhitePiece) PieceType.WHITE_KNIGHT else PieceType.BLACK_KNIGHT
            diffRatio > 0.10 -> if (isWhitePiece) PieceType.WHITE_PAWN else PieceType.BLACK_PAWN
            else -> PieceType.EMPTY
        }
        return piece
    }

    /**
     * Detect if the board is flipped (black pieces at bottom).
     */
    private fun detectOrientation(pieces: Array<Array<PieceType>>): Boolean {
        var topBlackCount = 0
        var bottomBlackCount = 0
        var topWhiteCount = 0
        var bottomWhiteCount = 0

        for (col in 0 until 8) {
            // Top 2 rows
            val top1 = pieces[0][col]
            val top2 = pieces[1][col]
            if (top1 != PieceType.EMPTY && top1.fenChar.isLowerCase()) topBlackCount++
            if (top2 != PieceType.EMPTY && top2.fenChar.isLowerCase()) topBlackCount++
            if (top1 != PieceType.EMPTY && top1.fenChar.isUpperCase()) topWhiteCount++
            if (top2 != PieceType.EMPTY && top2.fenChar.isUpperCase()) topWhiteCount++

            // Bottom 2 rows
            val bot1 = pieces[6][col]
            val bot2 = pieces[7][col]
            if (bot1 != PieceType.EMPTY && bot1.fenChar.isLowerCase()) bottomBlackCount++
            if (bot2 != PieceType.EMPTY && bot2.fenChar.isLowerCase()) bottomBlackCount++
            if (bot1 != PieceType.EMPTY && bot1.fenChar.isUpperCase()) bottomWhiteCount++
            if (bot2 != PieceType.EMPTY && bot2.fenChar.isUpperCase()) bottomWhiteCount++
        }

        // Flipped if more dark pieces at bottom
        return bottomBlackCount > topBlackCount
    }

    /**
     * Convert detected board to FEN string.
     */
    fun boardToFEN(boardResult: BoardResult): String {
        val builder = StringBuilder()

        for (row in 0 until 8) {
            var emptyCount = 0
            for (col in 0 until 8) {
                val piece = boardResult.pieces[row][col]
                if (piece == PieceType.EMPTY) {
                    emptyCount++
                } else {
                    if (emptyCount > 0) {
                        builder.append(emptyCount)
                        emptyCount = 0
                    }
                    builder.append(piece.fenChar)
                }
            }
            if (emptyCount > 0) builder.append(emptyCount)
            if (row < 7) builder.append("/")
        }

        // Active color, castling, en passant
        builder.append(" w KQkq - 0 1")
        return builder.toString()
    }

    // Utility: Euclidean color distance
    private fun colorDistance(r1: Int, g1: Int, b1: Int, r2: Int, g2: Int, b2: Int): Double {
        val dr = r1 - r2
        val dg = g1 - g2
        val db = b1 - b2
        return sqrt((dr * dr + dg * dg + db * db).toDouble())
    }

    // Utility: Average of a list of RGB colors
    private fun averageColor(colors: List<IntArray>): IntArray {
        if (colors.isEmpty()) return intArrayOf(128, 128, 128)
        var r = 0L; var g = 0L; var b = 0L
        for (c in colors) { r += c[0]; g += c[1]; b += c[2] }
        val n = colors.size
        return intArrayOf((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }
}
