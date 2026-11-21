package com.mylittleworld.child.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.mylittleworld.child.R
import com.mylittleworld.child.core.DeviceActions
import com.mylittleworld.child.ui.theme.MyLittleWorldTheme
import com.mylittleworld.child.ui.theme.Palette
import com.mylittleworld.shared.util.AppLog
import java.io.File

/**
 * Simplified camera built on CameraX. Two huge controls (photo, video), local
 * saving only, and no path by which a remote party can start the camera.
 */
class CameraActivity : ComponentActivity() {

    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private var lensFacing by mutableStateOf(CameraSelector.LENS_FACING_BACK)
    private var isRecording by mutableStateOf(false)
    private var statusMessage by mutableStateOf<String?>(null)
    private var cameraAvailable by mutableStateOf(true)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (!(grants[Manifest.permission.CAMERA] ?: hasCameraPermission())) {
            statusMessage = getString(R.string.camera_permission)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!DeviceActions.hasSystemCamera(this)) {
            cameraAvailable = false
            setContent { MyLittleWorldTheme { UnavailableScreen() } }
            return
        }
        ensurePermissions()
        setContent { MyLittleWorldTheme { CameraScreen() } }
    }

    private fun ensurePermissions() {
        val missing = mutableListOf<String>()
        if (!hasCameraPermission()) missing.add(Manifest.permission.CAMERA)
        if (!hasMicPermission()) missing.add(Manifest.permission.RECORD_AUDIO)
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA,
    ) == PackageManager.PERMISSION_GRANTED

    private fun hasMicPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    /* ---------------------------- capture ---------------------------- */

    private fun takePhoto() {
        val capture = imageCapture
        if (capture == null) {
            statusMessage = getString(R.string.camera_unavailable)
            return
        }
        val name = "MLW_${System.currentTimeMillis()}.jpg"
        val output = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/MyLittleWorld")
            }
            ImageCapture.OutputFileOptions.Builder(
                contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values,
            ).build()
        } else {
            val dir = File(getExternalFilesDir(null), "media").apply { mkdirs() }
            ImageCapture.OutputFileOptions.Builder(File(dir, name)).build()
        }
        capture.takePicture(
            output,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                    results.savedUri?.let { uri ->
                        MediaScannerConnection.scanFile(
                            this@CameraActivity, arrayOf(uri.toString()), null, null,
                        )
                    }
                    statusMessage = getString(R.string.camera_saved)
                    DeviceActions.toneConfirm()
                }

                override fun onError(exception: ImageCaptureException) {
                    AppLog.w("CameraActivity", "Photo capture failed (${exception.imageCaptureError})")
                    statusMessage = getString(R.string.camera_unavailable)
                }
            },
        )
    }

    @SuppressLint("MissingPermission") // hasMicPermission() is checked before audio is enabled
    private fun startRecording() {
        val capture = videoCapture
        if (capture == null) {
            statusMessage = getString(R.string.camera_unavailable)
            return
        }
        val name = "MLW_${System.currentTimeMillis()}.mp4"
        val pending = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MyLittleWorld")
            }
            capture.output.prepareRecording(
                this,
                MediaStoreOutputOptions.Builder(
                    contentResolver,
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                ).setContentValues(values).build(),
            )
        } else {
            val dir = File(getExternalFilesDir(null), "media").apply { mkdirs() }
            capture.output.prepareRecording(this, FileOutputOptions.Builder(File(dir, name)).build())
        }
        val prepared = if (hasMicPermission()) {
            runCatching { pending.withAudioEnabled() }.getOrDefault(pending)
        } else {
            pending
        }
        recording = prepared.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> isRecording = true
                is VideoRecordEvent.Finalize -> {
                    isRecording = false
                    if (event.hasError()) {
                        AppLog.w("CameraActivity", "Video error ${event.error}")
                        statusMessage = getString(R.string.camera_unavailable)
                    } else {
                        event.outputResults.outputUri?.let { uri ->
                            MediaScannerConnection.scanFile(
                                this@CameraActivity, arrayOf(uri.toString()), null, null,
                            )
                        }
                        statusMessage = getString(R.string.camera_saved)
                    }
                    recording = null
                }
                else -> Unit
            }
        }
    }

    private fun stopRecording() {
        runCatching { recording?.stop() }
        recording = null
        isRecording = false
    }

    /* ---------------------------- UI ---------------------------- */

    @Composable
    private fun CameraScreen() {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current

        DisposableEffect(Unit) {
            onDispose { stopRecording() }
        }

        if (!cameraAvailable) {
            UnavailableScreen()
            return
        }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            androidx.compose.runtime.key(previewGeneration) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        val future = ProcessCameraProvider.getInstance(ctx)
                        future.addListener({
                            runCatching {
                                val provider = future.get()
                                val preview = Preview.Builder().build().also {
                                    it.setSurfaceProvider(surfaceProvider)
                                }
                                val capture = ImageCapture.Builder()
                                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                                    .build()
                                val recorder = Recorder.Builder()
                                    .setQualitySelector(
                                        QualitySelector.fromOrderedList(
                                            listOf(Quality.HD, Quality.SD),
                                            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
                                        ),
                                    )
                                    .build()
                                val video = VideoCapture.withOutput(recorder)
                                provider.unbindAll()
                                val selector = CameraSelector.Builder()
                                    .requireLensFacing(lensFacing)
                                    .build()
                                if (!provider.hasCamera(selector)) {
                                    cameraAvailable = false
                                    return@addListener
                                }
                                provider.bindToLifecycle(
                                    lifecycleOwner, selector, preview, capture, video,
                                )
                                imageCapture = capture
                                videoCapture = video
                            }.onFailure { error ->
                                AppLog.w("CameraActivity", "Camera bind failed", error)
                                cameraAvailable = false
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                    }
                },
                onRelease = { view ->
                    runCatching {
                        ProcessCameraProvider.getInstance(view.context).get().unbindAll()
                    }
                    imageCapture = null
                    videoCapture = null
                },
            )
            }

            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                statusMessage?.let { message ->
                    Text(
                        message,
                        color = Color.White,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.Black.copy(alpha = 0.45f))
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundButton(
                        label = stringResource(R.string.camera_close),
                        color = Color(0xFF6B5B70),
                        onClick = { finish() },
                    ) { Text("✕", fontSize = 26.sp, color = Color.White) }

                    RoundButton(
                        label = if (isRecording) stringResource(R.string.camera_stop)
                        else stringResource(R.string.camera_shutter),
                        color = if (isRecording) Color(0xFFE05263) else Color.White,
                        size = 104.dp,
                        onClick = {
                            if (isRecording) {
                                stopRecording()
                            } else if (!hasCameraPermission()) {
                                permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
                            } else {
                                takePhoto()
                            }
                        },
                    ) {
                        Box(
                            modifier = Modifier
                                .size(if (isRecording) 34.dp else 74.dp)
                                .clip(if (isRecording) RoundedCornerShape(10.dp) else CircleShape)
                                .background(if (isRecording) Color.White else Palette.LearningEnd),
                        )
                    }

                    RoundButton(
                        label = if (isRecording) stringResource(R.string.camera_stop)
                        else stringResource(R.string.camera_record),
                        color = Color(0xFF8E6BD1),
                        onClick = {
                            if (isRecording) {
                                stopRecording()
                            } else if (!hasCameraPermission()) {
                                ensurePermissions()
                            } else {
                                startRecording()
                            }
                        },
                    ) {
                        Canvas(Modifier.size(34.dp)) {
                            drawCircle(
                                color = Color.White,
                                radius = size.minDimension / 2f - 2f,
                                style = Stroke(width = 3.dp.toPx()),
                            )
                            drawCircle(color = Color.White, radius = size.minDimension / 4f)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.Center) {
                    Button(
                        onClick = {
                            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                                CameraSelector.LENS_FACING_FRONT
                            } else {
                                CameraSelector.LENS_FACING_BACK
                            }
                            // Rebinding happens through the AndroidView key change.
                            recreatePreview()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.2f)),
                        shape = RoundedCornerShape(22.dp),
                    ) { Text(stringResource(R.string.camera_switch), color = Color.White) }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    private var previewGeneration by mutableStateOf(0)

    private fun recreatePreview() {
        previewGeneration += 1
    }

    @Composable
    private fun RoundButton(
        label: String,
        color: Color,
        size: Dp = 72.dp,
        onClick: () -> Unit,
        content: @Composable () -> Unit,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(color)
                    .clickable(onClick = onClick)
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) { content() }
            Spacer(Modifier.height(6.dp))
            Text(label, color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }

    @Composable
    private fun UnavailableScreen() {
        Box(
            Modifier.fillMaxSize().background(Palette.Cream),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.camera_unavailable),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Palette.Ink,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { finish() },
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.CallEnd),
                ) { Text(stringResource(R.string.a11y_home)) }
                statusMessage?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = Palette.InkSoft, fontSize = 14.sp)
                }
            }
        }
    }
}
