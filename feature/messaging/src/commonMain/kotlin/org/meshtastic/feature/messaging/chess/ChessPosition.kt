/*
 * Copyright (c) 2026 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.meshtastic.feature.messaging.chess

enum class ChessSide {
    WHITE,
    BLACK,
    ;

    val opposite: ChessSide
        get() = if (this == WHITE) BLACK else WHITE
}

enum class ChessPieceType(val symbol: Char) {
    KING('k'),
    QUEEN('q'),
    ROOK('r'),
    BISHOP('b'),
    KNIGHT('n'),
    PAWN('p'),
}

data class ChessPiece(val side: ChessSide, val type: ChessPieceType)

enum class ChessOutcome {
    ACTIVE,
    CHECK,
    CHECKMATE,
    STALEMATE,
    DRAW_THREEFOLD_REPETITION,
    DRAW_FIFTY_MOVE_RULE,
    DRAW_FIVEFOLD_REPETITION,
    DRAW_SEVENTY_FIVE_MOVE_RULE,
    DRAW_INSUFFICIENT_MATERIAL,
}

data class ChessMove(val from: Int, val to: Int, val promotion: ChessPieceType? = null) {
    init {
        require(from in 0 until BOARD_SQUARES)
        require(to in 0 until BOARD_SQUARES)
        require(promotion == null || promotion in PROMOTION_PIECES)
    }

    fun toUci(): String = chessSquareName(from) + chessSquareName(to) + (promotion?.symbol?.toString().orEmpty())

    companion object {
        @Suppress("ReturnCount")
        fun fromUci(value: String): ChessMove? {
            if (value.length !in UCI_MOVE_LENGTHS) return null
            val from = parseSquare(value.substring(0, SQUARE_NAME_LENGTH)) ?: return null
            val to = parseSquare(value.substring(SQUARE_NAME_LENGTH, UCI_BASE_LENGTH)) ?: return null
            val promotion =
                value.getOrNull(UCI_BASE_LENGTH)?.let { symbol ->
                    PROMOTION_PIECES.singleOrNull { it.symbol == symbol.lowercaseChar() } ?: return null
                }
            return ChessMove(from, to, promotion)
        }
    }
}

private val PROMOTION_PIECES =
    setOf(ChessPieceType.QUEEN, ChessPieceType.ROOK, ChessPieceType.BISHOP, ChessPieceType.KNIGHT)
private val UCI_MOVE_LENGTHS = UCI_BASE_LENGTH..UCI_PROMOTION_LENGTH

private enum class CastlingRight(val side: ChessSide, val kingSide: Boolean) {
    WHITE_KING(ChessSide.WHITE, true),
    WHITE_QUEEN(ChessSide.WHITE, false),
    BLACK_KING(ChessSide.BLACK, true),
    BLACK_QUEEN(ChessSide.BLACK, false),
}

/**
 * Immutable chess position with legal move generation and standard automatic/claimable draw rules.
 *
 * Square 0 is a1 and square 63 is h8. Promotion moves must specify a queen, rook, bishop, or knight.
 */
@Suppress("TooManyFunctions", "ReturnCount", "LoopWithTooManyJumpStatements", "LongParameterList")
class ChessPosition
private constructor(
    private val board: List<ChessPiece?>,
    val sideToMove: ChessSide,
    private val castlingRights: Set<CastlingRight>,
    private val enPassantSquare: Int?,
    val halfmoveClock: Int,
    val fullmoveNumber: Int,
    private val repetitions: Map<String, Int>,
    private val declaredOutcome: ChessOutcome?,
) {
    val outcome: ChessOutcome
        get() {
            declaredOutcome?.let {
                return it
            }
            val hasLegalMoves =
                board.indices.any { square -> board[square]?.side == sideToMove && legalMovesFrom(square).isNotEmpty() }
            if (!hasLegalMoves) return if (isInCheck(sideToMove)) ChessOutcome.CHECKMATE else ChessOutcome.STALEMATE
            if (halfmoveClock >= SEVENTY_FIVE_MOVE_HALF_MOVES) return ChessOutcome.DRAW_SEVENTY_FIVE_MOVE_RULE
            if ((repetitions[positionKey()] ?: 0) >= FIVEFOLD_REPETITION_COUNT) {
                return ChessOutcome.DRAW_FIVEFOLD_REPETITION
            }
            if (hasInsufficientMaterial()) return ChessOutcome.DRAW_INSUFFICIENT_MATERIAL
            return if (isInCheck(sideToMove)) ChessOutcome.CHECK else ChessOutcome.ACTIVE
        }

    fun pieceAt(square: Int): ChessPiece? {
        require(square in board.indices)
        return board[square]
    }

    fun legalMovesFrom(square: Int): List<ChessMove> {
        require(square in board.indices)
        val piece = board[square] ?: return emptyList()
        if (piece.side != sideToMove || declaredOutcome != null) return emptyList()
        return pseudoMovesFrom(square, piece).filter { move ->
            val next = applyUnchecked(move, trackRepetition = false)
            !next.isInCheck(piece.side)
        }
    }

    fun legalMoves(): List<ChessMove> = if (outcome.isFinished()) {
        emptyList()
    } else {
        board.indices.flatMap { square ->
            if (board[square]?.side == sideToMove) legalMovesFrom(square) else emptyList()
        }
    }

    fun isInCheck(side: ChessSide): Boolean {
        val kingSquare = board.indexOfFirst { it == ChessPiece(side, ChessPieceType.KING) }
        return kingSquare >= 0 && isSquareAttacked(kingSquare, side.opposite)
    }

    fun play(move: ChessMove): ChessPosition? {
        if (outcome.isFinished()) return null
        if (move !in legalMovesFrom(move.from)) return null
        return applyUnchecked(move, trackRepetition = true)
    }

    fun canClaimThreefoldRepetition(): Boolean = (repetitions[positionKey()] ?: 0) >= THREEFOLD_REPETITION_COUNT

    fun canClaimFiftyMoveDraw(): Boolean = halfmoveClock >= FIFTY_MOVE_HALF_MOVES

    fun toFen(): String {
        val placement =
            (BOARD_WIDTH - 1 downTo 0).joinToString("/") { rank ->
                var emptySquares = 0
                buildString {
                    for (file in 0 until BOARD_WIDTH) {
                        val piece = board[squareOf(file, rank)]
                        if (piece == null) {
                            emptySquares++
                        } else {
                            if (emptySquares > 0) {
                                append(emptySquares)
                                emptySquares = 0
                            }
                            val symbol = piece.type.symbol
                            append(if (piece.side == ChessSide.WHITE) symbol.uppercaseChar() else symbol)
                        }
                    }
                    if (emptySquares > 0) append(emptySquares)
                }
            }
        val castling = buildString {
            if (CastlingRight.WHITE_KING in castlingRights) append('K')
            if (CastlingRight.WHITE_QUEEN in castlingRights) append('Q')
            if (CastlingRight.BLACK_KING in castlingRights) append('k')
            if (CastlingRight.BLACK_QUEEN in castlingRights) append('q')
        }
            .ifEmpty { "-" }
        val enPassant = enPassantSquare?.let(::chessSquareName) ?: "-"
        val activeColor = if (sideToMove == ChessSide.WHITE) "w" else "b"
        return "$placement $activeColor $castling $enPassant $halfmoveClock $fullmoveNumber"
    }

    fun claimDraw(outcome: ChessOutcome): ChessPosition? {
        if (outcome == ChessOutcome.DRAW_THREEFOLD_REPETITION && canClaimThreefoldRepetition()) {
            return copy(declaredOutcome = outcome)
        }
        if (outcome == ChessOutcome.DRAW_FIFTY_MOVE_RULE && canClaimFiftyMoveDraw()) {
            return copy(declaredOutcome = outcome)
        }
        return null
    }

    private fun pseudoMovesFrom(square: Int, piece: ChessPiece): List<ChessMove> = when (piece.type) {
        ChessPieceType.PAWN -> pawnMoves(square, piece.side)
        ChessPieceType.KNIGHT -> jumpingMoves(square, piece.side, KNIGHT_STEPS)
        ChessPieceType.BISHOP -> slidingMoves(square, piece.side, DIAGONAL_DIRECTIONS)
        ChessPieceType.ROOK -> slidingMoves(square, piece.side, STRAIGHT_DIRECTIONS)
        ChessPieceType.QUEEN -> slidingMoves(square, piece.side, ALL_DIRECTIONS)
        ChessPieceType.KING -> kingMoves(square, piece.side)
    }

    private fun pawnMoves(square: Int, side: ChessSide): List<ChessMove> {
        val moves = mutableListOf<ChessMove>()
        val file = square % BOARD_WIDTH
        val rank = square / BOARD_WIDTH
        val direction = if (side == ChessSide.WHITE) 1 else -1
        val startRank = if (side == ChessSide.WHITE) WHITE_PAWN_START_RANK else BLACK_PAWN_START_RANK
        val nextRank = rank + direction
        if (nextRank in 0 until BOARD_WIDTH) {
            val forward = squareOf(file, nextRank)
            if (board[forward] == null) {
                addPawnMove(moves, square, forward, nextRank)
                val twoRanks = rank + 2 * direction
                if (rank == startRank && board[squareOf(file, twoRanks)] == null) {
                    moves += ChessMove(square, squareOf(file, twoRanks))
                }
            }
            for (fileDelta in CAPTURE_FILE_DELTAS) {
                val targetFile = file + fileDelta
                if (targetFile !in 0 until BOARD_WIDTH) continue
                val target = squareOf(targetFile, nextRank)
                val occupant = board[target]
                if (
                    (occupant != null && occupant.side != side && occupant.type != ChessPieceType.KING) ||
                    target == enPassantSquare
                ) {
                    addPawnMove(moves, square, target, nextRank)
                }
            }
        }
        return moves
    }

    private fun addPawnMove(moves: MutableList<ChessMove>, from: Int, to: Int, targetRank: Int) {
        if (targetRank == WHITE_PAWN_PROMOTION_RANK || targetRank == BLACK_PAWN_PROMOTION_RANK) {
            PROMOTION_PIECES.forEach { moves += ChessMove(from, to, it) }
        } else {
            moves += ChessMove(from, to)
        }
    }

    private fun jumpingMoves(square: Int, side: ChessSide, steps: List<Pair<Int, Int>>): List<ChessMove> {
        val file = square % BOARD_WIDTH
        val rank = square / BOARD_WIDTH
        return steps.mapNotNull { (fileDelta, rankDelta) ->
            val targetFile = file + fileDelta
            val targetRank = rank + rankDelta
            if (targetFile !in 0 until BOARD_WIDTH || targetRank !in 0 until BOARD_WIDTH) return@mapNotNull null
            val target = squareOf(targetFile, targetRank)
            val occupant = board[target]
            if (occupant?.side == side || occupant?.type == ChessPieceType.KING) null else ChessMove(square, target)
        }
    }

    private fun slidingMoves(square: Int, side: ChessSide, directions: List<Pair<Int, Int>>): List<ChessMove> {
        val moves = mutableListOf<ChessMove>()
        val file = square % BOARD_WIDTH
        val rank = square / BOARD_WIDTH
        for ((fileDelta, rankDelta) in directions) {
            var targetFile = file + fileDelta
            var targetRank = rank + rankDelta
            while (targetFile in 0 until BOARD_WIDTH && targetRank in 0 until BOARD_WIDTH) {
                val target = squareOf(targetFile, targetRank)
                val occupant = board[target]
                if (occupant == null) {
                    moves += ChessMove(square, target)
                } else {
                    if (occupant.side != side && occupant.type != ChessPieceType.KING) {
                        moves += ChessMove(square, target)
                    }
                    break
                }
                targetFile += fileDelta
                targetRank += rankDelta
            }
        }
        return moves
    }

    private fun kingMoves(square: Int, side: ChessSide): List<ChessMove> {
        val moves = jumpingMoves(square, side, KING_STEPS).toMutableList()
        val homeRank = if (side == ChessSide.WHITE) WHITE_HOME_RANK else BLACK_HOME_RANK
        if (square != squareOf(KING_FILE, homeRank) || isSquareAttacked(square, side.opposite)) return moves
        for (kingSide in listOf(true, false)) {
            val right = castlingRight(side, kingSide)
            if (right !in castlingRights) continue
            val rookFile = if (kingSide) ROOK_KING_FILE else ROOK_QUEEN_FILE
            val rook = board[squareOf(rookFile, homeRank)]
            if (rook != ChessPiece(side, ChessPieceType.ROOK)) continue
            val filePath = if (kingSide) KING_SIDE_EMPTY_FILES else QUEEN_SIDE_EMPTY_FILES
            if (filePath.any { board[squareOf(it, homeRank)] != null }) continue
            val transitFiles = if (kingSide) KING_SIDE_TRANSIT_FILES else QUEEN_SIDE_TRANSIT_FILES
            if (transitFiles.any { isSquareAttacked(squareOf(it, homeRank), side.opposite) }) continue
            val destinationFile = if (kingSide) KING_CASTLE_DESTINATION_FILE else QUEEN_CASTLE_DESTINATION_FILE
            moves += ChessMove(square, squareOf(destinationFile, homeRank))
        }
        return moves
    }

    private fun isSquareAttacked(target: Int, bySide: ChessSide): Boolean {
        val targetFile = target % BOARD_WIDTH
        val targetRank = target / BOARD_WIDTH
        for (source in board.indices) {
            val attacker = board[source] ?: continue
            if (attacker.side != bySide) continue
            val sourceFile = source % BOARD_WIDTH
            val sourceRank = source / BOARD_WIDTH
            val fileDelta = targetFile - sourceFile
            val rankDelta = targetRank - sourceRank
            val attacked =
                when (attacker.type) {
                    ChessPieceType.PAWN ->
                        rankDelta == (if (bySide == ChessSide.WHITE) 1 else -1) && fileDelta in CAPTURE_FILE_DELTAS

                    ChessPieceType.KNIGHT -> (fileDelta to rankDelta) in KNIGHT_STEPS

                    ChessPieceType.KING -> (fileDelta to rankDelta) in KING_STEPS

                    ChessPieceType.BISHOP ->
                        isSlidingAttack(sourceFile, sourceRank, targetFile, targetRank, DIAGONAL_DIRECTIONS)

                    ChessPieceType.ROOK ->
                        isSlidingAttack(sourceFile, sourceRank, targetFile, targetRank, STRAIGHT_DIRECTIONS)

                    ChessPieceType.QUEEN ->
                        isSlidingAttack(sourceFile, sourceRank, targetFile, targetRank, ALL_DIRECTIONS)
                }
            if (attacked) return true
        }
        return false
    }

    private fun isSlidingAttack(
        sourceFile: Int,
        sourceRank: Int,
        targetFile: Int,
        targetRank: Int,
        directions: List<Pair<Int, Int>>,
    ): Boolean {
        val fileDelta = targetFile - sourceFile
        val rankDelta = targetRank - sourceRank
        val direction = directions.firstOrNull { (df, dr) -> sameRay(fileDelta, rankDelta, df, dr) } ?: return false
        var file = sourceFile + direction.first
        var rank = sourceRank + direction.second
        while (file != targetFile || rank != targetRank) {
            if (board[squareOf(file, rank)] != null) return false
            file += direction.first
            rank += direction.second
        }
        return true
    }

    private fun sameRay(fileDelta: Int, rankDelta: Int, directionFile: Int, directionRank: Int): Boolean = when {
        directionFile == 0 -> fileDelta == 0 && rankDelta * directionRank > 0

        directionRank == 0 -> rankDelta == 0 && fileDelta * directionFile > 0

        else ->
            fileDelta * directionFile > 0 &&
                rankDelta * directionRank > 0 &&
                kotlin.math.abs(fileDelta) == kotlin.math.abs(rankDelta)
    }

    private fun applyUnchecked(move: ChessMove, trackRepetition: Boolean): ChessPosition {
        val movingPiece = checkNotNull(board[move.from])
        val movedBoard = board.toMutableList()
        val capturedPiece =
            if (movingPiece.type == ChessPieceType.PAWN && move.to == enPassantSquare && board[move.to] == null) {
                movedBoard[move.to + if (movingPiece.side == ChessSide.WHITE) -BOARD_WIDTH else BOARD_WIDTH].also {
                    movedBoard[move.to + if (movingPiece.side == ChessSide.WHITE) -BOARD_WIDTH else BOARD_WIDTH] = null
                }
            } else {
                board[move.to]
            }
        movedBoard[move.from] = null
        movedBoard[move.to] = ChessPiece(movingPiece.side, move.promotion ?: movingPiece.type)
        if (movingPiece.type == ChessPieceType.KING && kotlin.math.abs(move.to - move.from) == CASTLE_MOVE_DISTANCE) {
            val homeRank = if (movingPiece.side == ChessSide.WHITE) WHITE_HOME_RANK else BLACK_HOME_RANK
            val kingSide = move.to % BOARD_WIDTH > move.from % BOARD_WIDTH
            val rookFrom = squareOf(if (kingSide) ROOK_KING_FILE else ROOK_QUEEN_FILE, homeRank)
            val rookTo = squareOf(if (kingSide) ROOK_KING_DESTINATION_FILE else ROOK_QUEEN_DESTINATION_FILE, homeRank)
            movedBoard[rookTo] = movedBoard[rookFrom]
            movedBoard[rookFrom] = null
        }

        val nextRights = castlingRightsAfterMove(movingPiece, move, capturedPiece)
        val nextEnPassant =
            if (
                movingPiece.type == ChessPieceType.PAWN &&
                kotlin.math.abs(move.to - move.from) == PAWN_DOUBLE_MOVE_DISTANCE
            ) {
                (move.from + move.to) / 2
            } else {
                null
            }
        val nextSide = sideToMove.opposite
        val nextPosition =
            ChessPosition(
                board = movedBoard,
                sideToMove = nextSide,
                castlingRights = nextRights,
                enPassantSquare = nextEnPassant,
                halfmoveClock =
                if (movingPiece.type == ChessPieceType.PAWN || capturedPiece != null) 0 else halfmoveClock + 1,
                fullmoveNumber = fullmoveNumber + if (sideToMove == ChessSide.BLACK) 1 else 0,
                repetitions = repetitions,
                declaredOutcome = null,
            )
        if (!trackRepetition) return nextPosition
        val nextKey = nextPosition.positionKey()
        return nextPosition.copy(repetitions = repetitions + (nextKey to (repetitions[nextKey] ?: 0) + 1))
    }

    private fun castlingRightsAfterMove(
        piece: ChessPiece,
        move: ChessMove,
        capturedPiece: ChessPiece?,
    ): Set<CastlingRight> {
        val rights = castlingRights.toMutableSet()
        if (piece.type == ChessPieceType.KING) {
            rights.removeAll { it.side == piece.side }
        }
        if (piece.type == ChessPieceType.ROOK) {
            rights.remove(castlingRightAt(piece.side, move.from))
        }
        if (capturedPiece?.type == ChessPieceType.ROOK) {
            rights.remove(castlingRightAt(capturedPiece.side, move.to))
        }
        return rights
    }

    private fun castlingRightAt(side: ChessSide, square: Int): CastlingRight? {
        val homeRank = if (side == ChessSide.WHITE) WHITE_HOME_RANK else BLACK_HOME_RANK
        if (square / BOARD_WIDTH != homeRank) return null
        return when (square % BOARD_WIDTH) {
            ROOK_KING_FILE -> castlingRight(side, kingSide = true)
            ROOK_QUEEN_FILE -> castlingRight(side, kingSide = false)
            else -> null
        }
    }

    private fun hasInsufficientMaterial(): Boolean {
        val nonKings = board.filterNotNull().filter { it.type != ChessPieceType.KING }
        if (
            nonKings.any {
                it.type == ChessPieceType.PAWN || it.type == ChessPieceType.ROOK || it.type == ChessPieceType.QUEEN
            }
        ) {
            return false
        }
        if (nonKings.size <= 1) return true
        if (nonKings.any { it.type != ChessPieceType.BISHOP }) return false
        val bishopSquareColors =
            board.indices
                .filter { board[it]?.type == ChessPieceType.BISHOP }
                .map { (it % BOARD_WIDTH + it / BOARD_WIDTH) % SQUARE_COLOR_COUNT }
        return bishopSquareColors.distinct().size == ONE_SQUARE_COLOR
    }

    private fun positionKey(): String = buildString {
        board.forEachIndexed { index, piece ->
            if (piece != null) {
                append(index)
                append(if (piece.side == ChessSide.WHITE) piece.type.symbol.uppercaseChar() else piece.type.symbol)
            }
        }
        append('|')
        append(sideToMove.name)
        append('|')
        castlingRights.sortedBy { it.ordinal }.forEach { append(it.name) }
        append('|')
        append(enPassantSquare ?: -1)
    }

    private fun copy(
        repetitions: Map<String, Int> = this.repetitions,
        declaredOutcome: ChessOutcome? = this.declaredOutcome,
    ) = ChessPosition(
        board,
        sideToMove,
        castlingRights,
        enPassantSquare,
        halfmoveClock,
        fullmoveNumber,
        repetitions,
        declaredOutcome,
    )

    companion object {
        fun initial(): ChessPosition {
            val board = MutableList<ChessPiece?>(BOARD_SQUARES) { null }
            val backRank =
                listOf(
                    ChessPieceType.ROOK,
                    ChessPieceType.KNIGHT,
                    ChessPieceType.BISHOP,
                    ChessPieceType.QUEEN,
                    ChessPieceType.KING,
                    ChessPieceType.BISHOP,
                    ChessPieceType.KNIGHT,
                    ChessPieceType.ROOK,
                )
            for (file in 0 until BOARD_WIDTH) {
                board[squareOf(file, WHITE_HOME_RANK)] = ChessPiece(ChessSide.WHITE, backRank[file])
                board[squareOf(file, WHITE_PAWN_START_RANK)] = ChessPiece(ChessSide.WHITE, ChessPieceType.PAWN)
                board[squareOf(file, BLACK_PAWN_START_RANK)] = ChessPiece(ChessSide.BLACK, ChessPieceType.PAWN)
                board[squareOf(file, BLACK_HOME_RANK)] = ChessPiece(ChessSide.BLACK, backRank[file])
            }
            val initial =
                ChessPosition(
                    board,
                    ChessSide.WHITE,
                    CastlingRight.entries.toSet(),
                    null,
                    0,
                    1,
                    emptyMap(),
                    null,
                )
            return initial.copy(repetitions = mapOf(initial.positionKey() to 1))
        }

        @Suppress("LongMethod")
        fun fromFen(fen: String): ChessPosition? {
            val fields = fen.split(' ')
            if (fields.size != FEN_FIELD_COUNT) return null
            val board = MutableList<ChessPiece?>(BOARD_SQUARES) { null }
            val ranks = fields[FEN_BOARD_FIELD].split('/')
            if (ranks.size != BOARD_WIDTH) return null
            ranks.forEachIndexed { fenRank, row ->
                var file = 0
                for (symbol in row) {
                    if (symbol in FIRST_FEN_EMPTY_COUNT..LAST_FEN_EMPTY_COUNT) {
                        file += symbol.digitToInt()
                    } else {
                        val side = if (symbol.isUpperCase()) ChessSide.WHITE else ChessSide.BLACK
                        val type =
                            ChessPieceType.entries.singleOrNull { it.symbol == symbol.lowercaseChar() } ?: return null
                        if (file >= BOARD_WIDTH) return null
                        board[squareOf(file, BOARD_WIDTH - 1 - fenRank)] = ChessPiece(side, type)
                        file++
                    }
                }
                if (file != BOARD_WIDTH) return null
            }
            if (board.count { it == ChessPiece(ChessSide.WHITE, ChessPieceType.KING) } != 1) return null
            if (board.count { it == ChessPiece(ChessSide.BLACK, ChessPieceType.KING) } != 1) return null
            val sideToMove =
                when (fields[FEN_SIDE_TO_MOVE_FIELD]) {
                    "w" -> ChessSide.WHITE
                    "b" -> ChessSide.BLACK
                    else -> return null
                }
            val rights = mutableSetOf<CastlingRight>()
            if (fields[FEN_CASTLING_FIELD] != "-") {
                for (symbol in fields[FEN_CASTLING_FIELD]) {
                    val right =
                        when (symbol) {
                            'K' -> CastlingRight.WHITE_KING
                            'Q' -> CastlingRight.WHITE_QUEEN
                            'k' -> CastlingRight.BLACK_KING
                            'q' -> CastlingRight.BLACK_QUEEN
                            else -> return null
                        }
                    if (!rights.add(right)) return null
                }
            }
            if (
                !rights.all { right ->
                    val rank = if (right.side == ChessSide.WHITE) WHITE_HOME_RANK else BLACK_HOME_RANK
                    board[squareOf(KING_FILE, rank)] == ChessPiece(right.side, ChessPieceType.KING) &&
                        board[squareOf(if (right.kingSide) ROOK_KING_FILE else ROOK_QUEEN_FILE, rank)] ==
                        ChessPiece(right.side, ChessPieceType.ROOK)
                }
            ) {
                return null
            }
            val enPassant =
                if (fields[FEN_EN_PASSANT_FIELD] == "-") {
                    null
                } else {
                    parseSquare(fields[FEN_EN_PASSANT_FIELD])?.takeIf { square ->
                        val rank = square / BOARD_WIDTH
                        rank ==
                            if (sideToMove == ChessSide.WHITE) {
                                EN_PASSANT_TARGET_RANK_FOR_WHITE
                            } else {
                                EN_PASSANT_TARGET_RANK_FOR_BLACK
                            }
                    } ?: return null
                }
            val halfmoveClock = fields[FEN_HALFMOVE_FIELD].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val fullmoveNumber = fields[FEN_FULLMOVE_FIELD].toIntOrNull()?.takeIf { it > 0 } ?: return null
            val position =
                ChessPosition(
                    board,
                    sideToMove,
                    rights,
                    enPassant,
                    halfmoveClock,
                    fullmoveNumber,
                    emptyMap(),
                    null,
                )
            return position.copy(repetitions = mapOf(position.positionKey() to 1))
        }
    }
}

private fun ChessOutcome.isFinished(): Boolean = this != ChessOutcome.ACTIVE && this != ChessOutcome.CHECK

private fun castlingRight(side: ChessSide, kingSide: Boolean): CastlingRight = when (side) {
    ChessSide.WHITE -> if (kingSide) CastlingRight.WHITE_KING else CastlingRight.WHITE_QUEEN
    ChessSide.BLACK -> if (kingSide) CastlingRight.BLACK_KING else CastlingRight.BLACK_QUEEN
}

fun chessSquareName(square: Int): String {
    require(square in 0 until BOARD_SQUARES)
    return "${('a'.code + square % BOARD_WIDTH).toChar()}${('1'.code + square / BOARD_WIDTH).toChar()}"
}

@Suppress("ReturnCount")
private fun parseSquare(value: String): Int? {
    if (value.length != SQUARE_NAME_LENGTH) return null
    val file = value[0] - 'a'
    val rank = value[1] - '1'
    if (file !in 0 until BOARD_WIDTH || rank !in 0 until BOARD_WIDTH) return null
    return squareOf(file, rank)
}

private fun squareOf(file: Int, rank: Int): Int = rank * BOARD_WIDTH + file

private const val BOARD_WIDTH = 8
private const val BOARD_SQUARES = BOARD_WIDTH * BOARD_WIDTH
private const val SQUARE_NAME_LENGTH = 2
private const val UCI_BASE_LENGTH = 4
private const val UCI_PROMOTION_LENGTH = 5
private const val FIRST_FEN_EMPTY_COUNT = '1'
private const val LAST_FEN_EMPTY_COUNT = '8'
private const val ONE_SQUARE_COLOR = 1
private const val SQUARE_COLOR_COUNT = 2
private const val WHITE_HOME_RANK = 0
private const val BLACK_HOME_RANK = 7
private const val WHITE_PAWN_START_RANK = 1
private const val BLACK_PAWN_START_RANK = 6
private const val WHITE_PAWN_PROMOTION_RANK = 7
private const val BLACK_PAWN_PROMOTION_RANK = 0
private const val KING_FILE = 4
private const val ROOK_QUEEN_FILE = 0
private const val ROOK_KING_FILE = 7
private const val KING_CASTLE_DESTINATION_FILE = 6
private const val QUEEN_CASTLE_DESTINATION_FILE = 2
private const val ROOK_QUEEN_DESTINATION_FILE = 3
private const val ROOK_KING_DESTINATION_FILE = 5
private const val CASTLE_MOVE_DISTANCE = 2
private const val PAWN_DOUBLE_MOVE_DISTANCE = 16
private const val FIFTY_MOVE_HALF_MOVES = 100
private const val SEVENTY_FIVE_MOVE_HALF_MOVES = 150
private const val THREEFOLD_REPETITION_COUNT = 3
private const val FIVEFOLD_REPETITION_COUNT = 5
private const val FEN_FIELD_COUNT = 6
private const val FEN_BOARD_FIELD = 0
private const val FEN_SIDE_TO_MOVE_FIELD = 1
private const val FEN_CASTLING_FIELD = 2
private const val FEN_EN_PASSANT_FIELD = 3
private const val FEN_HALFMOVE_FIELD = 4
private const val FEN_FULLMOVE_FIELD = 5
private const val EN_PASSANT_TARGET_RANK_FOR_WHITE = 5
private const val EN_PASSANT_TARGET_RANK_FOR_BLACK = 2
private val CAPTURE_FILE_DELTAS = listOf(-1, 1)
private val KING_SIDE_EMPTY_FILES = listOf(5, 6)
private val QUEEN_SIDE_EMPTY_FILES = listOf(1, 2, 3)
private val KING_SIDE_TRANSIT_FILES = listOf(5, 6)
private val QUEEN_SIDE_TRANSIT_FILES = listOf(3, 2)
private val KNIGHT_STEPS = listOf(-2 to -1, -2 to 1, -1 to -2, -1 to 2, 1 to -2, 1 to 2, 2 to -1, 2 to 1)
private val KING_STEPS = listOf(-1 to -1, -1 to 0, -1 to 1, 0 to -1, 0 to 1, 1 to -1, 1 to 0, 1 to 1)
private val STRAIGHT_DIRECTIONS = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
private val DIAGONAL_DIRECTIONS = listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)
private val ALL_DIRECTIONS = STRAIGHT_DIRECTIONS + DIAGONAL_DIRECTIONS
