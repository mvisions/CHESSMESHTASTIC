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
package org.meshtastic.feature.messaging.games

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class FleetGameSessionTest {
    @Test
    fun `replays local attack after received attack and retains the pending result`() {
        val playing = session(localTurn = false)
        val receivedAttack =
            playing.applyTurn(
                FleetCommand.Turn(GAME_ID, turn = 1, result = null, attack = 0),
                fromLocal = false,
            )
        val result = FleetShotResult(hit = true)

        assertEquals(result, receivedAttack?.pendingResult)
        val replayedLocalAttack =
            receivedAttack?.applyTurn(
                FleetCommand.Turn(GAME_ID, turn = 2, result = result, attack = 42),
                fromLocal = true,
            )

        assertEquals(2, replayedLocalAttack?.turn)
        assertEquals(false, replayedLocalAttack?.localTurn)
        assertEquals(42, replayedLocalAttack?.outgoingShot)
        assertEquals(setOf(0), replayedLocalAttack?.incomingShots)
        assertNull(replayedLocalAttack?.pendingResult)
    }

    @Test
    fun `records opponent result against local shot and processes their next attack`() {
        val awaitingReply = session(localTurn = false, turn = 2, outgoingShot = 42)
        val updated =
            awaitingReply.applyTurn(
                FleetCommand.Turn(GAME_ID, turn = 3, result = FleetShotResult(hit = false), attack = 44),
                fromLocal = false,
            )

        assertEquals(FleetShotResult(hit = false), updated?.outgoingResults?.get(42))
        assertEquals(setOf(44), updated?.incomingShots)
        assertEquals(FleetShotResult(hit = false), updated?.pendingResult)
        assertEquals(true, updated?.localTurn)
        assertNull(updated?.outgoingShot)
    }

    @Test
    fun nextShotNeedsBothThirtySecondsAndTheOpponentsAttack() {
        val clock = TestTimeSource()
        val awaitingReply = session(localTurn = false, turn = 1, outgoingShot = 42).copy(lastShotAt = clock.markNow())
        assertFalse(awaitingReply.canFireShot)
        val replied =
            requireNotNull(
                awaitingReply.applyTurn(
                    FleetCommand.Turn(GAME_ID, 2, FleetShotResult(hit = false), 44),
                    fromLocal = false,
                ),
            )
        assertFalse(replied.canFireShot)
        clock += 29.seconds + 999.milliseconds
        assertFalse(replied.canFireShot)
        clock += 1.milliseconds
        assertTrue(replied.canFireShot)
        assertFalse(awaitingReply.canFireShot)
    }

    @Test
    fun allReactionButtonsShareOneMinuteCooldownAndDuplicatesAreIgnored() {
        val clock = TestTimeSource()
        val first = FleetCommand.Reaction(GAME_ID, 1, FleetReaction.BRAVO)
        val second = FleetCommand.Reaction(GAME_ID, 2, FleetReaction.GG)
        val sent = requireNotNull(session(true).applyReaction(first, true, clock.markNow()))
        assertEquals(FleetGameReaction(FleetReaction.BRAVO, true), sent.latestReaction)
        assertFalse(sent.canSendReaction)
        assertNull(sent.applyReaction(first, true, clock.markNow()))
        clock += 59.seconds + 999.milliseconds
        assertNull(sent.applyReaction(second, true, clock.markNow()))
        assertFalse(sent.canSendReaction)
        clock += 1.milliseconds
        assertTrue(sent.canSendReaction)
        val next = requireNotNull(sent.applyReaction(second, true, clock.markNow()))
        assertEquals(FleetGameReaction(FleetReaction.GG, true), next.latestReaction)
        val received = requireNotNull(next.applyReaction(first, false, clock.markNow()))
        assertEquals(FleetGameReaction(FleetReaction.BRAVO, false), received.latestReaction)
        assertNull(received.applyReaction(second, false, clock.markNow()))
    }

    private fun session(localTurn: Boolean, turn: Int = 0, outgoingShot: Int? = null) = FleetGameSession(
        gameId = GAME_ID,
        contactKey = "node",
        opponentNodeId = "!12345678",
        opponentName = "Opponent",
        board = FleetBoard(listOf(FleetShip(length = 2, cells = setOf(0, 1)))),
        phase = FleetGamePhase.PLAYING,
        localTurn = localTurn,
        turn = turn,
        outgoingShot = outgoingShot,
    )

    private companion object {
        const val GAME_ID = "0123456789abcdef"
    }
}
