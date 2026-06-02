package com.chesssolver.app.engine

import kotlin.math.max
import kotlin.math.min

/**
 * Pure Kotlin chess engine with alpha-beta search.
 * No native dependencies - works on all Android devices.
 */
class ChessEngine {

    // Board representation: 8x8 array
    // Positive = white pieces, Negative = black pieces, 0 = empty
    companion object {
        const val EMPTY = 0
        const val PAWN = 1
        const val KNIGHT = 2
        const val BISHOP = 3
        const val ROOK = 4
        const val QUEEN = 5
        const val KING = 6

        // Piece values for evaluation
        val PIECE_VALUES = intArrayOf(0, 100, 320, 330, 500, 900, 20000)

        // Piece-square tables (from white's perspective, flip for black)
        // Encourages pieces to be on good squares
        val PST_PAWN = intArrayOf(
             0,  0,  0,  0,  0,  0,  0,  0,
            50, 50, 50, 50, 50, 50, 50, 50,
            10, 10, 20, 30, 30, 20, 10, 10,
             5,  5, 10, 25, 25, 10,  5,  5,
             0,  0,  0, 20, 20,  0,  0,  0,
             5, -5,-10,  0,  0,-10, -5,  5,
             5, 10, 10,-20,-20, 10, 10,  5,
             0,  0,  0,  0,  0,  0,  0,  0
        )
        val PST_KNIGHT = intArrayOf(
            -50,-40,-30,-30,-30,-30,-40,-50,
            -40,-20,  0,  0,  0,  0,-20,-40,
            -30,  0, 10, 15, 15, 10,  0,-30,
            -30,  5, 15, 20, 20, 15,  5,-30,
            -30,  0, 15, 20, 20, 15,  0,-30,
            -30,  5, 10, 15, 15, 10,  5,-30,
            -40,-20,  0,  5,  5,  0,-20,-40,
            -50,-40,-30,-30,-30,-30,-40,-50
        )
        val PST_BISHOP = intArrayOf(
            -20,-10,-10,-10,-10,-10,-10,-20,
            -10,  0,  0,  0,  0,  0,  0,-10,
            -10,  0, 10, 10, 10, 10,  0,-10,
            -10,  5,  5, 10, 10,  5,  5,-10,
            -10,  0,  5, 10, 10,  5,  0,-10,
            -10, 10,  5, 10, 10,  5, 10,-10,
            -10,  5,  0,  0,  0,  0,  5,-10,
            -20,-10,-10,-10,-10,-10,-10,-20
        )
        val PST_ROOK = intArrayOf(
             0,  0,  0,  0,  0,  0,  0,  0,
             5, 10, 10, 10, 10, 10, 10,  5,
            -5,  0,  0,  0,  0,  0,  0, -5,
            -5,  0,  0,  0,  0,  0,  0, -5,
            -5,  0,  0,  0,  0,  0,  0, -5,
            -5,  0,  0,  0,  0,  0,  0, -5,
            -5,  0,  0,  0,  0,  0,  0, -5,
             0,  0,  0,  5,  5,  0,  0,  0
        )
        val PST_QUEEN = intArrayOf(
            -20,-10,-10, -5, -5,-10,-10,-20,
            -10,  0,  0,  0,  0,  0,  0,-10,
            -10,  0,  5,  5,  5,  5,  0,-10,
             -5,  0,  5,  5,  5,  5,  0, -5,
              0,  0,  5,  5,  5,  5,  0, -5,
            -10,  5,  5,  5,  5,  5,  0,-10,
            -10,  0,  5,  0,  0,  0,  0,-10,
            -20,-10,-10, -5, -5,-10,-10,-20
        )
        val PST_KING_MID = intArrayOf(
            -30,-40,-40,-50,-50,-40,-40,-30,
            -30,-40,-40,-50,-50,-40,-40,-30,
            -30,-40,-40,-50,-50,-40,-40,-30,
            -30,-40,-40,-50,-50,-40,-40,-30,
            -20,-30,-30,-40,-40,-30,-30,-20,
            -10,-20,-20,-20,-20,-20,-20,-10,
             20, 20,  0,  0,  0,  0, 20, 20,
             20, 30, 10,  0,  0, 10, 30, 20
        )
    }

    data class Move(
        val fromRow: Int, val fromCol: Int,
        val toRow: Int, val toCol: Int,
        val promotion: Int = 0  // 0=none, QUEEN/ROOK/BISHOP/KNIGHT
    ) {
        fun toUCI(): String {
            val fromFile = 'a' + fromCol
            val fromRank = '8' - fromRow
            val toFile = 'a' + toCol
            val toRank = '8' - toRow
            val promo = when (promotion) {
                QUEEN -> "q"
                ROOK -> "r"
                BISHOP -> "b"
                KNIGHT -> "n"
                else -> ""
            }
            return "$fromFile$fromRank$toFile$toRank$promo"
        }
    }

    data class Position(
        val board: Array<IntArray> = Array(8) { IntArray(8) { EMPTY } },
        var whiteToMove: Boolean = true,
        var castling: String = "KQkq",
        var enPassant: String = "-",
        var halfmove: Int = 0,
        var fullmove: Int = 1
    ) {
        fun copy(): Position {
            return Position(
                board = Array(8) { r -> board[r].copyOf() },
                whiteToMove = whiteToMove,
                castling = castling,
                enPassant = enPassant,
                halfmove = halfmove,
                fullmove = fullmove
            )
        }
    }

    private var position = Position()

    fun setPosition(fen: String) {
        position = parseFEN(fen)
    }

    fun getBestMove(depth: Int = 4, timeMs: Int = 3000): String? {
        val moves = generateLegalMoves(position)
        if (moves.isEmpty()) return null

        var bestMove: Move? = null
        var bestScore = if (position.whiteToMove) Int.MIN_VALUE else Int.MAX_VALUE

        // Move ordering: captures first, then central moves
        val orderedMoves = moves.sortedByDescending { move ->
            var score = 0
            val captured = position.board[move.toRow][move.toCol]
            if (captured != EMPTY) score += abs(captured) * 10 - abs(position.board[move.fromRow][move.fromCol])
            if (move.promotion != 0) score += 800
            // Center moves
            if (move.toRow in 2..5 && move.toCol in 2..5) score += 5
            score
        }

        val startTime = System.currentTimeMillis()

        for (move in orderedMoves) {
            if (System.currentTimeMillis() - startTime > timeMs) break

            val newPos = makeMove(position, move)
            val score = alphaBeta(newPos, depth - 1, Int.MIN_VALUE + 1, Int.MAX_VALUE - 1, !position.whiteToMove, startTime, timeMs)

            if (position.whiteToMove) {
                if (score > bestScore) {
                    bestScore = score
                    bestMove = move
                }
            } else {
                if (score < bestScore) {
                    bestScore = score
                    bestMove = move
                }
            }
        }

        return bestMove?.toUCI()
    }

    private fun alphaBeta(pos: Position, depth: Int, alpha: Int, beta: Int, maximizing: Boolean, startTime: Long, timeMs: Int): Int {
        if (System.currentTimeMillis() - startTime > timeMs) return evaluate(pos)
        if (depth == 0) return quiescence(pos, alpha, beta, 3, startTime, timeMs)

        val moves = generateLegalMoves(pos)
        if (moves.isEmpty()) {
            // Check if it's checkmate or stalemate
            return if (isInCheck(pos, pos.whiteToMove)) {
                if (maximizing) Int.MIN_VALUE + 100 else Int.MAX_VALUE - 100
            } else {
                0  // Stalemate
            }
        }

        var a = alpha
        var b = beta

        // Move ordering for better pruning
        val orderedMoves = moves.sortedByDescending { move ->
            var score = 0
            val captured = pos.board[move.toRow][move.toCol]
            if (captured != EMPTY) score += abs(captured) * 10 - abs(pos.board[move.fromRow][move.fromCol])
            if (move.promotion != 0) score += 800
            score
        }

        if (maximizing) {
            var value = Int.MIN_VALUE
            for (move in orderedMoves) {
                val newPos = makeMove(pos, move)
                value = max(value, alphaBeta(newPos, depth - 1, a, b, false, startTime, timeMs))
                a = max(a, value)
                if (value >= b) break  // Beta cutoff
            }
            return value
        } else {
            var value = Int.MAX_VALUE
            for (move in orderedMoves) {
                val newPos = makeMove(pos, move)
                value = min(value, alphaBeta(newPos, depth - 1, a, b, true, startTime, timeMs))
                b = min(b, value)
                if (value <= a) break  // Alpha cutoff
            }
            return value
        }
    }

    // Quiescence search - search captures to avoid horizon effect
    private fun quiescence(pos: Position, alpha: Int, beta: Int, depth: Int, startTime: Long, timeMs: Int): Int {
        if (System.currentTimeMillis() - startTime > timeMs) return evaluate(pos)

        val standPat = evaluate(pos)

        if (depth == 0) return standPat

        if (pos.whiteToMove) {
            if (standPat >= beta) return beta
            var a = max(alpha, standPat)
            val captures = generateLegalMoves(pos).filter { pos.board[it.toRow][it.toCol] != EMPTY || it.promotion != 0 }
            for (move in captures) {
                val newPos = makeMove(pos, move)
                val score = quiescence(newPos, a, beta, depth - 1, startTime, timeMs)
                a = max(a, score)
                if (a >= beta) return beta
            }
            return a
        } else {
            if (standPat <= alpha) return alpha
            var b = min(beta, standPat)
            val captures = generateLegalMoves(pos).filter { pos.board[it.toRow][it.toCol] != EMPTY || it.promotion != 0 }
            for (move in captures) {
                val newPos = makeMove(pos, move)
                val score = quiescence(newPos, alpha, b, depth - 1, startTime, timeMs)
                b = min(b, score)
                if (b <= alpha) return alpha
            }
            return b
        }
    }

    fun evaluate(pos: Position): Int {
        var score = 0
        for (row in 0..7) {
            for (col in 0..7) {
                val piece = pos.board[row][col]
                if (piece == EMPTY) continue
                val absPiece = abs(piece)
                val isWhite = piece > 0

                // Material value
                var value = PIECE_VALUES[absPiece]

                // Piece-square table bonus
                val pstIndex = if (isWhite) row * 8 + col else (7 - row) * 8 + col
                value += when (absPiece) {
                    PAWN -> PST_PAWN[pstIndex]
                    KNIGHT -> PST_KNIGHT[pstIndex]
                    BISHOP -> PST_BISHOP[pstIndex]
                    ROOK -> PST_ROOK[pstIndex]
                    QUEEN -> PST_QUEEN[pstIndex]
                    KING -> PST_KING_MID[pstIndex]
                    else -> 0
                }

                score += if (isWhite) value else -value
            }
        }
        return score
    }

    // Generate all legal moves for the position
    fun generateLegalMoves(pos: Position): List<Move> {
        val pseudoMoves = generatePseudoLegalMoves(pos)
        return pseudoMoves.filter { move ->
            val newPos = makeMove(pos, move)
            !isInCheck(newPos, pos.whiteToMove)
        }
    }

    private fun generatePseudoLegalMoves(pos: Position): List<Move> {
        val moves = mutableListOf<Move>()
        for (row in 0..7) {
            for (col in 0..7) {
                val piece = pos.board[row][col]
                if (piece == EMPTY) continue
                val isWhite = piece > 0
                if (isWhite != pos.whiteToMove) continue

                val absPiece = abs(piece)
                when (absPiece) {
                    PAWN -> generatePawnMoves(pos, row, col, isWhite, moves)
                    KNIGHT -> generateKnightMoves(pos, row, col, isWhite, moves)
                    BISHOP -> generateSlidingMoves(pos, row, col, isWhite, moves, bishopDirs)
                    ROOK -> generateSlidingMoves(pos, row, col, isWhite, moves, rookDirs)
                    QUEEN -> generateSlidingMoves(pos, row, col, isWhite, moves, queenDirs)
                    KING -> generateKingMoves(pos, row, col, isWhite, moves)
                }
            }
        }
        return moves
    }

    private val bishopDirs = listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)
    private val rookDirs = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
    private val queenDirs = bishopDirs + rookDirs
    private val knightOffsets = listOf(-2 to -1, -2 to 1, -1 to -2, -1 to 2, 1 to -2, 1 to 2, 2 to -1, 2 to 1)
    private val kingOffsets = listOf(-1 to -1, -1 to 0, -1 to 1, 0 to -1, 0 to 1, 1 to -1, 1 to 0, 1 to 1)

    private fun generatePawnMoves(pos: Position, row: Int, col: Int, isWhite: Boolean, moves: MutableList<Move>) {
        val dir = if (isWhite) -1 else 1
        val startRow = if (isWhite) 6 else 1
        val promoRow = if (isWhite) 0 else 7

        // Forward one
        val newRow = row + dir
        if (newRow in 0..7 && pos.board[newRow][col] == EMPTY) {
            if (newRow == promoRow) {
                for (promo in intArrayOf(QUEEN, ROOK, BISHOP, KNIGHT)) {
                    moves.add(Move(row, col, newRow, col, promo))
                }
            } else {
                moves.add(Move(row, col, newRow, col))
            }

            // Forward two from start
            if (row == startRow) {
                val twoRow = row + 2 * dir
                if (pos.board[twoRow][col] == EMPTY) {
                    moves.add(Move(row, col, twoRow, col))
                }
            }
        }

        // Captures
        for (dc in intArrayOf(-1, 1)) {
            val nc = col + dc
            if (nc !in 0..7 || newRow !in 0..7) continue
            val target = pos.board[newRow][nc]
            if (target != EMPTY && (target > 0) != isWhite) {
                if (newRow == promoRow) {
                    for (promo in intArrayOf(QUEEN, ROOK, BISHOP, KNIGHT)) {
                        moves.add(Move(row, col, newRow, nc, promo))
                    }
                } else {
                    moves.add(Move(row, col, newRow, nc))
                }
            }

            // En passant
            if (pos.enPassant != "-") {
                val epCol = pos.enPassant[0] - 'a'
                val epRow = 8 - (pos.enPassant[1] - '0')
                if (newRow == epRow && nc == epCol) {
                    moves.add(Move(row, col, newRow, nc))
                }
            }
        }
    }

    private fun generateKnightMoves(pos: Position, row: Int, col: Int, isWhite: Boolean, moves: MutableList<Move>) {
        for ((dr, dc) in knightOffsets) {
            val nr = row + dr
            val nc = col + dc
            if (nr !in 0..7 || nc !in 0..7) continue
            val target = pos.board[nr][nc]
            if (target == EMPTY || (target > 0) != isWhite) {
                moves.add(Move(row, col, nr, nc))
            }
        }
    }

    private fun generateSlidingMoves(pos: Position, row: Int, col: Int, isWhite: Boolean, moves: MutableList<Move>, dirs: List<Pair<Int, Int>>) {
        for ((dr, dc) in dirs) {
            var nr = row + dr
            var nc = col + dc
            while (nr in 0..7 && nc in 0..7) {
                val target = pos.board[nr][nc]
                if (target == EMPTY) {
                    moves.add(Move(row, col, nr, nc))
                } else {
                    if ((target > 0) != isWhite) {
                        moves.add(Move(row, col, nr, nc))  // Capture
                    }
                    break
                }
                nr += dr
                nc += dc
            }
        }
    }

    private fun generateKingMoves(pos: Position, row: Int, col: Int, isWhite: Boolean, moves: MutableList<Move>) {
        for ((dr, dc) in kingOffsets) {
            val nr = row + dr
            val nc = col + dc
            if (nr !in 0..7 || nc !in 0..7) continue
            val target = pos.board[nr][nc]
            if (target == EMPTY || (target > 0) != isWhite) {
                moves.add(Move(row, col, nr, nc))
            }
        }

        // Castling
        val backRank = if (isWhite) 7 else 0
        if (row == backRank && col == 4) {
            // Kingside
            val ksFlag = if (isWhite) 'K' else 'k'
            if (pos.castling.contains(ksFlag)) {
                if (pos.board[backRank][5] == EMPTY && pos.board[backRank][6] == EMPTY) {
                    if (pos.board[backRank][7] == if (isWhite) ROOK else -ROOK) {
                        if (!isSquareAttacked(pos, backRank, 4, !isWhite) &&
                            !isSquareAttacked(pos, backRank, 5, !isWhite) &&
                            !isSquareAttacked(pos, backRank, 6, !isWhite)) {
                            moves.add(Move(row, col, backRank, 6))
                        }
                    }
                }
            }
            // Queenside
            val qsFlag = if (isWhite) 'Q' else 'q'
            if (pos.castling.contains(qsFlag)) {
                if (pos.board[backRank][3] == EMPTY && pos.board[backRank][2] == EMPTY && pos.board[backRank][1] == EMPTY) {
                    if (pos.board[backRank][0] == if (isWhite) ROOK else -ROOK) {
                        if (!isSquareAttacked(pos, backRank, 4, !isWhite) &&
                            !isSquareAttacked(pos, backRank, 3, !isWhite) &&
                            !isSquareAttacked(pos, backRank, 2, !isWhite)) {
                            moves.add(Move(row, col, backRank, 2))
                        }
                    }
                }
            }
        }
    }

    private fun isSquareAttacked(pos: Position, row: Int, col: Int, byWhite: Boolean): Boolean {
        // Check if any enemy piece attacks this square
        for (r in 0..7) {
            for (c in 0..7) {
                val piece = pos.board[r][c]
                if (piece == EMPTY) continue
                if ((piece > 0) != byWhite) continue
                val absPiece = abs(piece)
                when (absPiece) {
                    PAWN -> {
                        val dir = if (byWhite) -1 else 1
                        if (r + dir == row && (c - 1 == col || c + 1 == col)) return true
                    }
                    KNIGHT -> {
                        for ((dr, dc) in knightOffsets) {
                            if (r + dr == row && c + dc == col) return true
                        }
                    }
                    BISHOP -> {
                        if (checkSlidingAttack(pos, r, c, row, col, bishopDirs)) return true
                    }
                    ROOK -> {
                        if (checkSlidingAttack(pos, r, c, row, col, rookDirs)) return true
                    }
                    QUEEN -> {
                        if (checkSlidingAttack(pos, r, c, row, col, queenDirs)) return true
                    }
                    KING -> {
                        for ((dr, dc) in kingOffsets) {
                            if (r + dr == row && c + dc == col) return true
                        }
                    }
                }
            }
        }
        return false
    }

    private fun checkSlidingAttack(pos: Position, fromR: Int, fromC: Int, toR: Int, toC: Int, dirs: List<Pair<Int, Int>>): Boolean {
        for ((dr, dc) in dirs) {
            var nr = fromR + dr
            var nc = fromC + dc
            while (nr in 0..7 && nc in 0..7) {
                if (nr == toR && nc == toC) return true
                if (pos.board[nr][nc] != EMPTY) break
                nr += dr
                nc += dc
            }
        }
        return false
    }

    fun isInCheck(pos: Position, isWhite: Boolean): Boolean {
        // Find king
        for (r in 0..7) {
            for (c in 0..7) {
                val p = pos.board[r][c]
                if (p == if (isWhite) KING else -KING) {
                    return isSquareAttacked(pos, r, c, !isWhite)
                }
            }
        }
        return false
    }

    fun makeMove(pos: Position, move: Move): Position {
        val newPos = pos.copy()
        val piece = newPos.board[move.fromRow][move.fromCol]
        val captured = newPos.board[move.toRow][move.toCol]

        // Move piece
        newPos.board[move.toRow][move.toCol] = piece
        newPos.board[move.fromRow][move.fromCol] = EMPTY

        val isWhite = piece > 0
        val absPiece = abs(piece)

        // Handle pawn specifics
        if (absPiece == PAWN) {
            // En passant capture
            if (move.toCol != move.fromCol && captured == EMPTY) {
                // This was an en passant capture - remove the captured pawn
                val capturedPawnRow = move.toRow + if (isWhite) 1 else -1
                newPos.board[capturedPawnRow][move.toCol] = EMPTY
            }

            // Promotion
            if (move.promotion != 0) {
                val promoPiece = if (isWhite) move.promotion else -move.promotion
                newPos.board[move.toRow][move.toCol] = promoPiece
            }

            // Set en passant square
            if (abs(move.toRow - move.fromRow) == 2) {
                val epRow = (move.fromRow + move.toRow) / 2
                newPos.enPassant = "${('a' + move.toCol)}${('8' - epRow)}"
            } else {
                newPos.enPassant = "-"
            }
        } else {
            newPos.enPassant = "-"
        }

        // Handle castling move
        if (absPiece == KING && abs(move.toCol - move.fromCol) == 2) {
            val backRank = move.fromRow
            if (move.toCol == 6) {  // Kingside
                newPos.board[backRank][5] = newPos.board[backRank][7]
                newPos.board[backRank][7] = EMPTY
            } else if (move.toCol == 2) {  // Queenside
                newPos.board[backRank][3] = newPos.board[backRank][0]
                newPos.board[backRank][0] = EMPTY
            }
        }

        // Update castling rights
        var castling = newPos.castling
        if (absPiece == KING) {
            if (isWhite) castling = castling.replace("K", "").replace("Q", "")
            else castling = castling.replace("k", "").replace("q", "")
        }
        if (absPiece == ROOK) {
            if (move.fromRow == 7 && move.fromCol == 0) castling = castling.replace("Q", "")
            if (move.fromRow == 7 && move.fromCol == 7) castling = castling.replace("K", "")
            if (move.fromRow == 0 && move.fromCol == 0) castling = castling.replace("q", "")
            if (move.fromRow == 0 && move.fromCol == 7) castling = castling.replace("k", "")
        }
        // If a rook is captured
        if (move.toRow == 7 && move.toCol == 0) castling = castling.replace("Q", "")
        if (move.toRow == 7 && move.toCol == 7) castling = castling.replace("K", "")
        if (move.toRow == 0 && move.toCol == 0) castling = castling.replace("q", "")
        if (move.toRow == 0 && move.toCol == 7) castling = castling.replace("k", "")
        if (castling.isEmpty()) castling = "-"
        newPos.castling = castling

        // Switch turn
        newPos.whiteToMove = !pos.whiteToMove
        if (!isWhite) newPos.fullmove++

        return newPos
    }

    fun parseFEN(fen: String): Position {
        val pos = Position()
        val parts = fen.split(" ")

        // Parse board
        val rows = parts[0].split("/")
        for (r in 0..7) {
            var c = 0
            for (ch in rows[r]) {
                if (ch.isDigit()) {
                    c += ch - '0'
                } else {
                    val isWhite = ch.isUpperCase()
                    val pieceType = when (ch.lowercaseChar()) {
                        'p' -> PAWN
                        'n' -> KNIGHT
                        'b' -> BISHOP
                        'r' -> ROOK
                        'q' -> QUEEN
                        'k' -> KING
                        else -> EMPTY
                    }
                    pos.board[r][c] = if (isWhite) pieceType else -pieceType
                    c++
                }
            }
        }

        // Active color
        if (parts.size > 1) pos.whiteToMove = parts[1] == "w"

        // Castling
        if (parts.size > 2) pos.castling = parts[2]

        // En passant
        if (parts.size > 3) pos.enPassant = parts[3]

        // Half/full move clocks
        if (parts.size > 4) pos.halfmove = parts[4].toIntOrNull() ?: 0
        if (parts.size > 5) pos.fullmove = parts[5].toIntOrNull() ?: 1

        return pos
    }

    private fun abs(x: Int): Int = if (x < 0) -x else x
}
