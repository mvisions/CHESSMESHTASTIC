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

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import co.touchlab.kermit.Logger
import kotlinx.coroutines.launch
import org.meshtastic.core.common.util.CommonUri
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.voice_permission_denied
import org.meshtastic.core.ui.util.rememberShowToastResource
import java.io.File
import java.io.IOException

private const val MIN_RECORDING_MILLIS = 700L
private const val MAX_RECORDING_MILLIS = 5 * 60 * 1000
private const val SAMPLE_RATE_HZ = 22_050
private const val BIT_RATE = 32_000
private const val STALE_RECORDING_MILLIS = 60 * 60 * 1000L

@Composable
actual fun rememberVoiceRecorder(onRecord: (CommonUri, Long) -> Unit): VoiceRecorder? {
    val context = LocalContext.current
    val latestOnRecorded by rememberUpdatedState(onRecord)
    val showToast = rememberShowToastResource()
    val scope = rememberCoroutineScope()
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) scope.launch { showToast(Res.string.voice_permission_denied) }
        }
    val recorder =
        remember(context) {
            AndroidVoiceRecorder(
                context = context,
                requestPermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                onRecord = { uri, duration -> latestOnRecorded(uri, duration) },
            )
        }
    DisposableEffect(recorder) { onDispose { recorder.cancel() } }
    return recorder
}

private class AndroidVoiceRecorder(
    private val context: Context,
    private val requestPermission: () -> Unit,
    private val onRecord: (CommonUri, Long) -> Unit,
) : VoiceRecorder {
    override var isRecording by mutableStateOf(false)
        private set

    override var startedAtMillis by mutableLongStateOf(0L)
        private set

    private var recorder: MediaRecorder? = null
    private var output: File? = null

    override fun start() {
        val granted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        when {
            isRecording -> Unit
            !granted -> requestPermission()
            else -> begin()
        }
    }

    private fun begin() {
        val file = newRecordingFile()
        val mediaRecorder = newMediaRecorder()
        try {
            mediaRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(SAMPLE_RATE_HZ)
                setAudioEncodingBitRate(BIT_RATE)
                setMaxDuration(MAX_RECORDING_MILLIS)
                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) stop()
                }
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = mediaRecorder
            output = file
            startedAtMillis = System.currentTimeMillis()
            isRecording = true
        } catch (e: IllegalStateException) {
            fail(mediaRecorder, file, e)
        } catch (e: IOException) {
            fail(mediaRecorder, file, e)
        }
    }

    override fun stop() {
        val (completed, file) = finish() ?: return
        val duration = System.currentTimeMillis() - startedAtMillis
        if (completed && duration >= MIN_RECORDING_MILLIS) {
            onRecord(CommonUri.parse(Uri.fromFile(file).toString()), duration)
        } else {
            file.delete()
        }
    }

    override fun cancel() {
        finish()?.second?.delete()
    }

    /** Stops the recorder; returns whether the file is complete, and the file. */
    private fun finish(): Pair<Boolean, File>? {
        val mediaRecorder = recorder
        val file = output
        if (mediaRecorder == null || file == null) return null
        recorder = null
        output = null
        isRecording = false
        // stop() throws when nothing was captured yet (a tap released immediately).
        val completed = runCatching { mediaRecorder.stop() }.isSuccess
        mediaRecorder.release()
        return completed to file
    }

    private fun fail(mediaRecorder: MediaRecorder, file: File, error: Exception) {
        Logger.e(error) { "Could not start voice recording" }
        mediaRecorder.release()
        file.delete()
    }

    private fun newRecordingFile(): File {
        val dir = File(context.cacheDir, "voice").apply { mkdirs() }
        // Old recordings are only needed until their upload finishes.
        val cutoff = System.currentTimeMillis() - STALE_RECORDING_MILLIS
        dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        return File(dir, "voice_${System.currentTimeMillis()}.m4a")
    }

    private fun newMediaRecorder(): MediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        MediaRecorder(context)
    } else {
        @Suppress("DEPRECATION")
        MediaRecorder()
    }
}
