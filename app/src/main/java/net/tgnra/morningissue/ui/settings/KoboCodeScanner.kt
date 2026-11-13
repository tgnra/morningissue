package net.tgnra.morningissue.ui.settings

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors


/**
 * The back camera's picture, read for QR codes with CameraX and ZXing ([QrDecoder]), so
 * that no Google Play services are needed. [onCode] is called on the main thread with each
 * code's text, once per distinct text; [onUnavailable] when there is no camera to use. The
 * camera stops when this leaves the composition.
 */
@Composable
fun KoboCodeScanner(onCode: (String) -> Unit, onUnavailable: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnCode by rememberUpdatedState(onCode)
    val currentOnUnavailable by rememberUpdatedState(onUnavailable)
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    DisposableEffect(lifecycleOwner) {
        val analysisThread = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var disposed = false
        val decoder = QrDecoder()
        var last: String? = null
        future.addListener({
            if (disposed) return@addListener
            try {
                val cameras = future.get()
                provider = cameras
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisThread) { image ->
                    try {
                        val plane = image.planes[0]
                        val buffer = plane.buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        val text = decoder.decode(bytes, image.width, image.height, plane.rowStride)
                        if (text != null && text != last) {
                            last = text
                            main.execute { if (!disposed) currentOnCode(text) }
                        }
                    } finally {
                        image.close()
                    }
                }
                cameras.unbindAll()
                cameras.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (_: Exception) {
                // No back camera, or it is in use: the code can still be pasted.
                currentOnUnavailable()
            }
        }, main)
        onDispose {
            disposed = true
            provider?.unbindAll()
            analysisThread.shutdown()
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}
