package com.example.core.network

import android.content.Context
import com.example.domain.model.FailureCause
import com.example.domain.model.IpCandidate
import com.example.domain.model.OverallIpStatus
import com.example.domain.model.Phase2TunnelOutcome
import com.example.domain.model.ScanSettings
import com.example.domain.model.TunnelTestUrl
import com.example.domain.model.VlessConfig
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray

interface XrayTunnelRunner {
  fun xrayVersion(): String

  suspend fun runTunnelTest(
    candidate: IpCandidate,
    vlessConfig: VlessConfig,
    settings: ScanSettings,
  ): Phase2TunnelOutcome
}

data class HttpThroughSocksResult(
  val statusCode: Int,
  val bodySnippet: String,
  val latencyMs: Long,
)

class AndroidLibXrayRunner(
  private val context: Context,
) : XrayTunnelRunner {

  companion object {
    private val envInitialized = AtomicBoolean(false)

    fun parseStatsMap(rawStats: String): Map<String, Long> {
      if (rawStats.isBlank()) return emptyMap()
      val map = mutableMapOf<String, Long>()
      for (entry in rawStats.split(';')) {
        val parts = entry.split(',')
        if (parts.size == 3) {
          val key = "${parts[0].trim()}:${parts[1].trim()}"
          val value = parts[2].trim().toLongOrNull() ?: 0L
          map[key] = value
        }
      }
      return map
    }

    fun computeMedian(values: List<Long>): Long? {
      if (values.isEmpty()) return null
      val sorted = values.sorted()
      val mid = sorted.size / 2
      return if (sorted.size % 2 == 1) {
        sorted[mid]
      } else {
        (sorted[mid - 1] + sorted[mid]) / 2L
      }
    }
  }

  private fun ensureCoreEnv() {
    if (envInitialized.compareAndSet(false, true)) {
      val xrayDir = context.filesDir.absolutePath
      Libv2ray.initCoreEnv(xrayDir, "")
    }
  }

  override fun xrayVersion(): String =
    try {
      Libv2ray.checkVersionX()
    } catch (e: Throwable) {
      "AndroidLibXrayLite (unavailable: ${e.message})"
    }

  override suspend fun runTunnelTest(
    candidate: IpCandidate,
    vlessConfig: VlessConfig,
    settings: ScanSettings,
  ): Phase2TunnelOutcome =
    withContext(Dispatchers.IO) {
      val enabledUrls = settings.tunnelTestUrls.filter { it.enabled && it.url.isNotBlank() }
      if (enabledUrls.isEmpty()) {
        return@withContext Phase2TunnelOutcome(
          ip = candidate.ip,
          ipVersion = candidate.ipVersion,
          attempts = 0,
          successes = 0,
          timeoutsOrDrops = 0,
          tunnelStatus = OverallIpStatus.INCONCLUSIVE,
          failureCause = FailureCause.NOT_CONFIGURED,
          detailMessageFa = "هیچ آدرس تست تونلی در تنظیمات فعال نیست",
          detailMessageEn = "No enabled tunnel test URLs configured",
          stabilityScore = 0.0,
        )
      }

      var controller: CoreController? = null
      try {
        ensureCoreEnv()
        val localPort = allocateLoopbackPort()
        val configJson =
          VlessParser.buildXrayTestConfigJson(
            config = vlessConfig,
            candidateIp = candidate.ip,
            localSocksPort = localPort,
            allowInsecureTlsDiagnostic = settings.allowInsecureTlsDiagnostic,
          )

        val startupError = StringBuilder()
        val callback =
          object : CoreCallbackHandler {
            override fun startup(): Long = 0L

            override fun shutdown(): Long = 0L

            override fun onEmitStatus(p0: Long, p1: String?): Long {
              if (!p1.isNullOrBlank() && p0 != 0L) {
                startupError.append(p1).append(' ')
              }
              return 0L
            }
          }

        val ctrl = Libv2ray.newCoreController(callback)
        controller = ctrl
        ctrl.startLoop(configJson, 0)

        val portReady = waitForLoopbackPort(localPort, settings.xrayStartTimeoutMs.toLong())
        if (!portReady || !ctrl.isRunning) {
          return@withContext Phase2TunnelOutcome(
            ip = candidate.ip,
            ipVersion = candidate.ipVersion,
            attempts = 0,
            successes = 0,
            timeoutsOrDrops = 0,
            tunnelStatus = OverallIpStatus.TUNNEL_FAIL,
            failureCause = FailureCause.XRAY_START_FAILED,
            detailMessageFa =
              "Xray روی پورت محلی راه‌اندازی نشد ${startupError.toString().take(80)}",
            detailMessageEn =
              "XRAY_START_FAILED: local SOCKS5 port did not open ${startupError.toString().take(80)}",
            stabilityScore = 0.0,
          )
        }

        val repetitions = settings.tunnelRepetitions.coerceIn(1, 5)
        val latencies = mutableListOf<Long>()
        var successes = 0
        var timeoutsOrDrops = 0
        var lastFailureCause = FailureCause.NONE
        var lastErrorFa = ""
        var lastErrorEn = ""
        var inconclusiveTraffic = false

        for (rep in 1..repetitions) {
          var repAllUrlsOk = true
          var repTotalMs = 0L

          for (testUrl in enabledUrls) {
            try {
              val res =
                executeRequestStrictlyViaSocks5(
                  localSocksPort = localPort,
                  testUrl = testUrl,
                  allowInsecureTlsDiagnostic = settings.allowInsecureTlsDiagnostic,
                )
              if (res.statusCode != testUrl.expectedStatusCode) {
                repAllUrlsOk = false
                lastFailureCause = FailureCause.TUNNEL_STATUS_MISMATCH
                lastErrorFa =
                  "پاسخ غیرمنتظره از ${testUrl.url}: کد ${res.statusCode} (انتظار ${testUrl.expectedStatusCode})"
                lastErrorEn =
                  "Unexpected status ${res.statusCode} from ${testUrl.url} (expected ${testUrl.expectedStatusCode})"
                break
              }
              if (
                testUrl.expectedBodyContains.isNotBlank() &&
                  !res.bodySnippet.contains(testUrl.expectedBodyContains)
              ) {
                repAllUrlsOk = false
                lastFailureCause = FailureCause.TUNNEL_CONTENT_MISMATCH
                lastErrorFa = "عبارت مورد انتظار در پاسخ تونل یافت نشد"
                lastErrorEn = "Expected response substring not found"
                break
              }
              repTotalMs += res.latencyMs
            } catch (e: SocketTimeoutException) {
              repAllUrlsOk = false
              timeoutsOrDrops++
              lastFailureCause = FailureCause.TUNNEL_REQUEST_TIMEOUT
              lastErrorFa = "تایم‌اوت درخواست از داخل تونل Xray (${testUrl.timeoutMs}ms)"
              lastErrorEn = "TUNNEL_REQUEST_TIMEOUT (${testUrl.timeoutMs}ms)"
              break
            } catch (e: SSLPeerUnverifiedException) {
              repAllUrlsOk = false
              lastFailureCause = FailureCause.TUNNEL_TLS_ERROR
              lastErrorFa = "خطای گواهی TLS در آدرس تست داخل تونل: ${e.message}"
              lastErrorEn = "Tunnel test TLS cert verification error: ${e.message}"
              break
            } catch (e: SSLHandshakeException) {
              repAllUrlsOk = false
              lastFailureCause = FailureCause.TUNNEL_TLS_ERROR
              lastErrorFa = "خطای TLS داخل تونل: ${e.message}"
              lastErrorEn = "Tunnel TLS handshake error: ${e.message}"
              break
            } catch (e: Exception) {
              repAllUrlsOk = false
              timeoutsOrDrops++
              lastFailureCause = FailureCause.TUNNEL_IO_ERROR
              lastErrorFa = "خطای عبور ترافیک از تونل Xray: ${e.message}"
              lastErrorEn = "Tunnel IO error: ${e.message}"
              break
            }
          }

          if (repAllUrlsOk) {
            // Proof check: verify Xray outbound stats show real bytes routed through cf-proxy
            val stats = parseStatsMap(ctrl.queryAllOutboundTrafficStats().orEmpty())
            val uplink = stats["cf-proxy:uplink"] ?: 0L
            val downlink = stats["cf-proxy:downlink"] ?: 0L
            if (uplink > 0L && downlink > 0L) {
              successes++
              latencies += repTotalMs.coerceAtLeast(1L)
            } else {
              inconclusiveTraffic = true
              lastFailureCause = FailureCause.TUNNEL_NO_OUTBOUND_TRAFFIC
              lastErrorFa = "امکان اثبات عبور ترافیک از Outbound پروکسی وجود نداشت (INCONCLUSIVE)"
              lastErrorEn = "Could not prove outbound traffic traversed cf-proxy (INCONCLUSIVE)"
            }
          }
        }

        // Optional non-blocking External IP check
        var externalIp: String? = null
        if (successes > 0 && settings.enableExternalIpCheck && settings.externalIpCheckUrl.isNotBlank()) {
          externalIp =
            runCatching {
              val ext =
                executeRequestStrictlyViaSocks5(
                  localSocksPort = localPort,
                  testUrl =
                    TunnelTestUrl(
                      id = "ext_ip",
                      url = settings.externalIpCheckUrl,
                      expectedStatusCode = 200,
                      timeoutMs = 5000,
                    ),
                  allowInsecureTlsDiagnostic = settings.allowInsecureTlsDiagnostic,
                )
              if (ext.statusCode == 200) ext.bodySnippet.take(80) else null
            }
              .getOrNull()
        }

        // Optional controlled download speed check ONLY on confirmed TUNNEL_PASS IPs
        var downloadKbps: Double? = null
        if (
          successes > 0 &&
            settings.enableOptionalDownloadSpeedTest &&
            settings.downloadSpeedTestUrl.isNotBlank()
        ) {
          downloadKbps =
            runCatching {
              measureControlledDownloadKbps(
                localSocksPort = localPort,
                urlStr = settings.downloadSpeedTestUrl,
                allowInsecureTlsDiagnostic = settings.allowInsecureTlsDiagnostic,
              )
            }
              .getOrNull()
        }

        val medianMs = computeMedian(latencies)
        val minMs = latencies.minOrNull()
        val maxMs = latencies.maxOrNull()
        val stabilityScore =
          if (repetitions > 0) (successes.toDouble() / repetitions.toDouble()) * 100.0 else 0.0

        val status =
          when {
            successes > 0 -> OverallIpStatus.TUNNEL_PASS
            inconclusiveTraffic -> OverallIpStatus.INCONCLUSIVE
            else -> OverallIpStatus.TUNNEL_FAIL
          }

        val summaryFa =
          when (status) {
            OverallIpStatus.TUNNEL_PASS ->
              "تونل واقعی Xray موفق ($successes/$repetitions تکرار، میانه ${medianMs}ms)"
            OverallIpStatus.INCONCLUSIVE -> lastErrorFa
            else -> lastErrorFa.ifBlank { "شکست در عبور از تونل VLESS" }
          }
        val summaryEn =
          when (status) {
            OverallIpStatus.TUNNEL_PASS ->
              "TUNNEL_PASS ($successes/$repetitions runs, median ${medianMs}ms)"
            OverallIpStatus.INCONCLUSIVE -> lastErrorEn
            else -> lastErrorEn.ifBlank { "TUNNEL_FAIL" }
          }

        Phase2TunnelOutcome(
          ip = candidate.ip,
          ipVersion = candidate.ipVersion,
          attempts = repetitions,
          successes = successes,
          timeoutsOrDrops = timeoutsOrDrops,
          tunnelStatus = status,
          medianMs = medianMs,
          minMs = minMs,
          maxMs = maxMs,
          externalIpObserved = externalIp,
          downloadKbps = downloadKbps,
          failureCause = if (status == OverallIpStatus.TUNNEL_PASS) FailureCause.NONE else lastFailureCause,
          detailMessageFa = summaryFa,
          detailMessageEn = summaryEn,
          stabilityScore = stabilityScore,
        )
      } catch (e: Throwable) {
        Phase2TunnelOutcome(
          ip = candidate.ip,
          ipVersion = candidate.ipVersion,
          attempts = settings.tunnelRepetitions,
          successes = 0,
          timeoutsOrDrops = settings.tunnelRepetitions,
          tunnelStatus = OverallIpStatus.INCONCLUSIVE,
          failureCause = FailureCause.XRAY_START_FAILED,
          detailMessageFa = "خطای اجرای موتور Xray: ${e.message}",
          detailMessageEn = "Xray execution error: ${e.message}",
          stabilityScore = 0.0,
        )
      } finally {
        runCatching { controller?.stopLoop() }
      }
    }

  private fun allocateLoopbackPort(): Int =
    ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

  private suspend fun waitForLoopbackPort(port: Int, timeoutMs: Long): Boolean {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000L
    while (System.nanoTime() < deadline) {
      val open =
        runCatching {
          Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), 150)
            true
          }
        }
          .getOrDefault(false)
      if (open) return true
      delay(30L)
    }
    return false
  }

  /**
   * Connects strictly through local SOCKS5 proxy using InetSocketAddress.createUnresolved(host, port)
   * so DNS happens remotely via Xray outbound and direct fallback is impossible.
   */
  fun executeRequestStrictlyViaSocks5(
    localSocksPort: Int,
    testUrl: TunnelTestUrl,
    allowInsecureTlsDiagnostic: Boolean,
  ): HttpThroughSocksResult {
    val uri = URI(testUrl.url.trim())
    val scheme = uri.scheme?.lowercase() ?: "https"
    val host = uri.host ?: throw IllegalArgumentException("Invalid test URL host: ${testUrl.url}")
    val port =
      if (uri.port > 0) uri.port else if (scheme == "https") 443 else 80
    val pathWithQuery =
      buildString {
        append(uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/")
        if (!uri.rawQuery.isNullOrBlank()) append('?').append(uri.rawQuery)
      }

    val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", localSocksPort))
    val socksSocket = Socket(proxy)
    var activeSocket: Socket = socksSocket
    try {
      val start = System.nanoTime()
      socksSocket.soTimeout = testUrl.timeoutMs
      socksSocket.connect(InetSocketAddress.createUnresolved(host, port), testUrl.timeoutMs)

      if (scheme == "https") {
        val factory =
          if (allowInsecureTlsDiagnostic) createInsecureSslFactory()
          else SSLSocketFactory.getDefault() as SSLSocketFactory
        val ssl = factory.createSocket(socksSocket, host, port, true) as SSLSocket
        activeSocket = ssl
        ssl.soTimeout = testUrl.timeoutMs
        val params = ssl.sslParameters
        params.serverNames = listOf(SNIHostName(host))
        params.applicationProtocols = arrayOf("http/1.1")
        ssl.sslParameters = params
        ssl.startHandshake()
        if (!allowInsecureTlsDiagnostic) {
          val ok = HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)
          if (!ok) throw SSLPeerUnverifiedException("Hostname check failed for $host")
        }
      }

      val hostHeader = if ((scheme == "https" && port == 443) || (scheme == "http" && port == 80)) host else "$host:$port"
      val httpReq =
        buildString {
          append("GET ").append(pathWithQuery).append(" HTTP/1.1\r\n")
          append("Host: ").append(hostHeader).append("\r\n")
          append("User-Agent: CloudFrontScanner/1.0\r\n")
          append("Accept-Encoding: identity\r\n")
          append("Connection: close\r\n\r\n")
        }

      val out = activeSocket.getOutputStream()
      out.write(httpReq.toByteArray(Charsets.UTF_8))
      out.flush()

      val input = BufferedInputStream(activeSocket.getInputStream())
      val headerText = readHeaders(input)
      val statusLine = headerText.lineSequence().firstOrNull().orEmpty()
      val statusCode =
        statusLine.split(' ').getOrNull(1)?.toIntOrNull()
          ?: throw IllegalStateException("Malformed HTTP status line: $statusLine")

      val bodyBytes = input.readBytesLimited(32 * 1024)
      val elapsed = ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(1L)
      return HttpThroughSocksResult(
        statusCode = statusCode,
        bodySnippet = bodyBytes.toString(Charsets.UTF_8),
        latencyMs = elapsed,
      )
    } finally {
      runCatching { activeSocket.close() }
      runCatching { socksSocket.close() }
    }
  }

  private fun measureControlledDownloadKbps(
    localSocksPort: Int,
    urlStr: String,
    allowInsecureTlsDiagnostic: Boolean,
  ): Double? {
    val uri = URI(urlStr.trim())
    val scheme = uri.scheme?.lowercase() ?: return null
    val host = uri.host ?: return null
    val port = if (uri.port > 0) uri.port else if (scheme == "https") 443 else 80
    val pathWithQuery =
      buildString {
        append(uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/")
        if (!uri.rawQuery.isNullOrBlank()) append('?').append(uri.rawQuery)
      }
    val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", localSocksPort))
    val socksSocket = Socket(proxy)
    var activeSocket: Socket = socksSocket
    return try {
      socksSocket.soTimeout = 8000
      socksSocket.connect(InetSocketAddress.createUnresolved(host, port), 6000)
      if (scheme == "https") {
        val factory =
          if (allowInsecureTlsDiagnostic) createInsecureSslFactory()
          else SSLSocketFactory.getDefault() as SSLSocketFactory
        val ssl = factory.createSocket(socksSocket, host, port, true) as SSLSocket
        activeSocket = ssl
        val params = ssl.sslParameters
        params.serverNames = listOf(SNIHostName(host))
        params.applicationProtocols = arrayOf("http/1.1")
        ssl.sslParameters = params
        ssl.startHandshake()
      }
      val req =
        "GET $pathWithQuery HTTP/1.1\r\nHost: $host\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n"
      activeSocket.getOutputStream().write(req.toByteArray(Charsets.UTF_8))
      activeSocket.getOutputStream().flush()
      val input = BufferedInputStream(activeSocket.getInputStream())
      readHeaders(input)
      val start = System.nanoTime()
      val maxBytes = 512 * 1024 // Cap at 512 KB to avoid wasting mobile data
      var totalRead = 0
      val buf = ByteArray(8192)
      while (totalRead < maxBytes) {
        val n = input.read(buf)
        if (n <= 0) break
        totalRead += n
      }
      val elapsedSec = ((System.nanoTime() - start) / 1_000_000_000.0).coerceAtLeast(0.05)
      (totalRead * 8.0 / 1000.0) / elapsedSec
    } finally {
      runCatching { activeSocket.close() }
      runCatching { socksSocket.close() }
    }
  }

  private fun readHeaders(input: BufferedInputStream): String {
    val sb = StringBuilder()
    var prev3 = 0
    var prev2 = 0
    var prev1 = 0
    var count = 0
    while (count < 16384) {
      val b = input.read()
      if (b == -1) break
      count++
      sb.append(b.toChar())
      if (prev3 == '\r'.code && prev2 == '\n'.code && prev1 == '\r'.code && b == '\n'.code) {
        break
      }
      prev3 = prev2
      prev2 = prev1
      prev1 = b
    }
    return sb.toString()
  }

  private fun BufferedInputStream.readBytesLimited(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(4096)
    var total = 0
    while (total < limit) {
      val n = read(buf, 0, minOf(buf.size, limit - total))
      if (n <= 0) break
      out.write(buf, 0, n)
      total += n
    }
    return out.toByteArray()
  }

  @Suppress("CustomX509TrustManager", "TrustAllX509TrustManager")
  private fun createInsecureSslFactory(): SSLSocketFactory {
    val trustAll =
      arrayOf<TrustManager>(
        object : X509TrustManager {
          override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

          override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

          override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
      )
    val ctx = SSLContext.getInstance("TLS")
    ctx.init(null, trustAll, SecureRandom())
    return ctx.socketFactory
  }
}
