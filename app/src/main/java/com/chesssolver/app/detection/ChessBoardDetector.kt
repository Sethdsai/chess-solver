package com.chesssolver.app.detection

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

data class BoardResult(
    val boardRect: Rect,
    val pieces: Array<Array<PieceType>>,  // 8x8 grid
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
        // HSV thresholds for board squares
        private val LIGHT_SQUARE_HUE_RANGE = 30.0..60.0   // Cream/beige
        private val DARK_SQUARE_HUE_RANGE = 20.0..45.0     // Brown/dark
    }

    fun detectBoard(bitmap: Bitmap): BoardResult? {
        try {
            // Step 1: Find the board region by detecting the chess grid pattern
            val boardRect = findBoardRegion(bitmap) ?: return null

            // Step 2: Analyze each square
            val cellWidth = boardRect.width() / 8f
            val cellHeight = boardRect.height() / 8f

            val pieces = Array(8) { Array(8) { PieceType.EMPTY } }

            for (row in 0 until 8) {
                for (col in 0 until 8) {
                    val left = boardRect.left + (col * cellWidth).toInt()
                    val top = boardRect.top + (row * cellHeight).toInt()
                    val right = boardRect.left + ((col + 1) * cellWidth).toInt()
                    val bottom = boardRect.top + ((row + 1) * cellHeight).toInt()

                    // Analyze the center region of the square (avoid borders)
                    val margin = (cellWidth * 0.2f).toInt()
                    val squareBitmap = Bitmap.createBitmap(
                        bitmap,
                        (left + margin).coerceAtLeast(0),
                        (top + margin).coerceAtLeast(0),
                        ((right - left) - 2 * margin).coerceAtMost(bitmap.width - left),
                        ((bottom - top) - 2 * margin).coerceAtMost(bitmap.height - top)
                    )

                    val piece = detectPiece(squareBitmap, row, col)
                    pieces[row][col] = piece
                }
            }

            // Step 3: Detect board orientation (is black at bottom?)
            val isFlipped = detectOrientation(pieces)

            return BoardResult(boardRect, pieces, isFlipped)
        } catch (e: Exception) {
            Log.e(TAG, "Board detection failed", e)
            return null
        }
    }

    private fun findBoardRegion(bitmap: Bitmap): Rect? {
        // Scan the bitmap for the chess board grid pattern
        // Strategy: Find a large region with alternating light/dark squares
        // We downscale for performance
        val scale = 0.25f
        val smallWidth = (bitmap.width * scale).toInt()
        val smallHeight = (bitmap.height * scale).toInt()
        val small = Bitmap.createScaledBitmap(bitmap, smallWidth, smallHeight, false)

        // Convert to grayscale and detect edges
        val pixels = IntArray(smallWidth * smallHeight)
        small.getPixels(pixels, 0, smallWidth, 0, 0, smallWidth, smallHeight)

        // Find regions with high contrast grid patterns
        // We look for the largest square-ish region with alternating brightness
        val gridSize = smallWidth / 4  // approximate grid cell size

        var bestRect: Rect? = null
        var bestScore = 0.0

        // Scan with different possible board sizes
        val boardSizes = intArrayOf(smallWidth * 3 / 4, smallWidth * 4 / 5, smallWidth * 9 / 10, smallWidth)

        for (size in boardSizes) {
            for (startX in 0..(smallWidth - size) step gridSize / 2) {
                for (startY in 0..(smallHeight - size) step gridSize / 2) {
                    val score = scoreBoardRegion(pixels, smallWidth, startX, startY, size)
                    if (score > bestScore) {
                        bestScore = score
                        // Convert back to original coordinates
                        bestRect = Rect(
                            (startX / scale).toInt(),
                            (startY / scale).toInt(),
                            ((startX + size) / scale).toInt(),
                            ((startY + size) / scale).toInt()
                        )
                    }
                }
            }
        }

        small.recycle()
        return bestRect
    }

    private fun scoreBoardRegion(pixels: IntArray, width: Int, x: Int, y: Int, size: Int): Double {
        // Score a region based on how well it matches a chess board pattern
        // We check for alternating light/dark in a grid
        val cellSize = size / 8
        if (cellSize < 2) return 0.0

        var alternatingCount = 0
        var totalCount = 0

        for (row in 0 until 8) {
            for (col in 0 until 8) {
                val cx = x + col * cellSize + cellSize / 2
                val cy = y + row * cellSize + cellSize / 2
                if (cx >= width || cy >= pixels.size / width) continue

                val brightness = getBrightness(pixels[cy * width + cx])

                // Check right neighbor
                if (col < 7) {
                    val nx = x + (col + 1) * cellSize + cellSize / 2
                    if (nx < width && cy < pixels.size / width) {
                        val neighborBrightness = getBrightness(pixels[cy * width + nx])
                        if (abs(brightness - neighborBrightness) > 30) {
                            alternatingCount++
                        }
                        totalCount++
                    }
                }

                // Check bottom neighbor
                if (row < 7) {
                    val ny = y + (row + 1) * cellSize + cellSize / 2
                    if (cx < width && ny < pixels.size / width) {
                        val neighborBrightness = getBrightness(pixels[ny * width + cx])
                        if (abs(brightness - neighborBrightness) > 30) {
                            alternatingCount++
                        }
                        totalCount++
                    }
                }
            }
        }

        return if (totalCount > 0) alternatingCount.toDouble() / totalCount.toDouble() else 0.0
    }

    private fun getBrightness(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (r * 0.299 + g * 0.587 + b * 0.114).toInt()
    }

    private fun detectPiece(squareBitmap: Bitmap, row: Int, col: Int): PieceType {
        // Analyze the center area of the square for piece detection
        val width = squareBitmap.width
        val height = squareBitmap.height
        if (width <= 0 || height <= 0) return PieceType.EMPTY

        val pixels = IntArray(width * height)
        squareBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // Determine square color (light or dark)
        val isLightSquare = (row + col) % 2 == 0
        val squareBrightness = getBrightness(pixels[height / 2 * width + width / 2])

        // Count distinct color clusters in the square
        var darkPixels = 0
        var lightPixels = 0
        var coloredPixels = 0

        for (pixel in pixels) {
            val brightness = getBrightness(pixel)
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF

            // Check if this pixel is significantly different from the square background
            val diff = abs(brightness - squareBrightness)
            if (diff > 40) {
                if (brightness < 80) darkPixels++
                else if (brightness > 180) lightPixels++
                else coloredPixels++
            }
        }

        val totalPixels = width * height
        val darkRatio = darkPixels.toFloat() / totalPixels
        val lightRatio = lightPixels.toFloat() / totalPixels
        val piecePixelRatio = (darkPixels + lightPixels + coloredPixels).toFloat() / totalPixels

        // If very few pixels differ from background, likely empty square
        if (piecePixelRatio < 0.08f) return PieceType.EMPTY

        // Determine if it's a white or black piece based on the dominant piece color
        val isWhitePiece = lightRatio > darkRatio

        // For a more complete implementation, we'd use template matching or ML
        // For now, use heuristics based on piece pixel count (size of piece silhouette)
        val pieceSize = darkPixels + lightPixels + coloredPixels

        return when {
            pieceSize < totalPixels * 0.12 -> PieceType.EMPTY
            isWhitePiece -> guessWhitePiece(pieceSize, totalPixels, isLightSquare)
            else -> guessBlackPiece(pieceSize, totalPixels, isLightSquare)
        }
    }

    private fun guessWhitePiece(piecePixels: Int, totalPixels: Int, isLightSquare: Boolean): PieceType {
        val ratio = piecePixels.toFloat() / totalPixels
        // Rough heuristics based on silhouette size
        return when {
            ratio > 0.5 -> PieceType.WHITE_KING
            ratio > 0.42 -> PieceType.WHITE_QUEEN
            ratio > 0.35 -> PieceType.WHITE_ROOK
            ratio > 0.28 -> PieceType.WHITE_BISHOP
            ratio > 0.22 -> PieceType.WHITE_KNIGHT
            else -> PieceType.WHITE_PAWN
        }
    }

    private fun guessBlackPiece(piecePixels: Int, totalPixels: Int, isLightSquare: Boolean): PieceType {
        val ratio = piecePixels.toFloat() / totalPixels
        return when {
            ratio > 0.5 -> PieceType.BLACK_KING
            ratio > 0.42 -> PieceType.BLACK_QUEEN
            ratio > 0.35 -> PieceType.BLACK_ROOK
            ratio > 0.28 -> PieceType.BLACK_BISHOP
            ratio > 0.22 -> PieceType.BLACK_KNIGHT
            else -> PieceType.BLACK_PAWN
        }
    }

    private fun detectOrientation(pieces: Array<Array<PieceType>>): Boolean {
        // Check if black pieces are in the top rows (normal) or bottom (flipped)
        var topDarkCount = 0
        var bottomDarkCount = 0

        for (col in 0 until 8) {
            // Top 2 rows
            if (pieces[0][col] != PieceType.EMPTY && pieces[0][col].fenChar.isLowerCase()) topDarkCount++
            if (pieces[1][col] != PieceType.EMPTY && pieces[1][col].fenChar.isLowerCase()) topDarkCount++
            // Bottom 2 rows
            if (pieces[6][col] != PieceType.EMPTY && pieces[6][col].fenChar.isLowerCase()) bottomDarkCount++
            if (pieces[7][col] != PieceType.EMPTY && pieces[7][col].fenChar.isLowerCase()) bottomDarkCount++
        }

        // If more dark pieces at bottom, board is flipped
        return bottomDarkCount > topDarkCount
    }

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
            if (emptyCount > 0) {
                builder.append(emptyCount)
            }
            if (row < 7) {
                builder.append("/")
            }
        }

        // Add active color, castling, en passant
        builder.append(" w KQkq - 0 1")

        return builder.toString()
    }
}
