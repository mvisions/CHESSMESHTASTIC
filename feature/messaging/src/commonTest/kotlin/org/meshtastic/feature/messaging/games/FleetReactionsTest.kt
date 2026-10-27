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

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class FleetReactionsTest {
    @Test
    fun reactionAppearsInGameAndLocksEveryReactionButton() = runComposeUiTest {
        var session by
            mutableStateOf(
                FleetGameSession(
                    gameId = "0123456789abcdef",
                    contactKey = "!12345678",
                    opponentNodeId = "!12345678",
                    opponentName = "Opponent",
                    board = FleetBoard(listOf(FleetShip(2, setOf(0, 1)))),
                    phase = FleetGamePhase.PLAYING,
                    localTurn = true,
                ),
            )
        var messages = 0
        setContent {
            MaterialTheme {
                FleetReactions(
                    session,
                    onReaction = {
                        messages++
                        session =
                            requireNotNull(session.applyReaction(FleetCommand.Reaction(session.gameId, 1, it), true))
                    },
                )
            }
        }
        onNodeWithText("Bravo!").performClick()
        onNodeWithText("You: Bravo!").assertIsDisplayed()
        onNodeWithText("GG").assertIsNotEnabled()
        onNodeWithText("Nice hit!").assertIsNotEnabled()
        runOnIdle {
            assertEquals(1, messages)
            session =
                requireNotNull(
                    session.applyReaction(
                        FleetCommand.Reaction(session.gameId, 1, FleetReaction.GG),
                        fromLocal = false,
                    ),
                )
        }
        onNodeWithText("Opponent: GG").assertIsDisplayed()
        runOnIdle { assertEquals(1, messages) }
    }
}
