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

import org.meshtastic.core.network.AppUpdateRelease

data class AppUpdateApk(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val signerDigests: Set<String>,
)

fun validateAppUpdate(
    release: AppUpdateRelease,
    downloadedSize: Long,
    downloadedSha256: String,
    installed: AppUpdateApk,
    candidate: AppUpdateApk,
) {
    check(downloadedSize == release.size) { "Update size does not match the release" }
    check(downloadedSha256.equals(release.sha256, ignoreCase = true)) { "Update SHA-256 does not match the release" }
    check(candidate.packageName == installed.packageName) { "Update belongs to a different application" }
    check(candidate.versionCode > installed.versionCode) { "Update is not newer than the installed application" }
    check(candidate.signerDigests.isNotEmpty() && candidate.signerDigests == installed.signerDigests) {
        "Update signing certificate does not match the installed application"
    }
    val expected = Regex("""media-part${Regex.escape(release.version)}(?:\s|$)""")
    check(expected.containsMatchIn(candidate.versionName)) { "APK version does not match the selected release" }
}
