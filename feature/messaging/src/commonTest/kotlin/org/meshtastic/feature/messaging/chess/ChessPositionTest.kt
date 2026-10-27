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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChessPositionTest {
    @Test
    fun initialPositionHasTwentyLegalMovesAndSerializesToFen() {
        val position = ChessPosition.initial()

        assertEquals(20, position.legalMoves().size)
        assertEquals(STARTING_FEN, position.toFen())
        assertEquals(position.toFen(), ChessPosition.fromFen(STARTING_FEN)?.toFen())
    }

    @Test
    fun rejectsMovesThatExposeOwnKing() {
        val position = fen("k3r3/8/8/8/8/8/4R3/4K3 w - - 0 1")

        assertNull(position.play(move("e2f2")))
        assertNotNull(position.play(move("e2e3")))
    }

    @Test
    fun supportsBothCastlesAndRejectsCastlingThroughCheck() {
        val open = fen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1")
        val kingMoves = open.legalMovesFrom(square("e1")).map(ChessMove::toUci)

        assertTrue("e1g1" in kingMoves)
        assertTrue("e1c1" in kingMoves)
        val castled = assertNotNull(open.play(move("e1g1")))
        assertEquals(ChessPiece(ChessSide.WHITE, ChessPieceType.KING), castled.pieceAt(square("g1")))
        assertEquals(ChessPiece(ChessSide.WHITE, ChessPieceType.ROOK), castled.pieceAt(square("f1")))
        assertFalse(castled.toFen().split(' ')[2].contains('K'))

        val attackedTransit = fen("4kr1r/8/8/8/8/8/8/4K2R w K - 0 1")
        assertFalse(attackedTransit.legalMovesFrom(square("e1")).any { it.toUci() == "e1g1" })
    }

    @Test
    fun supportsEnPassantCapture() {
        val position = ChessPosition.initial().playMoves("e2e4", "a7a6", "e4e5", "d7d5")

        assertTrue(position.legalMovesFrom(square("e5")).any { it.toUci() == "e5d6" })
        val afterCapture = assertNotNull(position.play(move("e5d6")))
        assertNull(afterCapture.pieceAt(square("d5")))
        assertEquals(ChessPiece(ChessSide.WHITE, ChessPieceType.PAWN), afterCapture.pieceAt(square("d6")))
    }

    @Test
    fun requiresPromotionChoiceAndSupportsAllFourPieces() {
        val position = fen("7k/P7/8/8/8/8/8/7K w - - 0 1")
        val promotions = position.legalMovesFrom(square("a7")).filter { it.to == square("a8") }

        assertEquals(setOf('q', 'r', 'b', 'n'), promotions.mapNotNull { it.promotion?.symbol }.toSet())
        assertNull(position.play(ChessMove(square("a7"), square("a8"))))
        val promoted = assertNotNull(position.play(move("a7a8q")))
        assertEquals(ChessPiece(ChessSide.WHITE, ChessPieceType.QUEEN), promoted.pieceAt(square("a8")))
    }

    @Test
    fun recognizesCheckmateAndStalemate() {
        assertEquals(
            ChessOutcome.CHECKMATE,
            fen("rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3").outcome,
        )
        assertEquals(ChessOutcome.STALEMATE, fen("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1").outcome)
    }

    @Test
    fun tracksThreefoldRepetitionAndAllowsClaim() {
        val position =
            ChessPosition.initial()
                .playMoves(
                    "g1f3",
                    "g8f6",
                    "f3g1",
                    "f6g8",
                    "g1f3",
                    "g8f6",
                    "f3g1",
                    "f6g8",
                )

        assertTrue(position.canClaimThreefoldRepetition())
        assertEquals(
            ChessOutcome.DRAW_THREEFOLD_REPETITION,
            position.claimDraw(ChessOutcome.DRAW_THREEFOLD_REPETITION)?.outcome,
        )
        assertNull(position.claimDraw(ChessOutcome.DRAW_FIFTY_MOVE_RULE))
    }

    @Test
    fun recognizesFiftyAndSeventyFiveMoveRulesAndInsufficientMaterial() {
        val fiftyMove = fen("4k3/8/8/8/8/8/8/4K2R w - - 100 51")
        assertTrue(fiftyMove.canClaimFiftyMoveDraw())
        assertEquals(ChessOutcome.ACTIVE, fiftyMove.outcome)
        assertEquals(ChessOutcome.DRAW_FIFTY_MOVE_RULE, fiftyMove.claimDraw(ChessOutcome.DRAW_FIFTY_MOVE_RULE)?.outcome)

        assertEquals(ChessOutcome.DRAW_SEVENTY_FIVE_MOVE_RULE, fen("4k3/8/8/8/8/8/8/4K2R w - - 150 76").outcome)
        assertEquals(ChessOutcome.DRAW_INSUFFICIENT_MATERIAL, fen("4k3/8/8/8/8/8/8/2B1K3 w - - 0 1").outcome)
    }

    @Test
    fun parsesAndFormatsUciMoves() {
        val move = assertNotNull(ChessMove.fromUci("e7e8N"))

        assertEquals("e7e8n", move.toUci())
        assertNull(ChessMove.fromUci("e9e8"))
        assertNull(ChessMove.fromUci("e7e8k"))
    }

    private fun ChessPosition.playMoves(vararg moves: String): ChessPosition =
        moves.fold(this) { position, uci -> assertNotNull(position.play(move(uci)), "Illegal move: $uci") }

    private fun fen(value: String): ChessPosition = assertNotNull(ChessPosition.fromFen(value), "Invalid FEN: $value")

    private fun move(uci: String): ChessMove = assertNotNull(ChessMove.fromUci(uci), "Invalid UCI: $uci")

    private fun square(value: String): Int = assertNotNull(ChessMove.fromUci(value + value)?.from)

    private companion object {
        const val STARTING_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    }
}
