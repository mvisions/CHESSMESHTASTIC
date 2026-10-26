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

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import co.touchlab.kermit.Logger
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.meshtastic.core.common.BuildConfigProvider
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.app_support_body
import org.meshtastic.core.resources.app_support_error
import org.meshtastic.core.resources.app_support_report
import org.meshtastic.core.resources.app_support_subject
import org.meshtastic.core.ui.component.ListItem

private const val SUPPORT_EMAIL = "msabanda@gmail.com"

fun createSupportEmailIntent(subject: String, body: String): Intent = Intent(
    Intent.ACTION_SENDTO,
    Uri.parse("mailto:$SUPPORT_EMAIL?subject=${Uri.encode(subject)}&body=${Uri.encode(body)}"),
)

@Composable
fun SupportEmailSetting(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val build = koinInject<BuildConfigProvider>()
    val subject = stringResource(Res.string.app_support_subject, build.versionName)
    val body = stringResource(Res.string.app_support_body, build.versionName)
    val errorText = stringResource(Res.string.app_support_error)
    val onError: (Exception) -> Unit = { error ->
        Logger.e(error) { "Unable to open support email application" }
        Toast.makeText(context, errorText, Toast.LENGTH_LONG).show()
    }
    ListItem(
        modifier = modifier,
        text = stringResource(Res.string.app_support_report),
        supportingText = SUPPORT_EMAIL,
        onClick = {
            try {
                context.startActivity(createSupportEmailIntent(subject, body))
            } catch (error: ActivityNotFoundException) {
                onError(error)
            } catch (error: SecurityException) {
                onError(error)
            }
        },
    )
}
