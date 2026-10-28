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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MinesGameContentTest {
    private val session =
        MinesSession(
            gameId = "test-mines",
            contactKey = "!12345678",
            opponentName = "Opponent",
            isHost = true,
            phase = MinesPhase.PLAYING,
        )

    @Test
    fun narrowPhoneAtLargeFontShowsAllSquareTouchTargetsAndConfirmation() = runComposeUiTest {
        setContent {
            MinesTestViewport {
                MinesGameContent(session, {}, {}, {}, Modifier.fillMaxSize())
            }
        }

        val viewport = onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        repeat(MINES_CELLS) { cell ->
            val node = onNodeWithContentDescription("Cell ${minesCoordinate(cell)}: hidden")
            node.assertIsDisplayed().assertWidthIsAtLeast(44.dp).assertHeightIsAtLeast(44.dp)
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertEquals(bounds.width, bounds.height, absoluteTolerance = 1f)
            assertTrue(bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
            assertTrue(bounds.left >= viewport.left && bounds.right <= viewport.right)
        }
        onNodeWithText("Reveal").assertIsDisplayed().assertIsNotEnabled()
        onNodeWithText("Resign").assertIsDisplayed()
    }

    @Test
    fun narrowPhoneContentBelowToolbarFits456DpAtLargeFontWithoutScrolling() = runComposeUiTest {
        setContent {
            MinesTestViewport(height = 456.dp, toolbarPadding = 0.dp) {
                MinesGameContent(session, {}, {}, {}, Modifier.fillMaxSize())
            }
        }

        val viewport = onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        repeat(MINES_CELLS) { cell ->
            val node = onNodeWithContentDescription("Cell ${minesCoordinate(cell)}: hidden")
            node.assertIsDisplayed().assertWidthIsAtLeast(44.dp).assertHeightIsAtLeast(44.dp)
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertEquals(bounds.width, bounds.height, absoluteTolerance = 1f)
            assertTrue(bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
            assertTrue(bounds.left >= viewport.left && bounds.right <= viewport.right)
        }
        listOf("Reveal", "Resign").forEach { label ->
            val control = onNodeWithText(label).assertIsDisplayed()
            val bounds = control.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
        }
        onNodeWithText("Reveal").assertIsNotEnabled()
        onNodeWithContentDescription("You: 0 points, 3 lives").assertIsDisplayed()
        onNodeWithContentDescription("Opponent: 0 points, 3 lives").assertIsDisplayed()
    }

    @Test
    fun largerViewportKeepsTheWholeBoardCenteredAndBounded() = runComposeUiTest {
        setContent {
            MinesTestViewport(width = 800.dp, height = 900.dp) {
                MinesGameContent(session, {}, {}, {}, Modifier.fillMaxSize())
            }
        }

        repeat(MINES_CELLS) { cell ->
            onNodeWithContentDescription("Cell ${minesCoordinate(cell)}: hidden").assertIsDisplayed()
        }
        val first = onNodeWithContentDescription("Cell A1: hidden").fetchSemanticsNode().boundsInRoot
        val last = onNodeWithContentDescription("Cell F6: hidden").fetchSemanticsNode().boundsInRoot
        val viewport = onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        assertEquals(first.width, first.height, absoluteTolerance = 1f)
        assertEquals(viewport.center.x, (first.left + last.right) / 2, absoluteTolerance = 1f)
        onNodeWithContentDescription("Cell A1: hidden").assertWidthIsAtLeast(44.dp)
        val density = viewport.width / 800f
        assertTrue(last.right - first.left <= 421f * density)
        onNodeWithText("Reveal").assertIsDisplayed()
    }

    @Test
    fun selectionNeverRevealsUntilExplicitConfirmation() = runComposeUiTest {
        var target: Int? = null
        setContent {
            MinesTestViewport {
                MinesGameContent(session, { target = it }, {}, {}, Modifier.fillMaxSize())
            }
        }

        onNodeWithContentDescription("Cell A1: hidden").performClick().assertIsSelected()
        runOnIdle { assertNull(target) }
        onNodeWithContentDescription("Cell F6: hidden").performClick().assertIsSelected()
        runOnIdle { assertNull(target) }
        onNodeWithText("Reveal at F6").performClick()
        runOnIdle { assertEquals(35, target) }
    }

    @Test
    fun pendingRequestBlocksSelectionAndOnlyExplicitRetrySendsAgain() = runComposeUiTest {
        var retries = 0
        var reveals = 0
        setContent {
            MinesTestViewport {
                MinesGameContent(
                    session.copy(pendingCell = 0),
                    { reveals++ },
                    { retries++ },
                    {},
                    Modifier.fillMaxSize(),
                )
            }
        }

        onNodeWithContentDescription("Cell A1: Awaiting response").assertIsDisplayed().assertHasNoClickAction()
        onNodeWithContentDescription("Cell B1: hidden").assertHasNoClickAction()
        runOnIdle {
            assertEquals(0, retries)
            assertEquals(0, reveals)
        }
        onNodeWithText("Retry").assertIsEnabled().performClick()
        runOnIdle {
            assertEquals(1, retries)
            assertEquals(0, reveals)
        }
    }

    @Test
    fun guestCanRequestMissingHostTurnWithoutSelectingACell() = runComposeUiTest {
        var retries = 0
        setContent {
            MinesTestViewport {
                MinesGameContent(session.copy(isHost = false), {}, { retries++ }, {}, Modifier.fillMaxSize())
            }
        }

        onNodeWithText("Their turn").assertIsDisplayed()
        onNodeWithContentDescription("Cell A1: hidden").assertHasNoClickAction()
        onNodeWithText("Retry").assertIsEnabled().performClick()
        runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun hostCanRetryAnInvitationWithoutAutomaticRetransmission() = runComposeUiTest {
        var retries = 0
        setContent {
            MinesTestViewport {
                MinesGameContent(
                    session.copy(phase = MinesPhase.INVITING),
                    {},
                    { retries++ },
                    {},
                    Modifier.fillMaxSize(),
                )
            }
        }

        onNodeWithText("Invitation sent").assertIsDisplayed()
        runOnIdle { assertEquals(0, retries) }
        onNodeWithText("Retry").performClick()
        runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun finishedHostCanRetryAnUnsentFinalReply() = runComposeUiTest {
        var retries = 0
        setContent {
            MinesTestViewport {
                MinesGameContent(
                    session.copy(phase = MinesPhase.FINISHED, replyPending = true),
                    {},
                    { retries++ },
                    {},
                    Modifier.fillMaxSize(),
                )
            }
        }

        onNodeWithContentDescription("Cell B1: hidden").assertHasNoClickAction()
        onNodeWithText("Retry").assertIsEnabled().performClick()
        runOnIdle { assertEquals(1, retries) }
        onNodeWithText("Resign").assertDoesNotExist()
    }

    @Test
    fun revealedZeroMineAndNumberHaveDistinctAccessibleStatesWithoutFloodFill() = runComposeUiTest {
        val match = MinesMatch().reveal(0, 0).reveal(1, -1).reveal(2, 2)
        setContent {
            MinesTestViewport {
                MinesGameContent(session.copy(match = match), {}, {}, {}, Modifier.fillMaxSize())
            }
        }

        onNodeWithContentDescription("Cell A1: revealed, no adjacent mines")
            .assertIsDisplayed()
            .assertHasNoClickAction()
        onNodeWithContentDescription("Cell B1: mine").assertIsDisplayed().assertHasNoClickAction()
        onNodeWithContentDescription("Cell C1: revealed, 2 adjacent mines").assertIsDisplayed().assertHasNoClickAction()
        repeat(MINES_CELLS - 3) { offset ->
            onNodeWithContentDescription("Cell ${minesCoordinate(offset + 3)}: hidden").assertIsDisplayed()
        }
    }

    @Test
    fun shortViewportScrollsToConfirmationWithoutShrinkingCells() = runComposeUiTest {
        var target: Int? = null
        setContent {
            MinesTestViewport(height = 360.dp) {
                MinesGameContent(session, { target = it }, {}, {}, Modifier.fillMaxSize())
            }
        }

        onNodeWithContentDescription("Cell A1: hidden").assertWidthIsAtLeast(44.dp).performClick()
        onNodeWithContentDescription("Cell F6: hidden").performScrollTo().assertIsDisplayed()
        onNodeWithText("Reveal at A1").performScrollTo().assertIsDisplayed().performClick()
        runOnIdle { assertEquals(0, target) }
    }

    @Test
    fun finishedResignationCanBeRetriedWithoutRevealing() = runComposeUiTest {
        var retries = 0
        var reveals = 0
        setContent {
            MinesTestViewport {
                MinesGameContent(
                    session.copy(phase = MinesPhase.FINISHED, localResigned = true),
                    { reveals++ },
                    { retries++ },
                    {},
                    Modifier.fillMaxSize(),
                )
            }
        }

        onNodeWithText("You lost").assertIsDisplayed()
        onNodeWithContentDescription("Cell A1: hidden").assertHasNoClickAction()
        runOnIdle { assertEquals(0, retries) }
        onNodeWithText("Retry").assertIsEnabled().performClick()
        runOnIdle {
            assertEquals(1, retries)
            assertEquals(0, reveals)
        }
        onNodeWithText("Resign").assertDoesNotExist()
    }

    @Test
    fun resignDelegatesToConfirmationOwner() = runComposeUiTest {
        var resignations = 0
        setContent {
            MinesTestViewport {
                MinesGameContent(session, {}, {}, { resignations++ }, Modifier.fillMaxSize())
            }
        }

        onNodeWithText("Resign").performClick()
        runOnIdle { assertEquals(1, resignations) }
    }
}

@Composable
private fun MinesTestViewport(
    width: Dp = 320.dp,
    height: Dp = 600.dp,
    fontScale: Float = 1.3f,
    toolbarPadding: Dp = 64.dp,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current.density
    CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
        MaterialTheme {
            Box(Modifier.size(width, height).testTag("viewport").padding(top = toolbarPadding)) { content() }
        }
    }
}
