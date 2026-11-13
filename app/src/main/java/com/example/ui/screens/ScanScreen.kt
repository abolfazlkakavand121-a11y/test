package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.domain.model.IpFamilyPreference
import com.example.domain.model.Phase2SelectionMode
import com.example.domain.model.ScanMode
import com.example.domain.model.ScanPhase
import com.example.domain.model.ScanPreset
import com.example.ui.MainViewModel
import com.example.ui.components.BadgeTone
import com.example.ui.components.KpiMiniTile
import com.example.ui.components.LtrMonoText
import com.example.ui.components.StatusPill
import com.example.ui.components.TelemetryCard
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.MidnightBg
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusEmerald
import com.example.ui.theme.StatusRose
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScanScreen(
  viewModel: MainViewModel,
  onOpenQrScanner: () -> Unit,
  onNavigateToResults: () -> Unit,
) {
  val strings = LocalAppStrings.current
  val settings by viewModel.settings.collectAsStateWithLifecycle()
  val awsState by viewModel.awsUiState.collectAsStateWithLifecycle()
  val rawVless by viewModel.rawVlessUri.collectAsStateWithLifecycle()
  val parsedVless by viewModel.parsedVless.collectAsStateWithLifecycle()
  val compat by viewModel.vlessCompatibility.collectAsStateWithLifecycle()
  val manualText by viewModel.manualInputText.collectAsStateWithLifecycle()
  val manualReport by viewModel.manualParseReport.collectAsStateWithLifecycle()
  val liveState by viewModel.liveScanState.collectAsStateWithLifecycle()
  val selectedManualPhase2 by viewModel.selectedPhase2Ips.collectAsStateWithLifecycle()

  val clipboardManager = LocalClipboardManager.current
  var showOriginDialog by remember { mutableStateOf(false) }
  var showAdvanced by remember { mutableStateOf(false) }
  var showCustomSampleDialog by remember { mutableStateOf(false) }
  var showPhase2CustomDialog by remember { mutableStateOf(false) }
  var phase2Mode by remember(settings.phase2SelectionMode) { mutableStateOf(settings.phase2SelectionMode) }
  var phase2CustomCount by remember(settings.phase2CustomCount) { mutableStateOf(settings.phase2CustomCount) }

  val manualFileLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      uri?.let { viewModel.importTextFromSafUri(it, targetIsVless = false) }
    }
  val vlessFileLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      uri?.let { viewModel.importTextFromSafUri(it, targetIsVless = true) }
    }

  val isRunning =
    liveState.phase == ScanPhase.PHASE1_PROBING || liveState.phase == ScanPhase.PHASE2_TUNNEL
  val isPaused = liveState.phase == ScanPhase.PAUSED

  LazyColumn(
    modifier = Modifier.fillMaxSize().testTag("scan_lazy_list"),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    // 1. Hero Banner & AWS CloudFront Edge Status Card
    item {
      TelemetryCard {
        Box(
          modifier =
            Modifier.fillMaxWidth()
              .height(112.dp)
              .clip(RoundedCornerShape(12.dp))
        ) {
          Image(
            painter = painterResource(id = R.drawable.img_radar_banner),
            contentDescription = strings.awsRangesTitle,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
          )
          Box(
            modifier =
              Modifier.fillMaxSize()
                .background(
                  Brush.verticalGradient(
                    listOf(MidnightBg.copy(alpha = 0.25f), MidnightBg.copy(alpha = 0.88f))
                  )
                )
          )
          Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = strings.awsRangesTitle,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
              )
              if (awsState.isLoading) {
                CircularProgressIndicator(
                  modifier = Modifier.size(20.dp),
                  strokeWidth = 2.dp,
                  color = Color.White,
                )
              } else {
                StatusPill(
                  label =
                    awsState.snapshot?.let {
                      if (it.isFromCache) "CACHE" else "AWS LIVE"
                    } ?: "NO DATA",
                  tone =
                    when {
                      awsState.snapshot == null -> BadgeTone.ROSE
                      awsState.snapshot?.isStaleWarning == true -> BadgeTone.AMBER
                      awsState.snapshot?.isFromCache == true -> BadgeTone.SKY
                      else -> BadgeTone.EMERALD
                    },
                )
              }
            }
            val snap = awsState.snapshot
            if (snap != null) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                LtrMonoText(
                  text = "GLOBAL: ${snap.globalEdgeV4.size} v4 / ${snap.globalEdgeV6.size} v6",
                  color = Color(0xFF93E2FF),
                )
                LtrMonoText(
                  text = "sync: ${snap.syncToken}",
                  color = Color.White.copy(alpha = 0.85f),
                )
              }
            } else {
              Text(
                text = awsState.errorMessage ?: strings.awsNotFetchedYet,
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.bodySmall,
              )
            }
          }
        }

        val snap = awsState.snapshot
        if (snap != null) {
          val fetchedFmt =
            remember(snap.fetchedAtMillis) {
              SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(snap.fetchedAtMillis))
            }
          Text(
            text =
              if (strings.isRtl)
                "تاریخ انتشار AWS: ${snap.createDate} | ذخیره محلی: $fetchedFmt"
              else "AWS createDate: ${snap.createDate} | Fetched: $fetchedFmt",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          if (snap.isFromCache) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Icon(
                Icons.Default.WarningAmber,
                contentDescription = null,
                tint = StatusAmber,
                modifier = Modifier.size(16.dp),
              )
              Text(
                text = strings.awsCacheWarning,
                style = MaterialTheme.typography.bodySmall,
                color = StatusAmber,
              )
            }
          }
          if (snap.secondarySourceDiffNote.isNotBlank()) {
            Text(
              text = snap.secondarySourceDiffNote,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          OutlinedButton(
            onClick = { viewModel.refreshAwsRanges(force = true) },
            enabled = !awsState.isLoading,
            modifier = Modifier.weight(1f).testTag("refresh_aws_button"),
          ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(strings.awsSyncButton)
          }
          TextButton(
            onClick = { showOriginDialog = true },
            modifier = Modifier.testTag("origin_facing_info_button"),
          ) {
            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(strings.awsOriginInfoButton)
          }
        }
      }
    }

    // 2. Scan Mode Selector (Mode A vs Mode B)
    item {
      TelemetryCard {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
          SegmentedButton(
            selected = settings.scanMode == ScanMode.AUTO_CLOUDFRONT,
            onClick = { viewModel.setScanMode(ScanMode.AUTO_CLOUDFRONT) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            enabled = !isRunning,
            modifier = Modifier.testTag("mode_auto_btn"),
          ) {
            Text(strings.scanModeAuto, maxLines = 1)
          }
          SegmentedButton(
            selected = settings.scanMode == ScanMode.MANUAL_INPUT,
            onClick = { viewModel.setScanMode(ScanMode.MANUAL_INPUT) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            enabled = !isRunning,
            modifier = Modifier.testTag("mode_manual_btn"),
          ) {
            Text(strings.scanModeManual, maxLines = 1)
          }
        }

        if (settings.scanMode == ScanMode.AUTO_CLOUDFRONT) {
          Text(
            text = strings.ipFamilyLabel,
            style = MaterialTheme.typography.labelLarge,
          )
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            listOf(
              IpFamilyPreference.BOTH_50_50 to strings.ipFamilyBoth,
              IpFamilyPreference.IPV4_ONLY to strings.ipFamilyV4,
              IpFamilyPreference.IPV6_ONLY to strings.ipFamilyV6,
            ).forEach { (pref, label) ->
              FilterChip(
                selected = settings.ipFamilyPreference == pref,
                onClick = { viewModel.setIpFamilyPreference(pref) },
                label = { Text(label) },
                enabled = !isRunning,
              )
            }
          }

          Text(
            text = strings.sampleCountLabel,
            style = MaterialTheme.typography.labelLarge,
          )
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            listOf(50, 100, 500, 1000).forEach { count ->
              FilterChip(
                selected = settings.autoSampleCount == count,
                onClick = { viewModel.setAutoSampleCount(count) },
                label = { Text("$count") },
                enabled = !isRunning,
              )
            }
            FilterChip(
              selected = settings.autoSampleCount !in listOf(50, 100, 500, 1000),
              onClick = { showCustomSampleDialog = true },
              label = {
                Text(
                  if (settings.autoSampleCount !in listOf(50, 100, 500, 1000))
                    "${strings.customCount}: ${settings.autoSampleCount}"
                  else strings.customCount
                )
              },
              enabled = !isRunning,
            )
          }
        } else {
          // Mode B: Manual Input (NO redundant IPv4/IPv6 question)
          Text(
            text = strings.manualInputTitle,
            style = MaterialTheme.typography.labelLarge,
          )
          CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            OutlinedTextField(
              value = manualText,
              onValueChange = { viewModel.updateManualInput(it) },
              placeholder = { Text(strings.manualInputHint, fontFamily = JetBrainsMonoFamily) },
              modifier = Modifier.fillMaxWidth().height(132.dp).testTag("manual_ip_input"),
              textStyle =
                MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFamily),
              enabled = !isRunning,
            )
          }
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            OutlinedButton(
              onClick = {
                clipboardManager.getText()?.text?.let { viewModel.updateManualInput(it) }
              },
              enabled = !isRunning,
              modifier = Modifier.weight(1f).testTag("manual_paste_btn"),
            ) {
              Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(4.dp))
              Text(strings.pasteBtn)
            }
            OutlinedButton(
              onClick = { manualFileLauncher.launch(arrayOf("text/*", "text/csv", "*/*")) },
              enabled = !isRunning,
              modifier = Modifier.weight(1f).testTag("manual_file_btn"),
            ) {
              Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(4.dp))
              Text(strings.importFileBtn)
            }
          }

          manualReport?.let { rep ->
            Surface(
              shape = RoundedCornerShape(10.dp),
              color = MaterialTheme.colorScheme.surfaceVariant,
              modifier = Modifier.fillMaxWidth(),
            ) {
              Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
              ) {
                Text(
                  text =
                    if (strings.isRtl)
                      "${strings.manualSummaryLabel}: ${rep.uniqueIps.size} IP معتبر آماده | ${rep.duplicatesRemoved} تکراری حذف شد | ${rep.errors.size} خط نامعتبر"
                    else
                      "${strings.manualSummaryLabel}: ${rep.uniqueIps.size} valid unique | ${rep.duplicatesRemoved} duplicates removed | ${rep.errors.size} invalid lines",
                  style = MaterialTheme.typography.bodySmall,
                  fontWeight = FontWeight.SemiBold,
                )
                rep.cidrNotices.forEach { n ->
                  Text(
                    text =
                      if (n.wasSampled)
                        "${n.cidr}: ${n.totalHosts} آدرس — نمونه‌گیری تصادفی ${n.sampledCount} مورد"
                      else "${n.cidr}: اسکن کنترل‌شده ${n.sampledCount} آدرس",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                  )
                }
                rep.errors.take(4).forEach { err ->
                  Text(
                    text =
                      if (strings.isRtl) "خط ${err.lineNumber} (${err.rawLine}): ${err.reasonFa}"
                      else "Line ${err.lineNumber} (${err.rawLine}): ${err.reasonEn}",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusRose,
                  )
                }
              }
            }

            if (rep.cidrNotices.any { it.wasSampled }) {
              Text(
                text = "${strings.cidrSampleCapLabel}: ${settings.manualCidrSampleCap}",
                style = MaterialTheme.typography.bodySmall,
              )
              FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(50, 100, 250, 500).forEach { cap ->
                  FilterChip(
                    selected = settings.manualCidrSampleCap == cap,
                    onClick = { viewModel.setManualCidrSampleCap(cap) },
                    label = { Text("$cap") },
                    enabled = !isRunning,
                  )
                }
              }
            }
          }
        }
      }
    }

    // 3. VLESS Config Import & Compatibility Card
    item {
      TelemetryCard {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = strings.vlessSectionTitle,
            style = MaterialTheme.typography.titleMedium,
          )
          Icon(
            Icons.Default.Security,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
          )
        }

        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
          OutlinedTextField(
            value = rawVless,
            onValueChange = { viewModel.updateVlessUri(it) },
            placeholder = { Text(strings.vlessHint, fontFamily = JetBrainsMonoFamily) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("vless_uri_input"),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
            enabled = !isRunning,
          )
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          OutlinedButton(
            onClick = {
              clipboardManager.getText()?.text?.let { viewModel.updateVlessUri(it) }
            },
            enabled = !isRunning,
            modifier = Modifier.weight(1f).testTag("vless_paste_btn"),
          ) {
            Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(strings.pasteBtn)
          }
          OutlinedButton(
            onClick = { vlessFileLauncher.launch(arrayOf("text/*", "*/*")) },
            enabled = !isRunning,
            modifier = Modifier.weight(1f).testTag("vless_file_btn"),
          ) {
            Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(strings.importVlessFileBtn)
          }
          OutlinedButton(
            onClick = onOpenQrScanner,
            enabled = !isRunning,
            modifier = Modifier.weight(1f).testTag("vless_qr_btn"),
          ) {
            Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(strings.scanQrBtn)
          }
        }

        val vlessRes = parsedVless
        if (vlessRes == null) {
          Text(
            text = strings.vlessMissingNotice,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        } else if (vlessRes.isFailure) {
          Text(
            text = vlessRes.exceptionOrNull()?.message ?: "Invalid VLESS URI",
            style = MaterialTheme.typography.bodySmall,
            color = StatusRose,
          )
        } else {
          val cfg = vlessRes.getOrThrow()
          Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(
              modifier = Modifier.padding(10.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              LtrMonoText(
                text = cfg.redactedSummary(),
                maxLines = 3,
                color = MaterialTheme.colorScheme.primary,
              )
              compat?.let { rep ->
                Text(
                  text =
                    if (strings.isRtl) rep.compatibilityExplanationFa
                    else rep.compatibilityExplanationEn,
                  style = MaterialTheme.typography.bodySmall,
                  color =
                    if (rep.canRunPhase2 && rep.isCloudFrontCompatible) StatusEmerald
                    else if (rep.errors.isNotEmpty()) StatusRose
                    else StatusAmber,
                )
                rep.errors.forEach { err ->
                  Text(
                    text = "• $err",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusRose,
                  )
                }
                rep.warnings.forEach { w ->
                  Text(
                    text = "• $w",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusAmber,
                  )
                }
              }
            }
          }
        }
      }
    }

    // 4. Collapsible Quick Presets & Pre-scan Estimate
    item {
      TelemetryCard {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = strings.quickOptionsTitle,
            style = MaterialTheme.typography.titleMedium,
          )
          IconButton(onClick = { showAdvanced = !showAdvanced }) {
            Icon(
              if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
              contentDescription = strings.quickOptionsTitle,
            )
          }
        }

        AnimatedVisibility(visible = showAdvanced) {
          Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              listOf(
                ScanPreset.FAST to strings.presetFast,
                ScanPreset.BALANCED to strings.presetBalanced,
                ScanPreset.ACCURATE to strings.presetAccurate,
              ).forEach { (preset, label) ->
                FilterChip(
                  selected = settings.scanPreset == preset,
                  onClick = { viewModel.applyPreset(preset) },
                  label = { Text(label) },
                  enabled = !isRunning,
                )
              }
            }
            val estCount =
              if (settings.scanMode == ScanMode.AUTO_CLOUDFRONT) settings.autoSampleCount
              else manualReport?.uniqueIps?.size ?: 0
            val estMb =
              String.format(
                Locale.US,
                "~%.2f MB",
                (estCount * 0.008) + (settings.phase2CustomCount * settings.tunnelRepetitions * 0.02),
              )
            Text(
              text =
                if (strings.isRtl)
                  "${strings.dataEstimateTitle}: $estCount IP در مرحله اول | همزمانی: ${settings.phase1Workers} Worker | مصرف تقریبی داده: $estMb"
                else
                  "${strings.dataEstimateTitle}: $estCount IPs in Phase 1 | ${settings.phase1Workers} workers | Est. data: $estMb",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

        // Primary Start / Pause / Resume / Stop / Clear Controls
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          if (!isRunning && !isPaused) {
            Button(
              onClick = { viewModel.startPhase1Scan() },
              modifier = Modifier.weight(1f).height(50.dp).testTag("start_phase1_btn"),
            ) {
              Icon(Icons.Default.PlayArrow, contentDescription = null)
              Spacer(Modifier.width(6.dp))
              Text(strings.startPhase1Btn)
            }
          } else if (isRunning) {
            Button(
              onClick = { viewModel.pauseScan() },
              colors = ButtonDefaults.buttonColors(containerColor = StatusAmber),
              modifier = Modifier.weight(1f).height(50.dp).testTag("pause_scan_btn"),
            ) {
              Icon(Icons.Default.Pause, contentDescription = null)
              Spacer(Modifier.width(6.dp))
              Text(strings.pauseBtn)
            }
          } else {
            Button(
              onClick = { viewModel.resumeScan() },
              modifier = Modifier.weight(1f).height(50.dp).testTag("resume_scan_btn"),
            ) {
              Icon(Icons.Default.PlayArrow, contentDescription = null)
              Spacer(Modifier.width(6.dp))
              Text(strings.resumeBtn)
            }
          }

          if (isRunning || isPaused) {
            OutlinedButton(
              onClick = { viewModel.stopScan() },
              colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusRose),
              border = BorderStroke(1.dp, StatusRose),
              modifier = Modifier.height(50.dp).testTag("stop_scan_btn"),
            ) {
              Icon(Icons.Default.Stop, contentDescription = null)
              Spacer(Modifier.width(4.dp))
              Text(strings.stopBtn)
            }
          }

          if (liveState.activeSessionId != null && !isRunning) {
            OutlinedButton(
              onClick = { viewModel.clearResultsView() },
              modifier = Modifier.height(50.dp).testTag("clear_scan_btn"),
            ) {
              Icon(Icons.Default.DeleteSweep, contentDescription = strings.clearBtn)
            }
          }
        }
      }
    }

    // 5. Live Progress & Telemetry Card
    if (liveState.activeSessionId != null) {
      item {
        TelemetryCard {
          val isP2 =
            liveState.phase == ScanPhase.PHASE2_TUNNEL ||
              (liveState.totalPhase2Targets > 0 && liveState.phase == ScanPhase.COMPLETED)
          val total = if (isP2) liveState.totalPhase2Targets else liveState.totalPhase1Targets
          val done = if (isP2) liveState.processedPhase2 else liveState.processedPhase1
          val progress = if (total > 0) (done.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = if (isP2) strings.phase2Title else strings.phase1Title,
              style = MaterialTheme.typography.titleMedium,
            )
            LtrMonoText(
              text =
                if (liveState.currentSpeedIpsPerSec > 0f)
                  String.format(Locale.US, "%d/%d (%.1f/s)", done, total, liveState.currentSpeedIpsPerSec)
                else "$done/$total",
              color = MaterialTheme.colorScheme.primary,
            )
          }

          LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
          )

          if (liveState.ratioNote.isNotBlank()) {
            Text(
              text = liveState.ratioNote,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }

          if (liveState.possibleIspDisruptionWarning) {
            Surface(
              shape = RoundedCornerShape(10.dp),
              color = StatusAmber.copy(alpha = 0.14f),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Text(
                text = strings.ispDisruptionWarning,
                style = MaterialTheme.typography.bodySmall,
                color = StatusAmber,
                modifier = Modifier.padding(10.dp),
              )
            }
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            KpiMiniTile(
              label = "Tunnel",
              value = "${liveState.tunnelPassCount}",
              tone = BadgeTone.EMERALD,
              modifier = Modifier.weight(1f),
            )
            KpiMiniTile(
              label = "Phase1 OK",
              value = "${liveState.qualifiedPhase1Count}",
              tone = BadgeTone.SKY,
              modifier = Modifier.weight(1f),
            )
            KpiMiniTile(
              label = "Fail",
              value = "${liveState.failCount + liveState.tunnelFailCount}",
              tone = BadgeTone.ROSE,
              modifier = Modifier.weight(1f),
            )
            KpiMiniTile(
              label = "Skip",
              value = "${liveState.skippedCount + liveState.inconclusiveCount}",
              tone = BadgeTone.SLATE,
              modifier = Modifier.weight(1f),
            )
          }

          TextButton(
            onClick = onNavigateToResults,
            modifier = Modifier.align(Alignment.End),
          ) {
            Text(strings.tabResults)
          }
        }
      }
    }

    // 6. Phase 1 -> Phase 2 Tunnel Selection Card
    if (
      liveState.qualifiedPhase1Count > 0 &&
        !isRunning &&
        liveState.activeSessionId != null
    ) {
      item {
        TelemetryCard(borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)) {
          Text(
            text = strings.selectForTunnelTitle,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
          )
          Text(
            text =
              if (strings.isRtl)
                "تعداد ${liveState.qualifiedPhase1Count} IP مرحله اول را رد کرده‌اند (مرتب‌شده بر اساس سریع‌ترین WS/TLS/TCP)."
              else
                "${liveState.qualifiedPhase1Count} IPs passed Phase 1 (sorted by fastest WS/TLS/TCP).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )

          if (selectedManualPhase2.isNotEmpty()) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text =
                  if (strings.isRtl) "${selectedManualPhase2.size} IP به‌صورت دستی از نتایج انتخاب شده"
                  else "${selectedManualPhase2.size} IPs manually selected from Results",
                style = MaterialTheme.typography.bodySmall,
                color = StatusEmerald,
              )
              TextButton(onClick = { viewModel.clearPhase2IpSelection() }) {
                Text(strings.clearBtn)
              }
            }
          } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              FilterChip(
                selected = phase2Mode == Phase2SelectionMode.ALL,
                onClick = { phase2Mode = Phase2SelectionMode.ALL },
                label = { Text("${strings.allQualified} (${liveState.qualifiedPhase1Count})") },
              )
              FilterChip(
                selected = phase2Mode == Phase2SelectionMode.TOP_50,
                onClick = { phase2Mode = Phase2SelectionMode.TOP_50 },
                label = { Text("50") },
              )
              FilterChip(
                selected = phase2Mode == Phase2SelectionMode.TOP_100,
                onClick = { phase2Mode = Phase2SelectionMode.TOP_100 },
                label = { Text("100") },
              )
              FilterChip(
                selected = phase2Mode == Phase2SelectionMode.CUSTOM,
                onClick = {
                  phase2Mode = Phase2SelectionMode.CUSTOM
                  showPhase2CustomDialog = true
                },
                label = { Text("${strings.customCount}: $phase2CustomCount") },
              )
            }
          }

          Button(
            onClick = { viewModel.startPhase2TunnelScan(phase2Mode, phase2CustomCount) },
            modifier = Modifier.fillMaxWidth().height(50.dp).testTag("start_phase2_btn"),
            colors = ButtonDefaults.buttonColors(containerColor = StatusEmerald),
          ) {
            Icon(Icons.Default.RocketLaunch, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(strings.startPhase2Btn)
          }
        }
      }
    }
  }

  // Origin-Facing & Regional AWS Info Dialog
  if (showOriginDialog) {
    val snap = awsState.snapshot
    AlertDialog(
      onDismissRequest = { showOriginDialog = false },
      title = { Text(strings.awsOriginInfoButton) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            text =
              if (strings.isRtl)
                "در فایل رسمی AWS دو نوع رنج وجود دارد:\n• CLOUDFRONT + region=GLOBAL: سرورهای Edge کلادفرانت (فهرست اصلی اسکنر).\n• CLOUDFRONT_ORIGIN_FACING: رنج‌هایی که CloudFront برای اتصال به سرور مبدأ (Origin) استفاده می‌کند و هرگز نباید با فهرست Edge مخلوط شود."
              else
                "Official AWS ip-ranges.json separates:\n• CLOUDFRONT + region=GLOBAL: Edge server ranges (used for primary scan).\n• CLOUDFRONT_ORIGIN_FACING: CloudFront-to-Origin egress ranges, kept strictly separate.",
            style = MaterialTheme.typography.bodySmall,
          )
          if (snap != null) {
            LtrMonoText(
              text =
                "GLOBAL Edge: ${snap.globalEdgeV4.size} v4, ${snap.globalEdgeV6.size} v6\n" +
                  "Regional Edge: ${snap.regionalEdgeV4.size} v4, ${snap.regionalEdgeV6.size} v6\n" +
                  "ORIGIN_FACING: ${snap.originFacingV4.size} v4, ${snap.originFacingV6.size} v6",
              maxLines = 4,
            )
            if (snap.originFacingV4.isNotEmpty()) {
              LtrMonoText(
                text = snap.originFacingV4.take(5).joinToString("\n") { it.cidr },
                maxLines = 6,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      },
      confirmButton = {
        TextButton(onClick = { showOriginDialog = false }) { Text("OK") }
      },
    )
  }

  if (showCustomSampleDialog) {
    var tempText by remember { mutableStateOf(settings.autoSampleCount.toString()) }
    AlertDialog(
      onDismissRequest = { showCustomSampleDialog = false },
      title = { Text(strings.sampleCountLabel) },
      text = {
        OutlinedTextField(
          value = tempText,
          onValueChange = { tempText = it.filter { ch -> ch.isDigit() } },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          singleLine = true,
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            tempText.toIntOrNull()?.let { viewModel.setAutoSampleCount(it) }
            showCustomSampleDialog = false
          }
        ) { Text("OK") }
      },
      dismissButton = {
        TextButton(onClick = { showCustomSampleDialog = false }) { Text(strings.stopBtn) }
      },
    )
  }

  if (showPhase2CustomDialog) {
    var tempText by remember { mutableStateOf(phase2CustomCount.toString()) }
    AlertDialog(
      onDismissRequest = { showPhase2CustomDialog = false },
      title = { Text(strings.selectForTunnelTitle) },
      text = {
        OutlinedTextField(
          value = tempText,
          onValueChange = { tempText = it.filter { ch -> ch.isDigit() } },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          singleLine = true,
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            tempText.toIntOrNull()?.let { phase2CustomCount = it.coerceAtLeast(1) }
            showPhase2CustomDialog = false
          }
        ) { Text("OK") }
      },
      dismissButton = {
        TextButton(onClick = { showPhase2CustomDialog = false }) { Text(strings.stopBtn) }
      },
    )
  }
}
