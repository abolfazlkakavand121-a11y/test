package com.example.domain.model

import kotlinx.serialization.Serializable

enum class AppLanguage(val code: String) {
  FA("fa"),
  EN("en"),
}

enum class ThemeMode {
  SYSTEM,
  DARK,
  LIGHT,
}

enum class ScanMode {
  AUTO_CLOUDFRONT,
  MANUAL_INPUT,
}

enum class IpFamilyPreference {
  BOTH_50_50,
  IPV4_ONLY,
  IPV6_ONLY,
}

enum class IpVersion {
  IPV4,
  IPV6,
}

enum class ScanPreset {
  FAST,
  BALANCED,
  ACCURATE,
}

enum class ScanPhase {
  IDLE,
  PHASE1_PROBING,
  PHASE1_COMPLETED,
  PHASE2_TUNNEL,
  COMPLETED,
  PAUSED,
  STOPPED,
}

enum class SubStepStatus {
  PENDING,
  PASS,
  FAIL,
  NOT_CONFIGURED,
  SKIPPED,
  DIAGNOSTIC_INSECURE_PASS,
}

enum class OverallIpStatus {
  PENDING,
  REACHABLE,
  TCP_PASS,
  TLS_PASS,
  WS_PASS,
  TUNNEL_PASS,
  TUNNEL_FAIL,
  PHASE1_FAIL,
  INCONCLUSIVE,
  SKIPPED,
}

enum class FailureCause {
  NONE,
  UNSUPPORTED_NETWORK_IPV6,
  NO_INTERNET,
  POSSIBLE_LOCAL_NETWORK_ISSUE,
  TCP_TIMEOUT,
  TCP_CONN_REFUSED,
  TCP_UNREACHABLE,
  TLS_CERT_ERROR,
  TLS_HANDSHAKE_FAILED,
  TLS_TIMEOUT,
  WS_UPGRADE_HTTP_ERROR,
  WS_TIMEOUT,
  WS_FAILED,
  NOT_CONFIGURED,
  XRAY_START_FAILED,
  TUNNEL_PROXY_UNREACHABLE,
  TUNNEL_NO_OUTBOUND_TRAFFIC,
  TUNNEL_REQUEST_TIMEOUT,
  TUNNEL_TLS_ERROR,
  TUNNEL_STATUS_MISMATCH,
  TUNNEL_CONTENT_MISMATCH,
  TUNNEL_IO_ERROR,
  INCOMPATIBLE_CONFIG,
}

enum class LogLevel {
  INFO,
  PASS,
  FAIL,
  SKIP,
}

enum class Phase2SelectionMode {
  ALL,
  TOP_50,
  TOP_100,
  CUSTOM,
}

@Serializable
data class TunnelTestUrl(
  val id: String,
  val url: String,
  val expectedStatusCode: Int = 204,
  val expectedBodyContains: String = "",
  val timeoutMs: Int = 8000,
  val enabled: Boolean = true,
) {
  val isPlainHttp: Boolean
    get() = url.trim().lowercase().startsWith("http://")
}

@Serializable
data class ScanSettings(
  val language: AppLanguage = AppLanguage.FA,
  val themeMode: ThemeMode = ThemeMode.SYSTEM,
  val scanPreset: ScanPreset = ScanPreset.BALANCED,
  val scanMode: ScanMode = ScanMode.AUTO_CLOUDFRONT,
  val ipFamilyPreference: IpFamilyPreference = IpFamilyPreference.BOTH_50_50,
  val autoSampleCount: Int = 100,
  val manualCidrSampleCap: Int = 100,
  val manualCidrScanAllUpTo: Int = 256,
  val defaultPort: Int = 443,
  val tcpTimeoutMs: Int = 2500,
  val tlsTimeoutMs: Int = 3500,
  val wsTimeoutMs: Int = 4000,
  val phase1Workers: Int = 8,
  val phase1Retries: Int = 1,
  val enableOptionalIcmpPing: Boolean = false,
  val allowInsecureTlsDiagnostic: Boolean = false,
  val xrayStartTimeoutMs: Int = 5000,
  val tunnelRepetitions: Int = 3,
  val phase2SelectionMode: Phase2SelectionMode = Phase2SelectionMode.TOP_50,
  val phase2CustomCount: Int = 30,
  val enableExternalIpCheck: Boolean = false,
  val externalIpCheckUrl: String = "https://api.ipify.org?format=json",
  val enableOptionalDownloadSpeedTest: Boolean = false,
  val downloadSpeedTestUrl: String = "",
  val tunnelTestUrls: List<TunnelTestUrl> =
    listOf(
      TunnelTestUrl(
        id = "default_https_204",
        url = "https://www.gstatic.com/generate_204",
        expectedStatusCode = 204,
        expectedBodyContains = "",
        timeoutMs = 8000,
        enabled = true,
      )
    ),
)

data class VlessConfig(
  val rawUri: String,
  val uuid: String,
  val address: String,
  val port: Int,
  val encryption: String = "none",
  val flow: String = "",
  val network: String = "tcp",
  val security: String = "none",
  val sni: String = "",
  val fingerprint: String = "",
  val alpn: List<String> = emptyList(),
  val allowInsecure: Boolean = false,
  val wsHost: String = "",
  val wsPath: String = "",
  val grpcServiceName: String = "",
  val grpcMode: String = "gun",
  val realityPublicKey: String = "",
  val realityShortId: String = "",
  val realitySpiderX: String = "",
  val headerType: String = "",
  val remark: String = "",
  val unknownParams: Map<String, String> = emptyMap(),
) {
  val effectiveSni: String
    get() = sni.ifBlank { wsHost }

  fun redactedUuid(): String {
    if (uuid.length <= 12) return "****"
    return "${uuid.take(8)}-****-****-****-${uuid.takeLast(4)}"
  }

  fun redactedSummary(): String {
    val parts = mutableListOf<String>()
    parts += "VLESS (${redactedUuid()})"
    parts += "port=$port"
    parts += "net=$network"
    parts += "sec=$security"
    if (effectiveSni.isNotBlank()) parts += "sni=$effectiveSni"
    if (wsHost.isNotBlank()) parts += "host=$wsHost"
    if (wsPath.isNotBlank()) parts += "path=$wsPath"
    if (grpcServiceName.isNotBlank()) parts += "serviceName=$grpcServiceName"
    if (alpn.isNotEmpty()) parts += "alpn=${alpn.joinToString(",")}"
    if (fingerprint.isNotBlank()) parts += "fp=$fingerprint"
    if (flow.isNotBlank()) parts += "flow=$flow"
    if (realityPublicKey.isNotBlank()) parts += "pbk=***redacted***"
    if (realityShortId.isNotBlank()) parts += "sid=***"
    return parts.joinToString(" | ")
  }
}

data class VlessCompatibilityReport(
  val isValid: Boolean,
  val isCloudFrontCompatible: Boolean,
  val canRunPhase2: Boolean,
  val warnings: List<String> = emptyList(),
  val errors: List<String> = emptyList(),
  val unsupportedOptions: List<String> = emptyList(),
  val compatibilityExplanationFa: String = "",
  val compatibilityExplanationEn: String = "",
)

data class AwsPrefixEntry(
  val cidr: String,
  val ipVersion: IpVersion,
  val service: String,
  val region: String,
  val networkBorderGroup: String = "",
)

data class AwsIpRangesSnapshot(
  val syncToken: String,
  val createDate: String,
  val fetchedAtMillis: Long,
  val isFromCache: Boolean,
  val isStaleWarning: Boolean,
  val globalEdgeV4: List<AwsPrefixEntry>,
  val globalEdgeV6: List<AwsPrefixEntry>,
  val regionalEdgeV4: List<AwsPrefixEntry>,
  val regionalEdgeV6: List<AwsPrefixEntry>,
  val originFacingV4: List<AwsPrefixEntry>,
  val originFacingV6: List<AwsPrefixEntry>,
  val secondarySourceDiffNote: String = "",
)

data class ManualLineError(
  val lineNumber: Int,
  val rawLine: String,
  val reasonFa: String,
  val reasonEn: String,
)

data class ManualCidrNotice(
  val cidr: String,
  val ipVersion: IpVersion,
  val totalHosts: String,
  val sampledCount: Int,
  val wasSampled: Boolean,
)

data class ManualParseReport(
  val uniqueIps: List<IpCandidate>,
  val totalInputEntries: Int,
  val validEntriesBeforeDedup: Int,
  val duplicatesRemoved: Int,
  val errors: List<ManualLineError>,
  val cidrNotices: List<ManualCidrNotice>,
)

data class IpCandidate(
  val ip: String,
  val ipVersion: IpVersion,
  val sourceCidr: String = "",
  val isManual: Boolean = false,
)

data class Phase1ProbeOutcome(
  val ip: String,
  val ipVersion: IpVersion,
  val port: Int,
  val sniUsed: String,
  val hostUsed: String,
  val icmpPingMs: Long? = null,
  val tcpStatus: SubStepStatus,
  val tcpMs: Long? = null,
  val tlsStatus: SubStepStatus,
  val tlsMs: Long? = null,
  val wsStatus: SubStepStatus,
  val wsMs: Long? = null,
  val wsHttpCode: Int? = null,
  val overallStatus: OverallIpStatus,
  val failureCause: FailureCause,
  val detailMessageFa: String,
  val detailMessageEn: String,
  val phase1Qualified: Boolean,
)

data class Phase2TunnelOutcome(
  val ip: String,
  val ipVersion: IpVersion,
  val attempts: Int,
  val successes: Int,
  val timeoutsOrDrops: Int,
  val tunnelStatus: OverallIpStatus,
  val medianMs: Long? = null,
  val minMs: Long? = null,
  val maxMs: Long? = null,
  val externalIpObserved: String? = null,
  val downloadKbps: Double? = null,
  val failureCause: FailureCause,
  val detailMessageFa: String,
  val detailMessageEn: String,
  val stabilityScore: Double,
)
