package com.example.data.local

import android.content.Context
import android.net.Uri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class ExportFormat(val extension: String, val mimeType: String) {
  TXT("txt", "text/plain"),
  CSV("csv", "text/csv"),
  JSON("json", "application/json"),
}

object ReportExporter {

  private val uuidRegex =
    Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

  fun redactSensitiveText(text: String): String {
    return uuidRegex.replace(text) { m ->
      val v = m.value
      "${v.take(8)}-****-****-****-${v.takeLast(4)}"
    }
  }

  private fun formatTime(millis: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)
    return sdf.format(Date(millis))
  }

  fun generateTxtReport(
    session: ScanSessionEntity,
    results: List<ScanResultEntity>,
  ): String {
    val sb = StringBuilder()
    sb.appendLine("====================================================")
    sb.appendLine("CloudFront Scanner Report")
    sb.appendLine("====================================================")
    sb.appendLine("Session ID: ${session.sessionId}")
    sb.appendLine("Scan Date: ${formatTime(session.createdAtMillis)}")
    sb.appendLine("Mode: ${session.scanMode} | Family: ${session.ipFamilyPreference} | Preset: ${session.scanPreset}")
    sb.appendLine("AWS Source syncToken: ${session.awsSyncToken.ifBlank { "N/A" }}")
    sb.appendLine("AWS Source createDate: ${session.awsCreateDate.ifBlank { "N/A" }}")
    sb.appendLine("AWS From Cache: ${session.awsIsFromCache}")
    sb.appendLine("VLESS Summary (Redacted): ${redactSensitiveText(session.vlessRedactedSummary.ifBlank { "None (Phase 1 only)" })}")
    if (session.ratioNote.isNotBlank()) sb.appendLine("Sampling Ratio: ${session.ratioNote}")
    sb.appendLine("Inputs: total=${session.totalInputs}, valid=${session.validInputs}, dupRemoved=${session.duplicatesRemoved}, invalid=${session.invalidInputsCount}")
    sb.appendLine("Phase 1: tcpPass=${session.tcpPassCount}, tlsPass=${session.tlsPassCount}, wsPass=${session.wsPassCount}, skipped=${session.skippedCount}")
    sb.appendLine("Phase 2: tunnelPass=${session.tunnelPassCount}, tunnelFail=${session.tunnelFailCount}, inconclusive=${session.inconclusiveCount}")
    sb.appendLine("----------------------------------------------------")
    sb.appendLine("IP\tVersion\tStatus\tPort\tTCP(ms)\tTLS(ms)\tWS(ms)\tTunnelMed(ms)\tSuccesses\tReason")
    for (r in results) {
      sb.append(r.ip).append('\t')
      sb.append(r.ipVersion).append('\t')
      sb.append(r.overallStatus).append('\t')
      sb.append(r.port).append('\t')
      sb.append(r.tcpMs?.toString() ?: "-").append('\t')
      sb.append(r.tlsMs?.toString() ?: "-").append('\t')
      sb.append(r.wsMs?.toString() ?: "-").append('\t')
      sb.append(r.tunnelMedianMs?.toString() ?: "-").append('\t')
      sb.append("${r.tunnelSuccesses}/${r.tunnelAttempts}").append('\t')
      sb.appendLine(redactSensitiveText("${r.failureCause}: ${r.detailMessageEn}"))
    }
    return sb.toString()
  }

  fun generateCsvReport(
    session: ScanSessionEntity,
    results: List<ScanResultEntity>,
  ): String {
    val sb = StringBuilder()
    sb.appendLine("# session_id=${session.sessionId},date=${formatTime(session.createdAtMillis)},aws_sync_token=${session.awsSyncToken},aws_create_date=${session.awsCreateDate},vless=${redactSensitiveText(session.vlessRedactedSummary)}")
    sb.appendLine(
      "ip,ip_version,port,sni,host,overall_status,tcp_status,tcp_ms,tls_status,tls_ms,ws_status,ws_ms,ws_http_code,tunnel_status,tunnel_attempts,tunnel_successes,tunnel_median_ms,stability_score,failure_cause,reason"
    )
    for (r in results) {
      sb.append(csvCell(r.ip)).append(',')
      sb.append(csvCell(r.ipVersion)).append(',')
      sb.append(r.port).append(',')
      sb.append(csvCell(r.sniUsed)).append(',')
      sb.append(csvCell(r.hostUsed)).append(',')
      sb.append(csvCell(r.overallStatus)).append(',')
      sb.append(csvCell(r.tcpStatus)).append(',')
      sb.append(r.tcpMs?.toString() ?: "").append(',')
      sb.append(csvCell(r.tlsStatus)).append(',')
      sb.append(r.tlsMs?.toString() ?: "").append(',')
      sb.append(csvCell(r.wsStatus)).append(',')
      sb.append(r.wsMs?.toString() ?: "").append(',')
      sb.append(r.wsHttpCode?.toString() ?: "").append(',')
      sb.append(csvCell(r.tunnelStatus)).append(',')
      sb.append(r.tunnelAttempts).append(',')
      sb.append(r.tunnelSuccesses).append(',')
      sb.append(r.tunnelMedianMs?.toString() ?: "").append(',')
      sb.append(String.format(Locale.US, "%.1f", r.stabilityScore)).append(',')
      sb.append(csvCell(r.failureCause)).append(',')
      sb.appendLine(csvCell(redactSensitiveText(r.detailMessageEn)))
    }
    return sb.toString()
  }

  fun generateJsonReport(
    session: ScanSessionEntity,
    results: List<ScanResultEntity>,
  ): String {
    val root = JSONObject()
    root.put("sessionId", session.sessionId)
    root.put("scanDate", formatTime(session.createdAtMillis))
    root.put("scanMode", session.scanMode)
    root.put("ipFamilyPreference", session.ipFamilyPreference)
    root.put("scanPreset", session.scanPreset)
    root.put("awsSyncToken", session.awsSyncToken)
    root.put("awsCreateDate", session.awsCreateDate)
    root.put("awsIsFromCache", session.awsIsFromCache)
    root.put("vlessRedactedSummary", redactSensitiveText(session.vlessRedactedSummary))
    root.put("ratioNote", session.ratioNote)
    root.put(
      "settingsSnapshot",
      runCatching { JSONObject(redactSensitiveText(session.settingsSnapshotJson)) }
        .getOrElse { redactSensitiveText(session.settingsSnapshotJson) },
    )

    val summary = JSONObject()
    summary.put("totalInputs", session.totalInputs)
    summary.put("validInputs", session.validInputs)
    summary.put("duplicatesRemoved", session.duplicatesRemoved)
    summary.put("invalidInputsCount", session.invalidInputsCount)
    summary.put("tcpPassCount", session.tcpPassCount)
    summary.put("tlsPassCount", session.tlsPassCount)
    summary.put("wsPassCount", session.wsPassCount)
    summary.put("skippedCount", session.skippedCount)
    summary.put("tunnelPassCount", session.tunnelPassCount)
    summary.put("tunnelFailCount", session.tunnelFailCount)
    summary.put("inconclusiveCount", session.inconclusiveCount)
    root.put("summary", summary)

    val arr = JSONArray()
    for (r in results) {
      val item = JSONObject()
      item.put("ip", r.ip)
      item.put("ipVersion", r.ipVersion)
      item.put("port", r.port)
      item.put("sniUsed", r.sniUsed)
      item.put("hostUsed", r.hostUsed)
      item.put("overallStatus", r.overallStatus)
      item.put("icmpPingMs", r.icmpPingMs ?: JSONObject.NULL)
      item.put("tcpStatus", r.tcpStatus)
      item.put("tcpMs", r.tcpMs ?: JSONObject.NULL)
      item.put("tlsStatus", r.tlsStatus)
      item.put("tlsMs", r.tlsMs ?: JSONObject.NULL)
      item.put("wsStatus", r.wsStatus)
      item.put("wsMs", r.wsMs ?: JSONObject.NULL)
      item.put("wsHttpCode", r.wsHttpCode ?: JSONObject.NULL)
      item.put("tunnelStatus", r.tunnelStatus)
      item.put("tunnelAttempts", r.tunnelAttempts)
      item.put("tunnelSuccesses", r.tunnelSuccesses)
      item.put("tunnelTimeoutsOrDrops", r.tunnelTimeoutsOrDrops)
      item.put("tunnelMedianMs", r.tunnelMedianMs ?: JSONObject.NULL)
      item.put("externalIpObserved", r.externalIpObserved ?: JSONObject.NULL)
      item.put("downloadKbps", r.downloadKbps ?: JSONObject.NULL)
      item.put("stabilityScore", r.stabilityScore)
      item.put("failureCause", r.failureCause)
      item.put("reasonFa", redactSensitiveText(r.detailMessageFa))
      item.put("reasonEn", redactSensitiveText(r.detailMessageEn))
      arr.put(item)
    }
    root.put("results", arr)
    return root.toString(2)
  }

  fun buildContent(
    format: ExportFormat,
    session: ScanSessionEntity,
    results: List<ScanResultEntity>,
  ): String =
    when (format) {
      ExportFormat.TXT -> generateTxtReport(session, results)
      ExportFormat.CSV -> generateCsvReport(session, results)
      ExportFormat.JSON -> generateJsonReport(session, results)
    }

  suspend fun writeToSafUri(context: Context, uri: Uri, content: String): Result<Unit> =
    withContext(Dispatchers.IO) {
      runCatching {
        context.contentResolver.openOutputStream(uri)?.use { out ->
          out.write(content.toByteArray(Charsets.UTF_8))
          out.flush()
        } ?: throw IllegalStateException("Cannot open SAF output stream")
      }
    }

  suspend fun writeTempShareFile(
    context: Context,
    fileName: String,
    content: String,
  ): File =
    withContext(Dispatchers.IO) {
      val dir = File(context.cacheDir, "exports").apply { mkdirs() }
      val file = File(dir, fileName)
      file.writeText(content, Charsets.UTF_8)
      file
    }

  private fun csvCell(raw: String): String {
    val safe = raw.replace("\"", "\"\"")
    return "\"$safe\""
  }
}
