package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.ExportFormat
import com.example.data.local.ScanResultEntity
import com.example.domain.model.OverallIpStatus
import com.example.ui.MainViewModel
import com.example.ui.ResultSortBy
import com.example.ui.ResultStatusFilter
import com.example.ui.components.BadgeTone
import com.example.ui.components.KpiMiniTile
import com.example.ui.components.LtrMonoText
import com.example.ui.components.StatusPill
import com.example.ui.components.TelemetryCard
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusEmerald
import com.example.ui.theme.StatusRose
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ResultsScreen(viewModel: MainViewModel) {
  val strings = LocalAppStrings.current
  val context = LocalContext.current
  val clipboardManager = LocalClipboardManager.current

  val allResults by viewModel.activeResults.collectAsStateWithLifecycle()
  val filteredResults by viewModel.filteredSortedResults.collectAsStateWithLifecycle()
  val searchQuery by viewModel.resultSearchQuery.collectAsStateWithLifecycle()
  val statusFilter by viewModel.resultStatusFilter.collectAsStateWithLifecycle()
  val ipFamilyFilter by viewModel.resultIpFamilyFilter.collectAsStateWithLifecycle()
  val sortBy by viewModel.resultSortBy.collectAsStateWithLifecycle()
  val displayLimit by viewModel.resultsDisplayLimit.collectAsStateWithLifecycle()
  val selectedPhase2Ips by viewModel.selectedPhase2Ips.collectAsStateWithLifecycle()

  var showExportMenu by remember { mutableStateOf(false) }
  var showShareMenu by remember { mutableStateOf(false) }
  var showSortMenu by remember { mutableStateOf(false) }
  var showCustomLimitDialog by remember { mutableStateOf(false) }
  var pendingExportFormat by remember { mutableStateOf(ExportFormat.CSV) }

  val safExportLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
      uri?.let { viewModel.exportToSaf(it, pendingExportFormat) }
    }

  val tunnelPassCount = allResults.count { it.tunnelStatus == OverallIpStatus.TUNNEL_PASS.name }
  val tunnelFailCount = allResults.count { it.tunnelStatus == OverallIpStatus.TUNNEL_FAIL.name }
  val inconclusiveCount = allResults.count { it.overallStatus == OverallIpStatus.INCONCLUSIVE.name }
  val skippedCount = allResults.count { it.overallStatus == OverallIpStatus.SKIPPED.name }
  val phase1OnlyCount =
    allResults.count {
      it.phase1Qualified && it.tunnelStatus != OverallIpStatus.TUNNEL_PASS.name
    }

  val visibleItems = filteredResults.take(displayLimit)

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    // Summary strip separating Tunnel Pass from Phase 1-only results
    item {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        KpiMiniTile(
          label = "Tunnel Pass",
          value = "$tunnelPassCount",
          tone = BadgeTone.EMERALD,
          modifier = Modifier.weight(1f),
        )
        KpiMiniTile(
          label = "Phase1 Only",
          value = "$phase1OnlyCount",
          tone = BadgeTone.SKY,
          modifier = Modifier.weight(1f),
        )
        KpiMiniTile(
          label = "Tunnel Fail",
          value = "$tunnelFailCount",
          tone = BadgeTone.ROSE,
          modifier = Modifier.weight(1f),
        )
        KpiMiniTile(
          label = "Inconcl/Skip",
          value = "${inconclusiveCount + skippedCount}",
          tone = BadgeTone.AMBER,
          modifier = Modifier.weight(1f),
        )
      }
    }

    // Search + Filter + Export Controls
    item {
      TelemetryCard {
        OutlinedTextField(
          value = searchQuery,
          onValueChange = { viewModel.setResultSearchQuery(it) },
          leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
          placeholder = { Text(strings.searchIpHint) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("results_search_input"),
        )

        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          listOf(
            ResultStatusFilter.ALL to strings.filterAll,
            ResultStatusFilter.TUNNEL_PASS to "Tunnel Pass",
            ResultStatusFilter.PHASE1_PASS_ONLY to "Phase1 Only",
            ResultStatusFilter.WS_PASS to "WS 101",
            ResultStatusFilter.TLS_PASS to "TLS Pass",
            ResultStatusFilter.TCP_PASS to "TCP Pass",
            ResultStatusFilter.TUNNEL_FAIL to "Tunnel Fail",
            ResultStatusFilter.INCONCLUSIVE to "Inconclusive",
            ResultStatusFilter.SKIPPED to "Skipped",
          ).forEach { (filter, label) ->
            FilterChip(
              selected = statusFilter == filter,
              onClick = { viewModel.setResultStatusFilter(filter) },
              label = { Text(label) },
            )
          }
        }

        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          FilterChip(
            selected = ipFamilyFilter == null,
            onClick = { viewModel.setResultIpFamilyFilter(null) },
            label = { Text("IPv4 + IPv6") },
          )
          FilterChip(
            selected = ipFamilyFilter == "IPV4",
            onClick = { viewModel.setResultIpFamilyFilter("IPV4") },
            label = { Text("IPv4") },
          )
          FilterChip(
            selected = ipFamilyFilter == "IPV6",
            onClick = { viewModel.setResultIpFamilyFilter("IPV6") },
            label = { Text("IPv6") },
          )

          BoxDropdownButton(
            label =
              when (sortBy) {
                ResultSortBy.RANK_DEFAULT -> if (strings.isRtl) "مرتب‌سازی: رتبه" else "Sort: Rank"
                ResultSortBy.TUNNEL_LATENCY -> "Tunnel ms"
                ResultSortBy.STABILITY -> if (strings.isRtl) "پایداری" else "Stability"
                ResultSortBy.WS_LATENCY -> "WS ms"
                ResultSortBy.TLS_LATENCY -> "TLS ms"
                ResultSortBy.TCP_LATENCY -> "TCP ms"
              },
            expanded = showSortMenu,
            onExpandChange = { showSortMenu = it },
          ) {
            ResultSortBy.entries.forEach { opt ->
              DropdownMenuItem(
                text = { Text(opt.name) },
                onClick = {
                  viewModel.setResultSortBy(opt)
                  showSortMenu = false
                },
              )
            }
          }
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          OutlinedButton(
            onClick = {
              val joined = filteredResults.joinToString("\n") { it.ip }
              if (joined.isNotBlank()) clipboardManager.setText(AnnotatedString(joined))
            },
            enabled = filteredResults.isNotEmpty(),
            modifier = Modifier.weight(1f).testTag("copy_filtered_ips_btn"),
          ) {
            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(strings.copyAllFiltered, maxLines = 1)
          }

          androidx.compose.foundation.layout.Box {
            OutlinedButton(
              onClick = { showExportMenu = true },
              enabled = allResults.isNotEmpty(),
              modifier = Modifier.testTag("export_results_btn"),
            ) {
              Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(4.dp))
              Text(strings.exportBtn)
            }
            DropdownMenu(expanded = showExportMenu, onDismissRequest = { showExportMenu = false }) {
              ExportFormat.entries.forEach { fmt ->
                DropdownMenuItem(
                  text = { Text(fmt.extension.uppercase()) },
                  onClick = {
                    showExportMenu = false
                    pendingExportFormat = fmt
                    safExportLauncher.launch("cloudfront_scan.${fmt.extension}")
                  },
                )
              }
            }
          }

          androidx.compose.foundation.layout.Box {
            OutlinedButton(
              onClick = { showShareMenu = true },
              enabled = allResults.isNotEmpty(),
              modifier = Modifier.testTag("share_results_btn"),
            ) {
              Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
            }
            DropdownMenu(expanded = showShareMenu, onDismissRequest = { showShareMenu = false }) {
              ExportFormat.entries.forEach { fmt ->
                DropdownMenuItem(
                  text = { Text("${strings.shareBtn} ${fmt.extension.uppercase()}") },
                  onClick = {
                    showShareMenu = false
                    viewModel.createShareIntent(fmt) { intent -> context.startActivity(intent) }
                  },
                )
              }
            }
          }
        }
      }
    }

    if (visibleItems.isEmpty()) {
      item {
        TelemetryCard {
          Text(
            text = strings.resultsEmpty,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    } else {
      items(visibleItems, key = { it.ip }) { item ->
        ResultIpCard(
          item = item,
          isRtl = strings.isRtl,
          isSelectedForPhase2 = item.ip in selectedPhase2Ips,
          onToggleSelect = { viewModel.togglePhase2IpSelection(item.ip) },
          onCopyIp = { clipboardManager.setText(AnnotatedString(item.ip)) },
        )
      }

      // Top-100 / Next-100 / Show All / Custom Pagination Controls
      if (filteredResults.size > visibleItems.size || displayLimit != 100) {
        item {
          TelemetryCard {
            Text(
              text =
                if (strings.isRtl)
                  "نمایش ${visibleItems.size} از ${filteredResults.size} نتیجه"
                else "Showing ${visibleItems.size} of ${filteredResults.size} results",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              if (visibleItems.size < filteredResults.size) {
                OutlinedButton(
                  onClick = { viewModel.showNext100Results() },
                  modifier = Modifier.testTag("next_100_btn"),
                ) {
                  Text(strings.showNext100)
                }
                OutlinedButton(
                  onClick = { viewModel.showAllResults() },
                  modifier = Modifier.testTag("show_all_results_btn"),
                ) {
                  Text(strings.showAll)
                }
              }
              OutlinedButton(
                onClick = { showCustomLimitDialog = true },
                modifier = Modifier.testTag("custom_limit_btn"),
              ) {
                Text(strings.customCount)
              }
            }
          }
        }
      }
    }
  }

  if (showCustomLimitDialog) {
    var limitInput by remember {
      mutableStateOf(if (displayLimit == Int.MAX_VALUE) filteredResults.size.toString() else displayLimit.toString())
    }
    AlertDialog(
      onDismissRequest = { showCustomLimitDialog = false },
      title = { Text(strings.customCount) },
      text = {
        OutlinedTextField(
          value = limitInput,
          onValueChange = { limitInput = it.filter { ch -> ch.isDigit() } },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          singleLine = true,
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            limitInput.toIntOrNull()?.let { viewModel.setCustomDisplayLimit(it) }
            showCustomLimitDialog = false
          }
        ) { Text("OK") }
      },
      dismissButton = {
        TextButton(onClick = { showCustomLimitDialog = false }) { Text(strings.stopBtn) }
      },
    )
  }
}

@Composable
private fun BoxDropdownButton(
  label: String,
  expanded: Boolean,
  onExpandChange: (Boolean) -> Unit,
  menuContent: @Composable () -> Unit,
) {
  androidx.compose.foundation.layout.Box {
    FilterChip(selected = false, onClick = { onExpandChange(true) }, label = { Text(label) })
    DropdownMenu(expanded = expanded, onDismissRequest = { onExpandChange(false) }) {
      menuContent()
    }
  }
}

@Composable
private fun ResultIpCard(
  item: ScanResultEntity,
  isRtl: Boolean,
  isSelectedForPhase2: Boolean,
  onToggleSelect: () -> Unit,
  onCopyIp: () -> Unit,
) {
  val statusTone =
    when (item.overallStatus) {
      OverallIpStatus.TUNNEL_PASS.name -> BadgeTone.EMERALD
      OverallIpStatus.TUNNEL_FAIL.name, OverallIpStatus.PHASE1_FAIL.name -> BadgeTone.ROSE
      OverallIpStatus.WS_PASS.name, OverallIpStatus.TLS_PASS.name, OverallIpStatus.TCP_PASS.name ->
        BadgeTone.SKY
      OverallIpStatus.INCONCLUSIVE.name -> BadgeTone.AMBER
      else -> BadgeTone.SLATE
    }

  TelemetryCard(
    borderColor =
      if (item.overallStatus == OverallIpStatus.TUNNEL_PASS.name)
        StatusEmerald.copy(alpha = 0.65f)
      else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.weight(1f),
      ) {
        if (item.phase1Qualified) {
          Checkbox(checked = isSelectedForPhase2, onCheckedChange = { onToggleSelect() })
        }
        Column {
          LtrMonoText(text = item.ip, fontWeight = FontWeight.Bold)
          LtrMonoText(
            text = "${item.ipVersion} | :${item.port} | SNI: ${item.sniUsed.ifBlank { "-" }}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        StatusPill(label = item.overallStatus, tone = statusTone)
        IconButton(onClick = onCopyIp) {
          Icon(
            Icons.Default.ContentCopy,
            contentDescription = "Copy IP",
            modifier = Modifier.size(18.dp),
          )
        }
      }
    }

    // Separate Stage Metrics Row
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      LtrMonoText(
        text = "TCP:${item.tcpMs?.let { "${it}ms" } ?: item.tcpStatus.take(4)}",
        modifier = Modifier.weight(1f),
      )
      LtrMonoText(
        text = "TLS:${item.tlsMs?.let { "${it}ms" } ?: item.tlsStatus.take(4)}",
        modifier = Modifier.weight(1f),
      )
      LtrMonoText(
        text = "WS:${item.wsMs?.let { "${it}ms" } ?: item.wsHttpCode?.toString() ?: item.wsStatus.take(4)}",
        modifier = Modifier.weight(1f),
      )
    }

    if (item.tunnelAttempts > 0) {
      val stabStr = String.format(Locale.US, "%.0f%%", item.stabilityScore)
      LtrMonoText(
        text =
          "Tunnel: ${item.tunnelSuccesses}/${item.tunnelAttempts} ($stabStr) | Median: ${item.tunnelMedianMs ?: "-"}ms" +
            (item.downloadKbps?.let { String.format(Locale.US, " | %.0f Kbps", it) } ?: ""),
        color =
          if (item.tunnelStatus == OverallIpStatus.TUNNEL_PASS.name) StatusEmerald
          else StatusRose,
      )
    }

    val reason = if (isRtl) item.detailMessageFa else item.detailMessageEn
    if (reason.isNotBlank()) {
      Text(
        text = reason,
        style = MaterialTheme.typography.bodySmall,
        color =
          when (statusTone) {
            BadgeTone.ROSE -> StatusRose
            BadgeTone.AMBER -> StatusAmber
            else -> MaterialTheme.colorScheme.onSurfaceVariant
          },
      )
    }
  }
}
