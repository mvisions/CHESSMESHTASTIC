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
package org.meshtastic.app

import android.app.Application
import android.app.DownloadManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.meshtastic.core.di.CoroutineDispatchers
import org.meshtastic.core.network.AppUpdateRelease
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidAppUpdateInstallerTest {
    @Test
    fun apkIntentSelectsOnlyTheSystemInstallerAndGrantsReadAccess() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val uri = Uri.parse("content://test.provider/update.apk")
        val manager = application.packageManager
        val component = ComponentName("test.system.installer", "test.system.installer.InstallActivity")
        val activity = shadowOf(manager).addActivityIfNotPresent(component)
        activity.exported = true
        activity.applicationInfo.flags = activity.applicationInfo.flags or ApplicationInfo.FLAG_SYSTEM
        shadowOf(manager)
            .addIntentFilterForActivity(
                component,
                IntentFilter(Intent.ACTION_VIEW).apply {
                    addCategory(Intent.CATEGORY_DEFAULT)
                    addDataScheme("content")
                    addDataType("application/vnd.android.package-archive")
                },
            )
        val intent = systemApkInstallerIntent(manager, uri)
        assertEquals("test.system.installer", intent.component?.packageName)
        assertEquals(uri, intent.data)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun missingSystemInstallerFailsExplicitly() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        assertFailsWith<IllegalStateException> {
            systemApkInstallerIntent(application.packageManager, Uri.parse("content://missing.provider/update.apk"))
        }
    }

    @Test
    fun verificationCacheRequiresSameDownloadReleaseAndUnchangedFile() {
        val file = File.createTempFile("update", ".apk")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3))
            val release =
                AppUpdateRelease(
                    "6.4",
                    "https://github.com/mvisions/Meshtastic-Android/releases/download/media-links-test-6.4/Meshtastic-media-arm64.apk",
                    3,
                    "a".repeat(64),
                )
            val cached = VerifiedApkDownload(973, release, file.length(), file.lastModified())

            assertTrue(cached.matches(973, release, file))
            assertFalse(cached.matches(974, release, file))
            assertFalse(cached.matches(973, release.copy(sha256 = "b".repeat(64)), file))

            file.appendBytes(byteArrayOf(4))
            assertFalse(cached.matches(973, release, file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun downloadSurvivesInstallerRecreationAndUsesVersionedPrivateDestination() = runTest {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = CoroutineDispatchers(dispatcher, dispatcher, dispatcher)
        shadowOf(application.packageManager)
            .installPackage(
                PackageInfo().apply {
                    packageName = application.packageName
                    versionName = "2.8.4-media-part6.3"
                },
            )
        val release =
            AppUpdateRelease(
                "6.4",
                "https://github.com/mvisions/Meshtastic-Android/releases/download/media-links-test-6.4/Meshtastic-media-arm64.apk",
                100,
                "a".repeat(64),
            )
        val installer = AndroidAppUpdateInstaller(application, dispatchers)
        installer.download(release)
        val restored = AndroidAppUpdateInstaller(application, dispatchers)
        assertEquals(release, restored.pendingRelease())
        val id = application.getSharedPreferences("app-update", Context.MODE_PRIVATE).getLong("download-id", -1)
        val manager = application.getSystemService(DownloadManager::class.java)
        manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            assertTrue(cursor.moveToFirst())
            val uri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
            assertTrue(uri.endsWith("/Download/app-updates/Meshtastic-media-part6.4-arm64.apk"))
            assertEquals(release.url, cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)))
        }
        assertFailsWith<IllegalStateException> { restored.install(release) }
        assertEquals(null, shadowOf(application).nextStartedActivity)
        manager.remove(id)
    }
}
