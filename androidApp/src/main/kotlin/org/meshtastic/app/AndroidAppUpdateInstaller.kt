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
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Single
import org.meshtastic.core.di.CoroutineDispatchers
import org.meshtastic.core.network.AppUpdateRelease
import org.meshtastic.feature.settings.component.AppUpdateApk
import org.meshtastic.feature.settings.component.AppUpdateInstallResult
import org.meshtastic.feature.settings.component.AppUpdateInstaller
import org.meshtastic.feature.settings.component.AppUpdateProgress
import org.meshtastic.feature.settings.component.validateAppUpdate
import java.io.File
import java.security.MessageDigest

@Single
class AndroidAppUpdateInstaller(private val application: Application, private val dispatchers: CoroutineDispatchers) :
    AppUpdateInstaller {
    private val downloads = application.getSystemService(DownloadManager::class.java)
    private val preferences = application.getSharedPreferences("app-update", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    override suspend fun installedVersion(): String = withContext(dispatchers.io) {
        checkNotNull(installedPackage().versionName) { "Installed application version is unavailable" }
    }

    override suspend fun pendingRelease(): AppUpdateRelease? = withContext(dispatchers.io) {
        preferences.getString(RELEASE, null)?.let { Json.decodeFromString<AppUpdateRelease>(it) }
    }

    override suspend fun download(release: AppUpdateRelease) = withContext(dispatchers.io) {
        mutex.withLock {
            check(!release.isInstalled(installedVersion())) { "This release is already installed" }
            val previous = preferences.getLong(DOWNLOAD_ID, -1)
            if (previous != -1L) downloads.remove(previous)
            val file = downloadFile(release)
            if (file.exists()) check(file.delete()) { "Unable to replace the previous update download" }
            val request =
                DownloadManager.Request(Uri.parse(release.url))
                    .setTitle(release.fileName)
                    .setMimeType(APK_MIME)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setDestinationInExternalFilesDir(
                        application,
                        Environment.DIRECTORY_DOWNLOADS,
                        "$UPDATE_DIRECTORY/${release.fileName}",
                    )
            val id = downloads.enqueue(request)
            if (
                !preferences
                    .edit()
                    .putString(RELEASE, Json.encodeToString(release))
                    .putLong(DOWNLOAD_ID, id)
                    .commit()
            ) {
                downloads.remove(id)
                error("Unable to persist the update download")
            }
        }
    }

    override suspend fun progress(release: AppUpdateRelease): AppUpdateProgress = withContext(dispatchers.io) {
        mutex.withLock {
            if (release.isInstalled(installedVersion())) return@withLock AppUpdateProgress.Installed
            check(pendingRelease() == release) { "Update download does not match the selected release" }
            val id = preferences.getLong(DOWNLOAD_ID, -1)
            downloads.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                if (!cursor.moveToFirst()) return@withLock AppUpdateProgress.Missing
                when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        verify(release)
                        AppUpdateProgress.Ready
                    }

                    DownloadManager.STATUS_FAILED -> {
                        val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        error("Update download failed (DownloadManager reason $reason)")
                    }

                    else ->
                        AppUpdateProgress.Downloading(
                            cursor.getLong(
                                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                            ),
                            release.size,
                        )
                }
            }
        }
    }

    override suspend fun install(release: AppUpdateRelease): AppUpdateInstallResult {
        withContext(dispatchers.io) { mutex.withLock { verify(release) } }
        return withContext(dispatchers.main) {
            if (!application.packageManager.canRequestPackageInstalls()) {
                application.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${application.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                AppUpdateInstallResult.PERMISSION_REQUIRED
            } else {
                val uri =
                    FileProvider.getUriForFile(
                        application,
                        "${application.packageName}.media-capture",
                        downloadFile(release),
                    )
                application.startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, APK_MIME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
                )
                AppUpdateInstallResult.OPENED
            }
        }
    }

    private suspend fun verify(release: AppUpdateRelease) {
        check(pendingRelease() == release) { "Update download does not match the selected release" }
        val file = downloadFile(release)
        check(file.isFile) { "Update APK is missing" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var count = input.read(buffer)
            while (count != -1) {
                currentCoroutineContext().ensureActive()
                digest.update(buffer, 0, count)
                count = input.read(buffer)
            }
        }
        val archive =
            checkNotNull(application.packageManager.getPackageArchiveInfo(file.path, signatureFlags())) {
                "Downloaded APK cannot be read"
            }
        validateAppUpdate(
            release,
            file.length(),
            digest.digest().toHexString(),
            installedPackage().metadata(),
            archive.metadata(),
        )
    }

    private fun downloadFile(release: AppUpdateRelease): File {
        require(release.version.matches(Regex("""\d+(?:\.\d+)*"""))) { "Invalid release version" }
        val root =
            checkNotNull(application.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)) {
                "Update download storage is unavailable"
            }
        return File(root, "$UPDATE_DIRECTORY/${release.fileName}")
    }

    @Suppress("DEPRECATION")
    private fun installedPackage(): PackageInfo =
        application.packageManager.getPackageInfo(application.packageName, signatureFlags())

    @Suppress("DEPRECATION")
    private fun signatureFlags(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        PackageManager.GET_SIGNATURES
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.metadata(): AppUpdateApk {
        val signers =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) signingInfo?.apkContentsSigners else signatures
        return AppUpdateApk(
            packageName,
            PackageInfoCompat.getLongVersionCode(this),
            checkNotNull(versionName) { "APK version name is missing" },
            signers
                .orEmpty()
                .map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toHexString() }
                .toSet(),
        )
    }

    private companion object {
        const val RELEASE = "release"
        const val DOWNLOAD_ID = "download-id"
        const val UPDATE_DIRECTORY = "app-updates"
        const val APK_MIME = "application/vnd.android.package-archive"
    }
}
