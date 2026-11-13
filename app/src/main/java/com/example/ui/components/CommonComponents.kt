package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusAmberBg
import com.example.ui.theme.StatusEmerald
import com.example.ui.theme.StatusEmeraldBg
import com.example.ui.theme.StatusRose
import com.example.ui.theme.StatusRoseBg
import com.example.ui.theme.StatusSky
import com.example.ui.theme.StatusSkyBg
import com.example.ui.theme.StatusSlate
import com.example.ui.theme.StatusSlateBg

/** Forces LTR rendering for IP addresses, CIDRs, URLs, and monospace metrics inside Persian RTL screens. */
@Composable
fun LtrMonoText(
  text: String,
  modifier: Modifier = Modifier,
  color: Color = MaterialTheme.colorScheme.onSurface,
  fontWeight: FontWeight = FontWeight.Medium,
  maxLines: Int = 1,
) {
  CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
    Text(
      text = text,
      modifier = modifier,
      color = color,
      fontFamily = JetBrainsMonoFamily,
      fontWeight = fontWeight,
      style = MaterialTheme.typography.bodyMedium,
      maxLines = maxLines,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
fun TelemetryCard(
  modifier: Modifier = Modifier,
  borderColor: Color = MaterialTheme.colorScheme.outline.copy(alpha = 0.65f),
  content: @Composable ColumnScope.() -> Unit,
) {
  Card(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, borderColor),
    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
      content = content,
    )
  }
}

enum class BadgeTone {
  EMERALD,
  ROSE,
  AMBER,
  SKY,
  SLATE,
}

@Composable
fun StatusPill(
  label: String,
  tone: BadgeTone,
  modifier: Modifier = Modifier,
) {
  val (fg, bg) =
    when (tone) {
      BadgeTone.EMERALD -> StatusEmerald to StatusEmeraldBg
      BadgeTone.ROSE -> StatusRose to StatusRoseBg
      BadgeTone.AMBER -> StatusAmber to StatusAmberBg
      BadgeTone.SKY -> StatusSky to StatusSkyBg
      BadgeTone.SLATE -> StatusSlate to StatusSlateBg
    }
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(8.dp),
    color = bg,
    border = BorderStroke(1.dp, fg.copy(alpha = 0.35f)),
  ) {
    Text(
      text = label,
      color = fg,
      style = MaterialTheme.typography.labelSmall,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
fun KpiMiniTile(
  label: String,
  value: String,
  tone: BadgeTone,
  modifier: Modifier = Modifier,
) {
  val (fg, bg) =
    when (tone) {
      BadgeTone.EMERALD -> StatusEmerald to StatusEmeraldBg
      BadgeTone.ROSE -> StatusRose to StatusRoseBg
      BadgeTone.AMBER -> StatusAmber to StatusAmberBg
      BadgeTone.SKY -> StatusSky to StatusSkyBg
      BadgeTone.SLATE -> StatusSlate to StatusSlateBg
    }
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(12.dp),
    color = bg,
    border = BorderStroke(1.dp, fg.copy(alpha = 0.28f)),
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
        text = value,
        color = fg,
        fontFamily = JetBrainsMonoFamily,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.titleMedium,
      )
      Text(
        text = label,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
fun LabeledMetricRow(
  label: String,
  value: String,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    LtrMonoText(text = value)
  }
}
