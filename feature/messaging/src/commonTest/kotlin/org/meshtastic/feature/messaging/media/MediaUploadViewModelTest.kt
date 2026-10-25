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

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import org.meshtastic.core.common.util.CommonUri
import org.meshtastic.core.repository.FileService
import org.meshtastic.core.repository.MediaUploadHost
import org.meshtastic.core.repository.MediaUploader
import org.meshtastic.core.repository.usecase.SendMessageOutcome
import org.meshtastic.core.repository.usecase.SendMessageUseCase
import org.meshtastic.core.ui.util.AlertManager
import org.meshtastic.core.ui.util.SnackbarManager
import org.meshtastic.feature.messaging.MessagingUiTextResolver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MediaUploadViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val store = ViewModelStore()
    private val alerts = AlertManager()
    private val hosts = mutableListOf<MediaUploadHost>()
    private val contents = mutableListOf<String>()
    private val sent = mutableListOf<Triple<String, String, Int?>>()
    private var primaryFails = true
    private var backupFails = false
    private var readable = true
    private var cancelled = false
    private lateinit var viewModel: MediaUploadViewModel
    private val originalResolver = MessagingUiTextResolver.resolve

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        MessagingUiTextResolver.resolve = { "Upload failed" }
        val files =
            object : FileService {
                override suspend fun mimeType(uri: CommonUri) = "audio/mp4"

                override suspend fun read(uri: CommonUri, block: suspend (BufferedSource) -> Unit): Boolean {
                    if (!readable) return false
                    // AndroidFileService catches upload errors raised inside its block.
                    return try {
                        block(Buffer().writeUtf8("recording"))
                        true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: IllegalStateException) {
                        false
                    }
                }

                override suspend fun write(uri: CommonUri, block: suspend (BufferedSink) -> Unit): Boolean =
                    error("Not used")
            }
        val uploader =
            object : MediaUploader {
                override suspend fun upload(
                    source: BufferedSource,
                    fileName: String,
                    contentType: String,
                    host: MediaUploadHost,
                ): String {
                    hosts += host
                    contents += source.readUtf8()
                    if (cancelled) throw CancellationException("Cancelled")
                    check(!(host == MediaUploadHost.LITTERBOX && primaryFails)) { "Primary unavailable" }
                    check(!(host == MediaUploadHost.UGUU && backupFails)) { "Backup unavailable" }
                    return if (host == MediaUploadHost.LITTERBOX) {
                        "https://litter.catbox.moe/test.m4a"
                    } else {
                        "https://d.uguu.se/test.m4a"
                    }
                }
            }
        val sender =
            object : SendMessageUseCase {
                override suspend fun invoke(text: String, contactKey: String, replyId: Int?): SendMessageOutcome {
                    sent += Triple(text, contactKey, replyId)
                    return SendMessageOutcome.Queued(1)
                }

                override suspend fun resend(uuid: Long, contactKey: String): SendMessageOutcome = error("Not used")
            }
        viewModel = MediaUploadViewModel(files, uploader, sender, SnackbarManager(), alerts)
        store.put("upload", viewModel)
    }

    @AfterTest
    fun tearDown() {
        store.clear()
        MessagingUiTextResolver.resolve = originalResolver
        Dispatchers.resetMain()
    }

    @Test
    fun primarySuccessDoesNotContactBackup() = runTest(dispatcher) {
        primaryFails = false
        upload()
        assertEquals(listOf(MediaUploadHost.LITTERBOX), hosts)
        assertEquals("https://litter.catbox.moe/test.m4a#d=12", sent.single().first)
        assertNull(alerts.currentAlert.value)
        assertFalse(viewModel.isUploading.value)
    }

    @Test
    fun failedPrimaryWaitsForConfirmationAndCancelDoesNotUpload() = runTest(dispatcher) {
        upload()
        assertEquals(listOf(MediaUploadHost.LITTERBOX), hosts)
        assertTrue(sent.isEmpty())
        assertTrue(viewModel.isUploading.value)
        val alert = assertNotNull(alerts.currentAlert.value)
        alert.onDismiss?.invoke()
        assertEquals(listOf(MediaUploadHost.LITTERBOX), hosts)
        assertFalse(viewModel.isUploading.value)
    }

    @Test
    fun confirmationReopensFileAndPreservesReplyRecipientAndDuration() = runTest(dispatcher) {
        upload()
        assertNotNull(alerts.currentAlert.value).onConfirm?.invoke()
        assertEquals(listOf(MediaUploadHost.LITTERBOX, MediaUploadHost.UGUU), hosts)
        assertEquals(listOf("recording", "recording"), contents)
        assertEquals(Triple("https://d.uguu.se/test.m4a#d=12", "0!ffffffff", 42), sent.single())
        assertTrue(fitsInOneMessage(sent.single().first))
        assertFalse(viewModel.isUploading.value)
    }

    @Test
    fun failedBackupSendsNothingAndDoesNotLoop() = runTest(dispatcher) {
        backupFails = true
        upload()
        assertNotNull(alerts.currentAlert.value).onConfirm?.invoke()
        assertEquals(listOf(MediaUploadHost.LITTERBOX, MediaUploadHost.UGUU), hosts)
        assertTrue(sent.isEmpty())
        assertNull(alerts.currentAlert.value)
        assertFalse(viewModel.isUploading.value)
    }

    @Test
    fun unreadableFileDoesNotOfferAnotherHost() = runTest(dispatcher) {
        readable = false
        upload()
        assertTrue(hosts.isEmpty())
        assertTrue(sent.isEmpty())
        assertNull(alerts.currentAlert.value)
        assertFalse(viewModel.isUploading.value)
    }

    @Test
    fun cancellationDoesNotOfferBackup() = runTest(dispatcher) {
        cancelled = true
        upload()
        assertNull(alerts.currentAlert.value)
        assertTrue(sent.isEmpty())
        assertFalse(viewModel.isUploading.value)
    }

    @Test
    fun leavingConversationDismissesPendingConfirmation() = runTest(dispatcher) {
        upload()
        assertNotNull(alerts.currentAlert.value)
        store.clear()
        assertNull(alerts.currentAlert.value)
        assertEquals(listOf(MediaUploadHost.LITTERBOX), hosts)
    }

    @Test
    fun waitingForConsentDoesNotAllowReplacingThePendingFile() = runTest(dispatcher) {
        upload()
        upload()
        assertEquals(listOf(MediaUploadHost.LITTERBOX), hosts)
        assertNotNull(alerts.currentAlert.value).onConfirm?.invoke()
        assertEquals(listOf(MediaUploadHost.LITTERBOX, MediaUploadHost.UGUU), hosts)
        assertEquals(1, sent.size)
    }

    private fun upload() = viewModel.uploadAndSend(CommonUri.parse("file:///voice.m4a"), "0!ffffffff", 42, 12_000)
}
