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
package org.meshtastic.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.junit.Test
import org.junit.runner.RunWith
import org.meshtastic.feature.messaging.games.GameSelectionScreen
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GameSelectionScreenTest {
    @Test
    fun rendersBackgroundAndBothGameChoicesOnAndroid() = runComposeUiTest {
        var chessInvitations = 0
        var fleetInvitations = 0
        setContent {
            MaterialTheme {
                GameSelectionScreen(
                    opponentName = "Opponent",
                    onSelectChess = { chessInvitations++ },
                    onSelectFleet = { fleetInvitations++ },
                    onNavigateBack = {},
                )
            }
        }

        onNodeWithText("Choose a game").assertIsDisplayed()
        onNodeWithText("Chess").assertIsDisplayed().performClick()
        runOnIdle {
            assertEquals(1, chessInvitations)
            assertEquals(0, fleetInvitations)
        }
        onNodeWithText("Battleship").assertIsDisplayed().performClick()
        runOnIdle {
            assertEquals(1, chessInvitations)
            assertEquals(1, fleetInvitations)
        }
    }
}
