package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.ScanLogEntity
import com.example.ui.LogFilterType
import com.example.ui.MainViewModel
import com.example.ui.components.BadgeTone
import com.example.ui.components.LtrMonoText
import com.example.ui.components.StatusPill
import com.example.ui.components.TelemetryCard
import com.example.ui.i18n.LocalAppStrings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LogsScreen(viewModel: MainViewModel) {
  val strings = LocalAppStrings.current
  val logs by viewModel.filteredLogs.collectAsStateWithLifecycle()
  val activeFilter by viewModel.logFilter.collectAsStateWithLifecycle()

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    item {
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
          LogFilterType.ALL to strings.filterAll,
          LogFilterType.PASS to strings.filterPass,
          LogFilterType.FAIL to strings.filterFail,
          LogFilterType.SKIP to strings.filterSkip,
        ).forEach { (filter, label) ->
          FilterChip(
            selected = activeFilter == filter,
            onClick = { viewModel.setLogFilter(filter) },
            label = { Text(label) },
          )
        }
      }
    }

    if (logs.isEmpty()) {
      item {
        TelemetryCard {
          Text(
            text = strings.logsEmpty,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    } else {
      items(logs, key = { it.id }) { log ->
        LogRowCard(log = log, isRtl = strings.isRtl)
      }
    }
  }
}

@Composable
private fun LogRowCard(log: ScanLogEntity, isRtl: Boolean) {
  val timeStr =
    remember(log.timestampMillis) {
      SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(log.timestampMillis))
    }
  val tone =
    when (log.level) {
      "PASS" -> BadgeTone.EMERALD
      "FAIL" -> BadgeTone.ROSE
      "SKIP" -> BadgeTone.AMBER
      else -> BadgeTone.SKY
    }

  TelemetryCard {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      LtrMonoText(
        text = "[$timeStr] ${log.stage} | ${log.ip}",
        modifier = Modifier.weight(1f).padding(end = 6.dp),
      )
      StatusPill(label = log.eventCode, tone = tone)
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = if (isRtl) log.messageFa else log.messageEn,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.weight(1f),
      )
      if (log.durationMs != null) {
        LtrMonoText(
          text = "${log.durationMs}ms",
          color = MaterialTheme.colorScheme.primary,
        )
      }
    }
  }
}
