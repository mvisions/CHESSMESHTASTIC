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
package org.meshtastic.feature.messaging.media

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.core.annotation.KoinViewModel
import org.meshtastic.core.common.util.CommonUri
import org.meshtastic.core.common.util.nowMillis
import org.meshtastic.core.repository.FileService
import org.meshtastic.core.repository.MediaUploader
import org.meshtastic.core.repository.usecase.SendMessageUseCase
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.UiText
import org.meshtastic.core.resources.media_unsupported_type
import org.meshtastic.core.resources.media_upload_failed
import org.meshtastic.core.ui.util.SnackbarManager
import org.meshtastic.core.ui.viewmodel.safeLaunch
import org.meshtastic.feature.messaging.MessagingUiTextResolver

/** Uploads a picked photo/video to the temporary file host and sends the resulting link over the mesh. */
@KoinViewModel
class MediaUploadViewModel(
    private val fileService: FileService,
    private val mediaUploader: MediaUploader,
    private val sendMessageUseCase: SendMessageUseCase,
    private val snackbarManager: SnackbarManager,
) : ViewModel() {

    private val _isUploading = MutableStateFlow(false)
    val isUploading: StateFlow<Boolean> = _isUploading.asStateFlow()

    fun uploadAndSend(uri: CommonUri, contactKey: String, replyId: Int?) {
        if (_isUploading.value) return
        _isUploading.value = true
        safeLaunch(tag = "uploadMedia") {
            try {
                val mimeType = fileService.mimeType(uri) ?: mimeTypeFromName(uri.toString())
                val extension = mediaExtensionFor(mimeType)
                if (mimeType == null || extension == null) {
                    showMessage(UiText.Resource(Res.string.media_unsupported_type))
                    return@safeLaunch
                }
                val url = upload(uri, "meshtastic_$nowMillis.$extension", mimeType)
                if (url == null) {
                    showMessage(UiText.Resource(Res.string.media_upload_failed))
                } else {
                    sendMessageUseCase(url, contactKey, replyId)
                }
            } finally {
                _isUploading.value = false
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun upload(uri: CommonUri, fileName: String, mimeType: String): String? {
        var url: String? = null
        try {
            fileService.read(uri) { source -> url = mediaUploader.upload(source, fileName, mimeType) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        return url
    }

    private suspend fun showMessage(text: UiText) {
        snackbarManager.showSnackbar(MessagingUiTextResolver.resolve(text))
    }
}
