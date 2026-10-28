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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
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
    fun narrowPhoneShowsOnlyOneLargeOwnFleetAndAimControl() = runComposeUiTest {
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
                    FleetGameContent(session, onCellClick = {}, modifier = Modifier.fillMaxSize())
                }
            }
        }

        onNodeWithContentDescription("Cell A1: not fired at").assertDoesNotExist()
        val own = onNodeWithContentDescription("Cell A1: ship").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        onNodeWithContentDescription("Cell A1: ship").assertHasNoClickAction()
        assertEquals(own.width, own.height, absoluteTolerance = 1f)
        onNodeWithContentDescription("Cell A1: ship").assertWidthIsAtLeast(18.dp)
        val fire = onNodeWithText("Aim").fetchSemanticsNode().boundsInRoot
        val title = onNodeWithText("Your fleet").fetchSemanticsNode().boundsInRoot
        assertTrue(title.right <= fire.left, "The title and Aim control must fit on one row")
        assertTrue(fire.bottom <= own.top, "The controls must not overlap the grid")
        onNodeWithContentDescription("Cell J10: water").assertIsDisplayed()
    }

    @Test
    fun separateTargetingViewUsesFullWidthAndShotsRequireConfirmation() = runComposeUiTest {
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
                    FleetGameContent(session, onCellClick = { target = it }, modifier = Modifier.fillMaxSize())
                }
            }
        }

        onNodeWithContentDescription("Cell A1: hit").assertIsDisplayed()
        onNodeWithContentDescription("Cell B1: ship").assertIsDisplayed()
        onNodeWithContentDescription("Cell J10: not fired at").assertDoesNotExist()
        onNodeWithContentDescription("Cell J10: water").assertIsDisplayed()
        onNodeWithContentDescription("Cell A1: hit").assertHeightIsAtLeast(20.dp)
        onNodeWithText("Aim").performClick()
        onNodeWithContentDescription("Cell A1: ship").assertDoesNotExist()
        onNodeWithContentDescription("Nice hit!").assertDoesNotExist()
        onNodeWithContentDescription("Cell J10: not fired at").assertIsDisplayed()
        onNodeWithContentDescription("Cell A1: not fired at").assertWidthIsAtLeast(32.dp)
        onNodeWithContentDescription("Cell A1: not fired at").performClick()
        runOnIdle { assertEquals(null, target) }
        onNodeWithContentDescription("Cell A1: not fired at")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        runOnIdle { assertEquals(null, target) }
        onNodeWithText("Fire at A1").assertIsDisplayed().performClick()
        runOnIdle { assertEquals(0, target) }
    }

    @Test
    fun narrowPhoneWithLargeTextAndLongOpponentNamePreservesBoardSpace() = runComposeUiTest {
        val session =
            FleetGameSession(
                gameId = "narrow-game",
                contactKey = "!12345678",
                opponentNodeId = "!12345678",
                opponentName = "Very long opponent name with multiple words and symbols",
                board = board,
                phase = FleetGamePhase.PLAYING,
                localTurn = true,
            )
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1.3f)) {
                    FleetGameContent(session, {}, Modifier.size(320.dp, 600.dp).padding(8.dp)) {
                        TextButton(onClick = {}) { Text("Resign") }
                    }
                }
            }
        }
        onNodeWithText(session.opponentName).assertIsDisplayed()
        onNodeWithText("Aim").assertIsDisplayed()
        onNodeWithContentDescription("Cell A1: not fired at").assertDoesNotExist()
        val own =
            onNodeWithContentDescription("Cell A1: ship").assertWidthIsAtLeast(24.dp).fetchSemanticsNode().boundsInRoot
        assertEquals(own.width, own.height, absoluteTolerance = 1f)
        onNodeWithContentDescription("Nice hit!").assertIsDisplayed()
        onNodeWithContentDescription("Nice move!").assertIsDisplayed()
        onNodeWithContentDescription("Bravo!").assertIsDisplayed()
        onNodeWithContentDescription("GG").assertIsDisplayed()
        onNodeWithText("Resign").assertIsDisplayed()
        onNodeWithContentDescription("Cell J10: water").assertIsDisplayed()
        onNodeWithText("Aim").performClick()
        onNodeWithText(session.opponentName).assertDoesNotExist()
        onNodeWithContentDescription("Nice hit!").assertDoesNotExist()
        onNodeWithText("Resign").assertDoesNotExist()
        onNodeWithContentDescription("Cell A1: not fired at").assertWidthIsAtLeast(28.dp)
        onNodeWithContentDescription("Cell J10: not fired at").assertIsDisplayed().performClick()
        onNodeWithContentDescription("Cell H8: not fired at").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        onNodeWithContentDescription("Cell J10: not fired at").assertIsDisplayed()
        onNodeWithText("Full board").performClick()
        onNodeWithContentDescription("Cell A1: not fired at").assertIsDisplayed()
        onNodeWithContentDescription("Cell J10: not fired at").assertIsDisplayed()
        onNodeWithText("Close").performClick()
        onNodeWithContentDescription("Cell J10: water").assertIsDisplayed()
        onNodeWithContentDescription("Cell A1: ship").assertWidthIsAtLeast(24.dp)
        onNodeWithContentDescription("Nice hit!").assertIsDisplayed()
        onNodeWithText("Resign").assertIsDisplayed()
    }

    @Test
    fun waitingPlayerCanInspectTargetAreasWithoutSelectingOrFiring() = runComposeUiTest {
        val session =
            FleetGameSession(
                gameId = "waiting-game",
                contactKey = "!12345678",
                opponentNodeId = "!12345678",
                opponentName = "Opponent",
                board = board,
                phase = FleetGamePhase.PLAYING,
                localTurn = false,
            )
        var target: Int? = null
        setContent {
            MaterialTheme {
                FleetGameContent(session, { target = it }, Modifier.size(320.dp, 600.dp))
            }
        }
        onNodeWithText("Aim").performClick()
        onNodeWithContentDescription("Cell J10: not fired at").performClick()
        onNodeWithContentDescription("Cell H8: not fired at").assertWidthIsAtLeast(48.dp).assertHasNoClickAction()
        onNodeWithContentDescription("Cell J10: not fired at").assertHasNoClickAction()
        onNodeWithText("Fire").assertIsNotEnabled()
        runOnIdle { assertEquals(null, target) }
        onNodeWithText("Close").performClick()
        onNodeWithContentDescription("Cell J10: water").assertIsDisplayed()
    }

    @Test
    fun enlargedRegionsStayInsideTheBoardAtEveryEdge() {
        assertEquals(FleetTargetRegion(0..2, 0..2), fleetTargetRegion(0))
        assertEquals(FleetTargetRegion(0..2, 7..9), fleetTargetRegion(9))
        assertEquals(FleetTargetRegion(7..9, 0..2), fleetTargetRegion(90))
        assertEquals(FleetTargetRegion(7..9, 7..9), fleetTargetRegion(99))
        for (cell in 0 until 100) {
            val region = fleetTargetRegion(cell)
            assertTrue(cell / 10 in region.rows)
            assertTrue(cell % 10 in region.columns)
            assertEquals(3, region.rows.count())
            assertEquals(3, region.columns.count())
        }
    }
}
