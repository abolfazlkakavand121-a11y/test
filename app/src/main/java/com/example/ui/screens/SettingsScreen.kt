package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.model.AppLanguage
import com.example.domain.model.ScanPreset
import com.example.domain.model.ThemeMode
import com.example.domain.model.TunnelTestUrl
import com.example.ui.MainViewModel
import com.example.ui.components.LtrMonoText
import com.example.ui.components.TelemetryCard
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusRose
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel) {
  val strings = LocalAppStrings.current
  val settings by viewModel.settings.collectAsStateWithLifecycle()
  var showAddUrlDialog by remember { mutableStateOf(false) }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    // 1. Language & Theme
    item {
      TelemetryCard {
        Text(text = strings.settingsLanguage, style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          FilterChip(
            selected = settings.language == AppLanguage.FA,
            onClick = { viewModel.setLanguage(AppLanguage.FA) },
            label = { Text("فارسی (RTL)") },
            modifier = Modifier.testTag("lang_fa_chip"),
          )
          FilterChip(
            selected = settings.language == AppLanguage.EN,
            onClick = { viewModel.setLanguage(AppLanguage.EN) },
            label = { Text("English (LTR)") },
            modifier = Modifier.testTag("lang_en_chip"),
          )
        }

        Text(text = strings.settingsTheme, style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          FilterChip(
            selected = settings.themeMode == ThemeMode.SYSTEM,
            onClick = { viewModel.setThemeMode(ThemeMode.SYSTEM) },
            label = { Text(strings.themeSystem) },
          )
          FilterChip(
            selected = settings.themeMode == ThemeMode.DARK,
            onClick = { viewModel.setThemeMode(ThemeMode.DARK) },
            label = { Text(strings.themeDark) },
          )
          FilterChip(
            selected = settings.themeMode == ThemeMode.LIGHT,
            onClick = { viewModel.setThemeMode(ThemeMode.LIGHT) },
            label = { Text(strings.themeLight) },
          )
        }

        Text(
          text = if (strings.isRtl) "حالت پیش‌فرض اسکن" else "Scan Preset",
          style = MaterialTheme.typography.titleMedium,
        )
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
            )
          }
        }
      }
    }

    // 2. Phase 1 Probe Settings
    item {
      TelemetryCard {
        Text(text = strings.settingsPhase1Group, style = MaterialTheme.typography.titleMedium)

        NumericSettingRow(
          label = if (strings.isRtl) "پورت پیش‌فرض (در نبود VLESS)" else "Default Port (no VLESS)",
          value = settings.defaultPort,
          onValueChange = { v ->
            viewModel.updateSettings { it.copy(defaultPort = v.coerceIn(1, 65535)) }
          },
        )
        NumericSettingRow(
          label = if (strings.isRtl) "تعداد Worker همزمان مرحله اول" else "Phase 1 Concurrent Workers",
          value = settings.phase1Workers,
          onValueChange = { v ->
            viewModel.updateSettings { it.copy(phase1Workers = v.coerceIn(1, 32)) }
          },
        )
        if (settings.phase1Workers > 16) {
          Text(
            text =
              if (strings.isRtl)
                "هشدار: تعداد Worker بالای ۱۶ ممکن است باعث داغ شدن گوشی یا محدودیت شبکه شود."
              else "Warning: >16 workers may increase battery heat or trigger ISP rate limits.",
            style = MaterialTheme.typography.bodySmall,
            color = StatusAmber,
          )
        }
        NumericSettingRow(
          label = if (strings.isRtl) "تکرار تلاش مرحله اول" else "Phase 1 Retries",
          value = settings.phase1Retries,
          onValueChange = { v ->
            viewModel.updateSettings { it.copy(phase1Retries = v.coerceIn(1, 3)) }
          },
        )
        NumericSettingRow(
          label = if (strings.isRtl) "Timeout اتصال TCP (ms)" else "TCP Timeout (ms)",
          value = settings.tcpTimeoutMs,
          onValueChange = { v ->
            viewModel.updateSettings { it.copy(tcpTimeoutMs = v.coerceIn(500, 15000)) }
          },
        )
        NumericSettingRow(
          label = if (strings.isRtl) "Timeout دست‌تکانی TLS (ms)" else "TLS Timeout (ms)",
          value = settings.tlsTimeoutMs,
          onValueChange = { v ->
            viewModel.updateSettings { it.copy(tlsTimeoutMs = v.coerceIn(500, 20000)) }
          },
        )
        NumericSettingRow(
          label = if (strings.isRtl) "Timeout وب‌سوکت (ms)" else "WebSocket Timeout (ms)",
          value = settings.wsTimeoutMs,
          onValueChange = { v ->
            viewModel.updateSettings { it.copy(wsTimeoutMs = v.coerceIn(500, 20000)) }
          },
        )

        SwitchSettingRow(
          label =
            if (strings.isRtl) "پینگ اختیاری ICMP (فقط در صورت پشتیبانی سیستم بدون Root)"
            else "Optional ICMP Ping (only if non-root ping works)",
          checked = settings.enableOptionalIcmpPing,
          onCheckedChange = { c ->
            viewModel.updateSettings { it.copy(enableOptionalIcmpPing = c) }
          },
        )
        SwitchSettingRow(
          label =
            if (strings.isRtl)
              "حالت تشخیصی TLS ناامن (نادیده گرفتن خطای گواهی؛ جدا از نتیجه معتبر)"
            else "Insecure TLS Diagnostic Mode (separately tagged, not counted as verified PASS)",
          checked = settings.allowInsecureTlsDiagnostic,
          onCheckedChange = { c ->
            viewModel.updateSettings { it.copy(allowInsecureTlsDiagnostic = c) }
          },
        )
      }
    }

    // 3. Phase 2 Xray Tunnel & Test URLs
    item {
      TelemetryCard {
        Text(text = strings.settingsPhase2Group, style = MaterialTheme.typography.titleMedium)

        NumericSettingRow(
          label = if (strings.isRtl) "Timeout راه‌اندازی Xray (ms)" else "Xray Startup Timeout (ms)",
          value = settings.xrayStartTimeoutMs,
          onValueChange = { v ->
            viewModel.updateSettings { it.copy(xrayStartTimeoutMs = v.coerceIn(1500, 15000)) }
          },
        )
        NumericSettingRow(
          label = if (strings.isRtl) "تعداد تکرار تست واقعی تونل (Median)" else "Tunnel Test Repetitions",
          value = settings.tunnelRepetitions,
          onValueChange = { v ->
            viewModel.updateSettings { it.copy(tunnelRepetitions = v.coerceIn(1, 5)) }
          },
        )
        SwitchSettingRow(
          label =
            if (strings.isRtl) "بررسی اختیاری External IP از داخل تونل (غیرمسدودکننده)"
            else "Optional External IP Check through tunnel (non-blocking)",
          checked = settings.enableExternalIpCheck,
          onCheckedChange = { c ->
            viewModel.updateSettings { it.copy(enableExternalIpCheck = c) }
          },
        )

        Text(
          text = if (strings.isRtl) "آدرس‌های تست تونل (URL):" else "Tunnel Test URLs:",
          style = MaterialTheme.typography.labelLarge,
        )

        if (settings.tunnelTestUrls.any { it.enabled && it.isPlainHttp }) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Icon(Icons.Default.WarningAmber, contentDescription = null, tint = StatusAmber)
            Text(
              text = strings.plainHttpWarning,
              style = MaterialTheme.typography.bodySmall,
              color = StatusAmber,
            )
          }
        }

        settings.tunnelTestUrls.forEach { tUrl ->
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              LtrMonoText(text = tUrl.url, fontWeight = FontWeight.SemiBold)
              LtrMonoText(
                text =
                  "expect=${tUrl.expectedStatusCode} | timeout=${tUrl.timeoutMs}ms" +
                    if (tUrl.expectedBodyContains.isNotBlank()) " | contains='${tUrl.expectedBodyContains}'"
                    else "",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Switch(
              checked = tUrl.enabled,
              onCheckedChange = { viewModel.toggleTunnelTestUrl(tUrl.id) },
            )
            if (settings.tunnelTestUrls.size > 1) {
              IconButton(onClick = { viewModel.deleteTunnelTestUrl(tUrl.id) }) {
                Icon(Icons.Default.Delete, contentDescription = "Delete URL", tint = StatusRose)
              }
            }
          }
        }

        OutlinedButton(
          onClick = { showAddUrlDialog = true },
          modifier = Modifier.fillMaxWidth().testTag("add_tunnel_url_btn"),
        ) {
          Icon(Icons.Default.Add, contentDescription = null)
          Spacer(Modifier.width(6.dp))
          Text(strings.addTunnelUrlBtn)
        }
      }
    }

    // 4. About Xray Core & Licenses
    item {
      TelemetryCard {
        Text(text = strings.settingsAboutGroup, style = MaterialTheme.typography.titleMedium)
        LtrMonoText(text = "Xray Core / Libv2ray: ${viewModel.xrayVersionText}", maxLines = 2)
        Text(
          text =
            if (strings.isRtl)
              "این برنامه کاملاً مستقل و آفلاین-محور است؛ هیچ حساب کاربری، سرور واسط یا ارسال تله‌متری ندارد. تمام درخواست‌های تست تونل مستقیماً از پورت محلی SOCKS5 موتور Xray (بدون Fallback مستقیم) عبور می‌کنند."
            else
              "Completely standalone with zero accounts, backend servers, or telemetry. All Phase 2 tunnel requests route strictly through Xray's local loopback SOCKS5 inbound with zero direct fallback.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          text =
            "Licenses: AndroidLibXrayLite (LGPL-3.0), Xray-core (MPL-2.0), AndroidX / OkHttp / CameraX / ML Kit (Apache-2.0).",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }

  if (showAddUrlDialog) {
    var urlInput by remember { mutableStateOf("https://") }
    var statusInput by remember { mutableStateOf("204") }
    var containsInput by remember { mutableStateOf("") }
    var timeoutInput by remember { mutableStateOf("8000") }

    AlertDialog(
      onDismissRequest = { showAddUrlDialog = false },
      title = { Text(strings.addTunnelUrlBtn) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(
            value = urlInput,
            onValueChange = { urlInput = it },
            label = { Text("URL (HTTPS recommended)") },
            singleLine = true,
          )
          OutlinedTextField(
            value = statusInput,
            onValueChange = { statusInput = it.filter { c -> c.isDigit() } },
            label = { Text("Expected HTTP Status") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
          )
          OutlinedTextField(
            value = containsInput,
            onValueChange = { containsInput = it },
            label = { Text("Expected Body Contains (optional)") },
            singleLine = true,
          )
          OutlinedTextField(
            value = timeoutInput,
            onValueChange = { timeoutInput = it.filter { c -> c.isDigit() } },
            label = { Text("Timeout (ms)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
          )
        }
      },
      confirmButton = {
        TextButton(
          onClick = {
            if (urlInput.startsWith("http://", true) || urlInput.startsWith("https://", true)) {
              viewModel.addTunnelTestUrl(
                TunnelTestUrl(
                  id = UUID.randomUUID().toString().take(8),
                  url = urlInput.trim(),
                  expectedStatusCode = statusInput.toIntOrNull() ?: 200,
                  expectedBodyContains = containsInput.trim(),
                  timeoutMs = (timeoutInput.toIntOrNull() ?: 8000).coerceIn(1000, 30000),
                  enabled = true,
                )
              )
              showAddUrlDialog = false
            }
          }
        ) { Text("OK") }
      },
      dismissButton = {
        TextButton(onClick = { showAddUrlDialog = false }) { Text(strings.stopBtn) }
      },
    )
  }
}

@Composable
private fun NumericSettingRow(
  label: String,
  value: Int,
  onValueChange: (Int) -> Unit,
) {
  var text by remember(value) { mutableStateOf(value.toString()) }
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodyMedium,
      modifier = Modifier.weight(1f),
    )
    OutlinedTextField(
      value = text,
      onValueChange = { raw ->
        val filtered = raw.filter { it.isDigit() }
        text = filtered
        filtered.toIntOrNull()?.let(onValueChange)
      },
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
      singleLine = true,
      modifier = Modifier.width(104.dp),
    )
  }
}

@Composable
private fun SwitchSettingRow(
  label: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodyMedium,
      modifier = Modifier.weight(1f),
    )
    Switch(checked = checked, onCheckedChange = onCheckedChange)
  }
}
