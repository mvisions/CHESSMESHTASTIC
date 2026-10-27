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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FleetBoardGridTest {
    private val board = FleetBoard(listOf(FleetShip(2, setOf(0, 1))))

    @Test
    fun ownFleetShowsShipsAndWaterWithVisibleCellBorders() = runComposeUiTest {
        var outline = Color.Unspecified
        setContent {
            MaterialTheme {
                outline = MaterialTheme.colorScheme.outline
                FleetBoardGrid(board, emptySet(), emptyMap(), null, ownBoard = true, enabled = false, onCellClick = {})
            }
        }

        onNodeWithContentDescription("Cell A1: ship").assertIsDisplayed()
        onNodeWithContentDescription("Cell B1: ship").assertIsDisplayed()
        val water = onNodeWithContentDescription("Cell C1: water").assertIsDisplayed()
        val pixels = water.captureToImage().toPixelMap()
        assertTrue((0 until pixels.width).any { pixels[it, pixels.height / 2] == outline })
    }

    @Test
    fun opponentsShipsStayHiddenAndCellsCanBeTargeted() = runComposeUiTest {
        var target: Int? = null
        var hullColor = Color.Unspecified
        setContent {
            MaterialTheme {
                hullColor = MaterialTheme.colorScheme.primary
                FleetBoardGrid(
                    board,
                    emptySet(),
                    emptyMap(),
                    null,
                    ownBoard = false,
                    enabled = true,
                    onCellClick = { target = it },
                )
            }
        }

        val unknownCell = onNodeWithContentDescription("Cell A1: not fired at").assertIsDisplayed()
        val pixels = unknownCell.captureToImage().toPixelMap()
        assertTrue((0 until pixels.width).none { pixels[it, pixels.height / 2] == hullColor })
        unknownCell.performClick()
        runOnIdle { assertEquals(0, target) }
    }

    @Test
    fun shipsHavePointedBowsInBothOrientations() = runComposeUiTest {
        val fleet = FleetBoard(listOf(FleetShip(2, setOf(0, 1)), FleetShip(2, setOf(3, 13))))
        var hullColor = Color.Unspecified
        setContent {
            MaterialTheme {
                hullColor = MaterialTheme.colorScheme.primary
                FleetBoardGrid(
                    fleet,
                    emptySet(),
                    emptyMap(),
                    null,
                    ownBoard = true,
                    enabled = false,
                    onCellClick = {},
                    cellSize = 24.dp,
                    cellWidth = 48.dp,
                )
            }
        }

        val horizontal = onNodeWithContentDescription("Cell B1: ship").captureToImage().toPixelMap()
        assertEquals(hullColor, horizontal[horizontal.width * 4 / 5, horizontal.height / 2])
        assertTrue(horizontal[horizontal.width * 4 / 5, horizontal.height / 4] != hullColor)
        val vertical = onNodeWithContentDescription("Cell D2: ship").captureToImage().toPixelMap()
        assertEquals(hullColor, vertical[vertical.width / 2, vertical.height * 4 / 5])
        assertTrue(vertical[vertical.width / 4, vertical.height * 4 / 5] != hullColor)
    }

    @Test
    fun firedCellStaysMarkedWhileWaitingForTheOpponent() = runComposeUiTest {
        setContent {
            MaterialTheme {
                FleetBoardGrid(
                    board,
                    emptySet(),
                    emptyMap(),
                    outgoingShot = 0,
                    ownBoard = false,
                    enabled = true,
                    onCellClick = {},
                )
            }
        }

        onNodeWithContentDescription("Cell A1: shot pending").assertIsDisplayed().assertHasNoClickAction()
        onNodeWithText("…").assertIsDisplayed()
    }

    @Test
    fun narrowPhoneStacksBoardsEvenWhenControlsLeaveAWideContentArea() = runComposeUiTest {
        val session =
            FleetGameSession(
                gameId = "portrait-game",
                contactKey = "!12345678",
                opponentNodeId = "!12345678",
                opponentName = "Opponent",
                board = board,
                phase = FleetGamePhase.PLAYING,
                localTurn = true,
            )
        setContent {
            MaterialTheme {
                Box(Modifier.size(393.dp, 380.dp)) {
                    FleetBoards(session, onCellClick = {}, modifier = Modifier.fillMaxSize())
                }
            }
        }

        val opponent =
            onNodeWithContentDescription("Cell A1: not fired at").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val own = onNodeWithContentDescription("Cell A1: ship").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(own.top > opponent.bottom, "Own fleet must be below the opponent on a narrow phone")
        assertEquals(opponent.left, own.left, "Both grids must share the same horizontal position")
        onNodeWithContentDescription("Cell A1: not fired at").assertWidthIsAtLeast(28.dp).assertHeightIsAtLeast(15.dp)
        onNodeWithContentDescription("Cell A1: ship").assertWidthIsAtLeast(28.dp).assertHeightIsAtLeast(15.dp)
        val fire = onNodeWithText("Fire").fetchSemanticsNode().boundsInRoot
        val title = onNodeWithText("Opponent's fleet").fetchSemanticsNode().boundsInRoot
        assertTrue(title.right <= opponent.left, "The title belongs beside the board, not above it")
        assertTrue(fire.right <= opponent.left, "The fire control belongs beside the board, not above it")
        onNodeWithContentDescription("Cell J10: not fired at").assertIsDisplayed()
        onNodeWithContentDescription("Cell J10: water").assertIsDisplayed()
    }

    @Test
    fun bothCompleteBoardsFitOnOnePhoneScreenAndShotsRequireConfirmation() = runComposeUiTest {
        val session =
            FleetGameSession(
                gameId = "test-game",
                contactKey = "!12345678",
                opponentNodeId = "!12345678",
                opponentName = "Opponent",
                board = board,
                phase = FleetGamePhase.PLAYING,
                localTurn = true,
                incomingShots = setOf(0),
            )
        var target: Int? = null
        setContent {
            MaterialTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    FleetBoards(session, onCellClick = { target = it }, modifier = Modifier.fillMaxSize())
                }
            }
        }

        onNodeWithContentDescription("Cell A1: hit").assertIsDisplayed()
        onNodeWithContentDescription("Cell B1: ship").assertIsDisplayed()
        onNodeWithContentDescription("Cell J10: not fired at").assertIsDisplayed()
        onNodeWithContentDescription("Cell J10: water").assertIsDisplayed()
        onNodeWithContentDescription("Cell A1: not fired at").assertWidthIsAtLeast(25.dp).assertHeightIsAtLeast(20.dp)
        onNodeWithContentDescription("Cell A1: hit").assertHeightIsAtLeast(20.dp)
        onNodeWithContentDescription("Cell A1: not fired at").performClick()
        runOnIdle { assertEquals(null, target) }
        onNodeWithText("Fire at A1").assertIsDisplayed().performClick()
        runOnIdle { assertEquals(0, target) }
    }
}
