package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.i18n.LocalAppStrings
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScannerScreen(
  onQrDecoded: (String) -> Unit,
  onClose: () -> Unit,
) {
  BackHandler(onBack = onClose)

  val strings = LocalAppStrings.current
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current

  var hasPermission by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
    )
  }

  val permissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      hasPermission = granted
    }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(strings.qrTitle) },
        navigationIcon = {
          IconButton(onClick = onClose) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
      )
    }
  ) { innerPadding ->
    if (!hasPermission) {
      Column(
        modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
      ) {
        Text(
          text = strings.qrCameraPermissionMsg,
          style = MaterialTheme.typography.bodyLarge,
        )
        Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
          Text(strings.grantPermission)
        }
      }
    } else {
      val executor = remember { Executors.newSingleThreadExecutor() }
      val scanner = remember { BarcodeScanning.getClient() }
      var handled by remember { mutableStateOf(false) }

      DisposableEffect(Unit) {
        onDispose {
          scanner.close()
          executor.shutdown()
        }
      }

      Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
        AndroidView(
          modifier = Modifier.fillMaxSize(),
          factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener(
              {
                val cameraProvider = cameraProviderFuture.get()
                val preview =
                  Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                  }
                val analysis =
                  ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { imageAnalysis ->
                      imageAnalysis.setAnalyzer(executor) { imageProxy ->
                        val mediaImage = imageProxy.image
                        if (mediaImage != null && !handled) {
                          val img =
                            InputImage.fromMediaImage(
                              mediaImage,
                              imageProxy.imageInfo.rotationDegrees,
                            )
                          scanner
                            .process(img)
                            .addOnSuccessListener { barcodes ->
                              val match =
                                barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
                              if (!match.isNullOrBlank() && !handled) {
                                handled = true
                                onQrDecoded(match)
                              }
                            }
                            .addOnCompleteListener { imageProxy.close() }
                        } else {
                          imageProxy.close()
                        }
                      }
                    }

                runCatching {
                  cameraProvider.unbindAll()
                  cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                  )
                }
              },
              ContextCompat.getMainExecutor(ctx),
            )
            previewView
          },
        )
        Text(
          text = strings.qrTitle,
          color = MaterialTheme.colorScheme.onPrimary,
          modifier =
            Modifier.align(Alignment.BottomCenter)
              .fillMaxWidth()
              .padding(16.dp),
        )
      }
    }
  }
}
