package com.kerbos.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.kerbos.app.Nav
import com.kerbos.app.importOtpText
import java.util.concurrent.Executors

private val qrHints = mapOf(
    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
    DecodeHintType.TRY_HARDER to true,
)

private fun decode(src: LuminanceSource): String? {
    val reader = MultiFormatReader().apply { setHints(qrHints) }
    for (s in listOf(src, src.invert())) {
        try {
            return reader.decodeWithState(BinaryBitmap(HybridBinarizer(s))).text
        } catch (_: Exception) {
        } finally {
            reader.reset()
        }
    }
    return null
}

fun decodeQrFromBitmap(bmp: Bitmap): String? {
    var b = bmp
    if (b.width * b.height > 4_000_000) {
        val f = Math.sqrt(4_000_000.0 / (b.width * b.height))
        b = Bitmap.createScaledBitmap(b, (b.width * f).toInt(), (b.height * f).toInt(), true)
    }
    val px = IntArray(b.width * b.height)
    b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
    return decode(RGBLuminanceSource(b.width, b.height, px))
}

private class QrAnalyzer(val onResult: (String) -> Unit) : ImageAnalysis.Analyzer {
    @Volatile var paused = false
    override fun analyze(image: ImageProxy) {
        try {
            if (paused) return
            val plane = image.planes[0]
            val buf = plane.buffer
            val w = image.width; val h = image.height; val rs = plane.rowStride
            val bytes = ByteArray(w * h)
            for (y in 0 until h) {
                buf.position(y * rs)
                buf.get(bytes, y * w, w)
            }
            decode(PlanarYUVLuminanceSource(bytes, w, h, 0, 0, w, h, false))?.let {
                paused = true
                onResult(it)
            }
        } catch (_: Exception) {
        } finally {
            image.close()
        }
    }
}

@Composable
fun ScanScreen(nav: Nav) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val req = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) req.launch(Manifest.permission.CAMERA) }

    val executor = remember { Executors.newSingleThreadExecutor() }
    val main = remember { Handler(Looper.getMainLooper()) }
    val analyzer = remember {
        lateinit var a: QrAnalyzer
        a = QrAnalyzer { text ->
            main.post {
                if (!importOtpText(ctx, text, nav, replaceTop = true)) main.postDelayed({ a.paused = false }, 1500)
            }
        }
        a
    }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(ctx).get().unbindAll() }
            executor.shutdown()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            AndroidView(
                factory = { c ->
                    PreviewView(c).also { pv ->
                        val f = ProcessCameraProvider.getInstance(c)
                        f.addListener({
                            val p = f.get()
                            val preview = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
                            val analysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                                .also { it.setAnalyzer(executor, analyzer) }
                            runCatching {
                                p.unbindAll()
                                p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                            }
                        }, ContextCompat.getMainExecutor(c))
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier.align(Alignment.Center).size(260.dp)
                    .border(4.dp, Brush.linearGradient(listOf(Coral, Violet, Indigo)), RoundedCornerShape(32.dp)),
            )
            Text(
                "Visez le QR code 2FA", color = Color.White, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 90.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50)).padding(horizontal = 18.dp, vertical = 10.dp),
            )
        } else {
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Kerbos a besoin de l'appareil photo pour scanner les QR codes.", color = Color.White)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { req.launch(Manifest.permission.CAMERA) }) { Text("Autoriser") }
            }
        }
        IconButton(
            onClick = { nav.pop() },
            colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.4f), contentColor = Color.White),
            modifier = Modifier.systemBarsPadding().padding(12.dp),
        ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Retour") }
    }
}
