package com.example.scanner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.CloudFrontScannerApp
import com.example.MainActivity
import com.example.R
import com.example.domain.model.ScanPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ScanForegroundService : Service() {

  companion object {
    const val CHANNEL_ID = "cloudfront_scan_channel"
    const val NOTIFICATION_ID = 4091
    const val ACTION_START_KEEPALIVE = "com.example.action.START_SCAN_SERVICE"
    const val ACTION_PAUSE = "com.example.action.PAUSE_SCAN"
    const val ACTION_RESUME = "com.example.action.RESUME_SCAN"
    const val ACTION_STOP = "com.example.action.STOP_SCAN"

    fun startService(context: Context) {
      val intent = Intent(context, ScanForegroundService::class.java).apply {
        action = ACTION_START_KEEPALIVE
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }
  }

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val app = application as CloudFrontScannerApp
    val coordinator = app.scanCoordinator

    when (intent?.action) {
      ACTION_PAUSE -> coordinator.pauseScan()
      ACTION_STOP -> {
        coordinator.stopScan()
        stopSelfSafe()
        return START_NOT_STICKY
      }
      ACTION_RESUME -> {
        serviceScope.launch {
          val settings = app.settingsStore.settingsFlow.first()
          val rawVless = app.settingsStore.vlessUriFlow.first()
          val vless = com.example.core.network.VlessParser.parse(rawVless).getOrNull()
          coordinator.resumeScan(settings, vless)
        }
      }
    }

    val notification = buildNotification(coordinator.liveState.value)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(
        NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
      )
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }

    coordinator.attachServiceCallbacks(
      onUpdate = { state ->
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(state))
      },
      onFinish = { stopSelfSafe() },
    )

    return START_NOT_STICKY
  }

  private fun stopSelfSafe() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      stopForeground(STOP_FOREGROUND_REMOVE)
    } else {
      @Suppress("DEPRECATION")
      stopForeground(true)
    }
    stopSelf()
  }

  private fun buildNotification(state: LiveScanUiState): Notification {
    val openAppIntent =
      PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).apply {
          flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )

    val title =
      when (state.phase) {
        ScanPhase.PHASE1_PROBING -> "اسکن مرحله اول (${state.processedPhase1}/${state.totalPhase1Targets})"
        ScanPhase.PHASE2_TUNNEL -> "تست تونل Xray (${state.processedPhase2}/${state.totalPhase2Targets})"
        ScanPhase.PAUSED -> "اسکن متوقف موقت (Paused)"
        else -> getString(R.string.app_name)
      }
    val text =
      "Tunnel Pass: ${state.tunnelPassCount} | TCP/TLS/WS Pass: ${state.qualifiedPhase1Count} | Fail: ${state.failCount}"

    val builder =
      NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(title)
        .setContentText(text)
        .setOngoing(state.phase == ScanPhase.PHASE1_PROBING || state.phase == ScanPhase.PHASE2_TUNNEL)
        .setOnlyAlertOnce(true)
        .setContentIntent(openAppIntent)

    if (state.phase == ScanPhase.PAUSED) {
      val resumeIntent =
        PendingIntent.getService(
          this,
          1,
          Intent(this, ScanForegroundService::class.java).apply { action = ACTION_RESUME },
          PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
      builder.addAction(0, "ادامه", resumeIntent)
    } else {
      val pauseIntent =
        PendingIntent.getService(
          this,
          2,
          Intent(this, ScanForegroundService::class.java).apply { action = ACTION_PAUSE },
          PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
      builder.addAction(0, "مکث", pauseIntent)
    }

    val stopIntent =
      PendingIntent.getService(
        this,
        3,
        Intent(this, ScanForegroundService::class.java).apply { action = ACTION_STOP },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
    builder.addAction(0, "توقف", stopIntent)

    return builder.build()
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      val channel =
        NotificationChannel(
          CHANNEL_ID,
          getString(R.string.notification_channel_scan_name),
          NotificationManager.IMPORTANCE_LOW,
        ).apply {
          description = getString(R.string.notification_channel_scan_desc)
        }
      nm.createNotificationChannel(channel)
    }
  }

  override fun onDestroy() {
    (application as? CloudFrontScannerApp)?.scanCoordinator?.detachServiceCallbacks()
    serviceScope.cancel()
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null
}
