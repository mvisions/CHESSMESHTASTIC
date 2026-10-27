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
package org.meshtastic.feature.settings.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.app_update_error
import org.meshtastic.core.ui.theme.StatusColors.StatusGreen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AppUpdateContentTest {
    @Test
    fun onlyOneCheckAndBrowserLaunchAreAllowedUntilReturningToTheApp() = runTest {
        val state = AppUpdateState()
        val response = CompletableDeferred<String?>()
        var requests = 0
        val job =
            launch(UnconfinedTestDispatcher(testScheduler)) {
                state.check {
                    requests++
                    response.await()
                }
            }
        state.check {
            requests++
            null
        }
        assertEquals(1, requests)
        response.complete(URL)
        job.join()
        val opened = mutableListOf<String>()
        repeat(6) { state.open { opened += it } }
        assertEquals(listOf(URL), opened)
        state.onResume()
        assertTrue(state.opening)
        state.onPause()
        state.onResume()
        assertFalse(state.opening)
        state.open { opened += it }
        assertEquals(listOf(URL, URL), opened)
    }

    @Test
    fun browserFailureIsVisibleAndDoesNotPreventAnExplicitRetry() = runTest {
        val state = AppUpdateState()
        state.check { URL }
        state.open { error("No browser installed") }
        assertEquals(Res.string.app_update_error, state.status)
        assertFalse(state.opening)
        var calls = 0
        state.open { calls++ }
        assertEquals(1, calls)
    }

    @Test
    fun availableUpdateIsCheckedAutomaticallyAndHasAGreenBackground() = runComposeUiTest {
        var requests = 0
        var expected = Color.Unspecified
        val opened = mutableListOf<String>()
        setContent {
            MaterialTheme {
                expected =
                    MaterialTheme.colorScheme.StatusGreen.copy(alpha = 0.18f)
                        .compositeOver(MaterialTheme.colorScheme.surface)
                Surface {
                    AppUpdateContent(
                        "2.8.4-media-part6.1",
                        {
                            requests++
                            URL
                        },
                        { opened += it },
                        Modifier.testTag("update"),
                    )
                }
            }
        }
        onNodeWithText("New update available. Tap to download.", substring = true).assertIsDisplayed()
        val pixels = onNodeWithTag("update").captureToImage().toPixelMap()
        val actual = pixels[2, pixels.height / 2]
        assertEquals(expected.red, actual.red, absoluteTolerance = 2f / 255f)
        assertEquals(expected.green, actual.green, absoluteTolerance = 2f / 255f)
        assertEquals(expected.blue, actual.blue, absoluteTolerance = 2f / 255f)
        runOnIdle {
            assertEquals(1, requests)
            assertTrue(opened.isEmpty())
        }
        onNodeWithText("Check for app updates").performClick()
        runOnIdle { assertEquals(listOf(URL), opened) }
    }

    @Test
    fun currentVersionKeepsTheNormalBackgroundAndNeverOpensADownload() = runComposeUiTest {
        var expected = Color.Unspecified
        var opened = 0
        setContent {
            MaterialTheme {
                expected = MaterialTheme.colorScheme.surface
                Surface {
                    AppUpdateContent("2.8.4-media-part6.2", { null }, { opened++ }, Modifier.testTag("update"))
                }
            }
        }
        onNodeWithText("You are on the latest version", substring = true).assertIsDisplayed()
        val pixels = onNodeWithTag("update").captureToImage().toPixelMap()
        assertEquals(expected, pixels[2, pixels.height / 2])
        runOnIdle { assertEquals(0, opened) }
    }

    private companion object {
        const val URL = "https://github.com/mvisions/Meshtastic-Android/releases/download/test/update.apk"
    }
}
