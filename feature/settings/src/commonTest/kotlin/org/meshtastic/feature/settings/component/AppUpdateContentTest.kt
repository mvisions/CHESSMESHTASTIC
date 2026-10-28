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
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.meshtastic.core.network.AppUpdateRelease
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.app_update_error
import org.meshtastic.core.resources.app_update_installed
import org.meshtastic.core.resources.app_update_not_installed
import org.meshtastic.core.resources.app_update_permission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AppUpdateContentTest {
    private val release =
        AppUpdateRelease(
            "6.4",
            "https://github.com/mvisions/Meshtastic-Android/releases/download/media-links-test-6.4/Meshtastic-media-arm64.apk",
            100,
            "a".repeat(64),
        )

    @Test
    fun downloadAndInstallAreSeparateAndRepeatedTapsDoNotDuplicateEither() = runTest {
        val fake = FakeInstaller()
        val state = AppUpdateState(fake, fake.version) { release }
        state.check()
        val gate = CompletableDeferred<Unit>()
        fake.downloadGate = gate
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { state.download() }
        repeat(5) { state.download() }
        assertEquals(1, fake.downloads)
        assertEquals(0, fake.installs)
        gate.complete(Unit)
        job.join()
        assertTrue(state.progress is AppUpdateProgress.Downloading)
        fake.result = AppUpdateProgress.Ready
        state.refresh()
        state.install()
        repeat(5) { state.install() }
        assertEquals(1, fake.installs)
        assertEquals(Res.string.app_update_not_installed, state.status)
        state.onPause()
        state.refresh()
        assertFalse(state.installing)
        assertEquals(Res.string.app_update_not_installed, state.status)
    }

    @Test
    fun recreationRestoresDownloadAndConfirmsActualInstalledVersion() = runTest {
        val fake = FakeInstaller()
        fake.pending = release
        fake.result = AppUpdateProgress.Downloading(50, 100)
        val state = AppUpdateState(fake, fake.version) { release }
        state.check()
        assertEquals(AppUpdateProgress.Downloading(50, 100), state.progress)
        fake.version = "2.8.4-media-part6.4"
        state.refresh()
        assertEquals(Res.string.app_update_installed, state.status)
        assertEquals(fake.version, state.installedVersion)
    }

    @Test
    fun permissionReturnDoesNotAutomaticallyInstallOrClaimSuccess() = runTest {
        val fake = FakeInstaller()
        fake.pending = release
        fake.result = AppUpdateProgress.Ready
        fake.installResult = AppUpdateInstallResult.PERMISSION_REQUIRED
        val state = AppUpdateState(fake, fake.version) { release }
        state.check()
        state.install()
        assertEquals(Res.string.app_update_permission, state.status)
        assertFalse(state.installing)
        state.refresh()
        assertEquals(1, fake.installs)
        assertEquals(AppUpdateProgress.Ready, state.progress)
    }

    @Test
    fun returningBeforeDownloadingDoesNotQueryANonexistentDownload() = runTest {
        val fake = FakeInstaller()
        val state = AppUpdateState(fake, fake.version) { release }
        state.check()
        state.refresh()
        assertEquals(0, fake.progressQueries)
        assertEquals(AppUpdateProgress.Missing, state.progress)
        assertEquals(release, state.release)
        assertEquals(0, fake.downloads)
    }

    @Test
    fun failedVerificationIsVisibleAndDoesNotEnableInstaller() = runTest {
        val fake = FakeInstaller()
        fake.pending = release
        fake.failure = true
        val state = AppUpdateState(fake, fake.version) { release }
        state.check()
        assertEquals(Res.string.app_update_error, state.status)
        assertEquals(AppUpdateProgress.Missing, state.progress)
        state.install()
        assertEquals(0, fake.installs)
    }

    @Test
    fun progressPercentageAndVersionedFilenameAreVisibleWithoutAutoInstalling() = runComposeUiTest {
        val fake = FakeInstaller()
        fake.pending = release
        fake.result = AppUpdateProgress.Downloading(50, 100)
        setContent { MaterialTheme { AppUpdateContent(fake.version, fake, { release }) } }
        onNodeWithText("Downloading update: 50%", substring = true).assertIsDisplayed()
        onNodeWithText(release.fileName, substring = true).assertIsDisplayed()
        runOnIdle { assertEquals(0, fake.installs) }
    }

    @Test
    fun pollingSurvivesRecompositionWhileVerifyingADownload() = runComposeUiTest {
        mainClock.autoAdvance = false
        val fake = FakeInstaller()
        fake.pending = release
        fake.result = AppUpdateProgress.Downloading(50, 100)
        setContent { MaterialTheme { AppUpdateContent(fake.version, fake, { release }) } }
        waitForIdle()
        val gate = CompletableDeferred<Unit>()
        runOnIdle {
            fake.progressGate = gate
            fake.result = AppUpdateProgress.Ready
        }
        mainClock.advanceTimeBy(1_200)
        waitForIdle()
        onNodeWithText("Verifying the downloaded APK", substring = true).assertIsDisplayed()
        runOnIdle { gate.complete(Unit) }
        mainClock.advanceTimeByFrame()
        waitForIdle()
        onNodeWithText("Install downloaded update").assertIsDisplayed()
        runOnIdle {
            assertTrue(fake.progressQueries >= 2)
            assertEquals(0, fake.installs)
        }
    }

    @Test
    fun failedDiscoveryAfterInstalledPendingUpdateRetriesDiscoveryNotDownload() = runTest {
        val fake = FakeInstaller()
        fake.pending = release
        fake.version = "2.8.4-media-part6.4"
        var checks = 0
        val state =
            AppUpdateState(fake, fake.version) {
                checks++
                error("Offline")
            }
        state.check()
        assertEquals(Res.string.app_update_error, state.status)
        assertEquals(null, state.release)
        state.check()
        assertEquals(2, checks)
        assertEquals(0, fake.downloads)
    }

    @Test
    fun availableUpdateOnlyDownloadsOnExplicitAction() = runComposeUiTest {
        val fake = FakeInstaller()
        setContent { MaterialTheme { AppUpdateContent(fake.version, fake, { release }) } }
        onNodeWithText("Version 6.4 is available.", substring = true).assertIsDisplayed()
        runOnIdle { assertEquals(0, fake.downloads) }
        onNodeWithText("Download app update").performClick()
        runOnIdle {
            assertEquals(1, fake.downloads)
            assertEquals(0, fake.installs)
        }
    }

    @Test
    fun apkValidationRejectsCorruptForeignOldAndWrongSignerPackages() {
        val installed = AppUpdateApk("test.app", 1, "2.8.4-media-part6.3", setOf("cert"))
        val candidate = installed.copy(versionCode = 2, versionName = "2.8.4-media-part6.4 (2) fdroid")
        validateAppUpdate(release, 100, release.sha256, installed, candidate)
        for (
        invalid in
        listOf(
            candidate.copy(packageName = "another.app"),
            candidate.copy(versionCode = 1),
            candidate.copy(signerDigests = setOf("other")),
            candidate.copy(signerDigests = emptySet()),
            candidate.copy(versionName = "2.8.4-media-part6.40"),
        )
        ) {
            assertFailsWith<IllegalStateException> {
                validateAppUpdate(release, 100, release.sha256, installed, invalid)
            }
        }
        assertFailsWith<IllegalStateException> { validateAppUpdate(release, 99, release.sha256, installed, candidate) }
        assertFailsWith<IllegalStateException> { validateAppUpdate(release, 100, "b".repeat(64), installed, candidate) }
    }

    private class FakeInstaller : AppUpdateInstaller {
        var version = "2.8.4-media-part6.3"
        var pending: AppUpdateRelease? = null
        var result: AppUpdateProgress = AppUpdateProgress.Missing
        var downloads = 0
        var installs = 0
        var failure = false
        var progressQueries = 0
        var progressGate: CompletableDeferred<Unit>? = null
        var downloadGate: CompletableDeferred<Unit>? = null
        var installResult = AppUpdateInstallResult.OPENED

        override suspend fun installedVersion() = version

        override suspend fun pendingRelease() = pending

        override suspend fun download(release: AppUpdateRelease) {
            downloads++
            downloadGate?.await()
            pending = release
            result = AppUpdateProgress.Downloading(0, release.size)
        }

        override suspend fun progress(release: AppUpdateRelease): AppUpdateProgress {
            progressQueries++
            check(pending == release) { "No pending download" }
            check(!failure) { "Invalid APK" }
            progressGate?.await()
            return if (release.isInstalled(version)) AppUpdateProgress.Installed else result
        }

        override suspend fun install(release: AppUpdateRelease): AppUpdateInstallResult {
            installs++
            return installResult
        }
    }
}
