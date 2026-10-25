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
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.core.annotation.KoinViewModel
import org.meshtastic.core.common.util.CommonUri
import org.meshtastic.core.common.util.nowMillis
import org.meshtastic.core.model.util.VipMessageTooLongException
import org.meshtastic.core.repository.FileService
import org.meshtastic.core.repository.MediaUploadHost
import org.meshtastic.core.repository.MediaUploader
import org.meshtastic.core.repository.usecase.SendMessageUseCase
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.UiText
import org.meshtastic.core.resources.cancel
import org.meshtastic.core.resources.media_backup_title
import org.meshtastic.core.resources.media_backup_uguu
import org.meshtastic.core.resources.media_backup_upload
import org.meshtastic.core.resources.media_unsupported_type
import org.meshtastic.core.resources.media_upload_failed
import org.meshtastic.core.resources.vip_message_too_long
import org.meshtastic.core.ui.util.AlertManager
import org.meshtastic.core.ui.util.SnackbarManager
import org.meshtastic.core.ui.viewmodel.safeLaunch
import org.meshtastic.feature.messaging.MessagingUiTextResolver
import org.meshtastic.feature.messaging.component.MESSAGE_CHARACTER_LIMIT_BYTES

/**
 * Uploads a picked photo/video or a recorded voice message to the temporary file host and sends the resulting link over
 * the mesh.
 */
@KoinViewModel
class MediaUploadViewModel(
    private val fileService: FileService,
    private val mediaUploader: MediaUploader,
    private val sendMessageUseCase: SendMessageUseCase,
    private val snackbarManager: SnackbarManager,
    private val alertManager: AlertManager,
) : ViewModel() {

    private val _isUploading = MutableStateFlow(false)
    val isUploading: StateFlow<Boolean> = _isUploading.asStateFlow()

    private data class UploadRequest(
        val uri: CommonUri,
        val contactKey: String,
        val replyId: Int?,
        val durationMillis: Long?,
    )

    private data class UploadAttempt(val url: String?, val readable: Boolean)

    private var pendingBackup: UploadRequest? = null
    private var backupAlert: AlertManager.AlertData? = null

    /**
     * Uploads [uri] and sends its link. [durationMillis] (voice messages) is appended to the link as a `#d=` hint so
     * receivers can show the length without downloading.
     */
    fun uploadAndSend(uri: CommonUri, contactKey: String, replyId: Int?, durationMillis: Long? = null) {
        if (_isUploading.value) return
        _isUploading.value = true
        uploadAndSend(UploadRequest(uri, contactKey, replyId, durationMillis), MediaUploadHost.LITTERBOX)
    }

    private fun uploadAndSend(request: UploadRequest, host: MediaUploadHost) {
        safeLaunch(tag = "uploadMedia") {
            try {
                val mimeType = fileService.mimeType(request.uri) ?: mimeTypeFromName(request.uri.toString())
                val extension = mediaExtensionFor(mimeType)
                if (mimeType == null || extension == null) {
                    showMessage(UiText.Resource(Res.string.media_unsupported_type))
                    return@safeLaunch
                }
                val attempt = upload(request.uri, "meshtastic_$nowMillis.$extension", mimeType, host)
                val url =
                    attempt.url?.let { uploaded ->
                        request.durationMillis?.let { withDurationHint(uploaded, it) } ?: uploaded
                    }
                // A link must fit one mesh text message just like typed text; never send a truncated URL.
                if (url == null || !fitsInOneMessage(url)) {
                    if (host == MediaUploadHost.LITTERBOX && attempt.readable) {
                        offerBackup(request)
                    } else {
                        showMessage(UiText.Resource(Res.string.media_upload_failed))
                    }
                } else {
                    sendMessageUseCase(url, request.contactKey, request.replyId)
                }
            } catch (e: VipMessageTooLongException) {
                Logger.w(e) { "Media link exceeds the VIP Brotli radio packet limit" }
                showMessage(UiText.Resource(Res.string.vip_message_too_long))
            } finally {
                if (pendingBackup == null) _isUploading.value = false
            }
        }
    }

    private fun offerBackup(request: UploadRequest) {
        pendingBackup = request
        alertManager.showAlert(
            titleRes = Res.string.media_backup_title,
            messageRes = Res.string.media_backup_uguu,
            confirmTextRes = Res.string.media_backup_upload,
            dismissTextRes = Res.string.cancel,
            onConfirm = {
                pendingBackup = null
                backupAlert = null
                uploadAndSend(request, MediaUploadHost.UGUU)
            },
            onDismiss = {
                pendingBackup = null
                backupAlert = null
                _isUploading.value = false
            },
        )
        backupAlert = alertManager.currentAlert.value
    }

    override fun onCleared() {
        if (backupAlert != null && alertManager.currentAlert.value === backupAlert) alertManager.dismissAlert()
        super.onCleared()
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun upload(
        uri: CommonUri,
        fileName: String,
        mimeType: String,
        host: MediaUploadHost,
    ): UploadAttempt {
        var url: String? = null
        var readable = false
        try {
            val read =
                fileService.read(uri) { source ->
                    readable = true
                    url = mediaUploader.upload(source, fileName, mimeType, host)
                }
            if (!read) Logger.w { "Media file read or upload failed ($host)" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w { "Media upload failed ($host): ${e::class.simpleName}" }
        }
        return UploadAttempt(url, readable)
    }

    private suspend fun showMessage(text: UiText) {
        snackbarManager.showSnackbar(MessagingUiTextResolver.resolve(text))
    }
}

/** Whether [url] fits in a single mesh text message under the same byte limit the composer enforces. */
internal fun fitsInOneMessage(url: String): Boolean = url.encodeToByteArray().size <= MESSAGE_CHARACTER_LIMIT_BYTES
