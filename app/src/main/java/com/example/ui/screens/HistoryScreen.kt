package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.ScanSessionEntity
import com.example.ui.MainViewModel
import com.example.ui.components.BadgeTone
import com.example.ui.components.LtrMonoText
import com.example.ui.components.StatusPill
import com.example.ui.components.TelemetryCard
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.theme.StatusRose
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
  viewModel: MainViewModel,
  onOpenResults: () -> Unit,
) {
  val strings = LocalAppStrings.current
  val sessions by viewModel.historySessions.collectAsStateWithLifecycle()

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    if (sessions.isEmpty()) {
      item {
        TelemetryCard {
          Text(
            text = strings.historyEmpty,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    } else {
      items(sessions, key = { it.sessionId }) { session ->
        HistorySessionCard(
          session = session,
          isRtl = strings.isRtl,
          loadLabel = strings.loadSession,
          deleteLabel = strings.deleteSession,
          onLoad = {
            viewModel.loadHistorySession(session.sessionId)
            onOpenResults()
          },
          onDelete = { viewModel.deleteHistorySession(session.sessionId) },
        )
      }
    }
  }
}

@Composable
private fun HistorySessionCard(
  session: ScanSessionEntity,
  isRtl: Boolean,
  loadLabel: String,
  deleteLabel: String,
  onLoad: () -> Unit,
  onDelete: () -> Unit,
) {
  val dateStr =
    remember(session.createdAtMillis) {
      SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(session.createdAtMillis))
    }
  TelemetryCard {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      LtrMonoText(text = "$dateStr (${session.scanMode})", fontWeight = FontWeight.Bold)
      StatusPill(
        label = session.phase,
        tone = if (session.tunnelPassCount > 0) BadgeTone.EMERALD else BadgeTone.SKY,
      )
    }

    LtrMonoText(
      text =
        "Inputs: ${session.validInputs} (dup-${session.duplicatesRemoved}) | " +
          "Tunnel Pass: ${session.tunnelPassCount} | Fail: ${session.tunnelFailCount}",
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (session.awsSyncToken.isNotBlank()) {
      LtrMonoText(
        text = "AWS syncToken: ${session.awsSyncToken} | ${session.awsCreateDate}",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    if (session.vlessRedactedSummary.isNotBlank()) {
      LtrMonoText(
        text = session.vlessRedactedSummary,
        maxLines = 2,
        color = MaterialTheme.colorScheme.primary,
      )
    }

    if (session.ratioNote.isNotBlank()) {
      Text(
        text = session.ratioNote,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Button(onClick = onLoad) {
        Icon(Icons.Default.Visibility, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text(loadLabel)
      }
      IconButton(onClick = onDelete) {
        Icon(Icons.Default.Delete, contentDescription = deleteLabel, tint = StatusRose)
      }
    }
  }
}
