package com.example.core.network

import com.example.domain.model.FailureCause
import com.example.domain.model.IpCandidate
import com.example.domain.model.IpVersion
import com.example.domain.model.OverallIpStatus
import com.example.domain.model.Phase1ProbeOutcome
import com.example.domain.model.ScanSettings
import com.example.domain.model.SubStepStatus
import com.example.domain.model.VlessConfig
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class Phase1ProbeEngine(
  private val networkChecker: NetworkCapabilityChecker,
) {

  private val random = SecureRandom()

  suspend fun probeCandidate(
    candidate: IpCandidate,
    settings: ScanSettings,
    vlessConfig: VlessConfig?,
  ): Phase1ProbeOutcome =
    withContext(Dispatchers.IO) {
      val netStatus = networkChecker.checkNetworkStatus()
      if (!netStatus.hasInternet) {
        return@withContext Phase1ProbeOutcome(
          ip = candidate.ip,
          ipVersion = candidate.ipVersion,
          port = vlessConfig?.port ?: settings.defaultPort,
          sniUsed = vlessConfig?.effectiveSni.orEmpty(),
          hostUsed = vlessConfig?.wsHost.orEmpty(),
          tcpStatus = SubStepStatus.SKIPPED,
          tlsStatus = SubStepStatus.SKIPPED,
          wsStatus = SubStepStatus.SKIPPED,
          overallStatus = OverallIpStatus.SKIPPED,
          failureCause = FailureCause.NO_INTERNET,
          detailMessageFa = "اتصال اینترنت دستگاه برقرار نیست",
          detailMessageEn = "No active internet connectivity on device",
          phase1Qualified = false,
        )
      }

      if (candidate.ipVersion == IpVersion.IPV6 && !netStatus.hasGlobalIpv6) {
        return@withContext Phase1ProbeOutcome(
          ip = candidate.ip,
          ipVersion = candidate.ipVersion,
          port = vlessConfig?.port ?: settings.defaultPort,
          sniUsed = vlessConfig?.effectiveSni.orEmpty(),
          hostUsed = vlessConfig?.wsHost.orEmpty(),
          tcpStatus = SubStepStatus.SKIPPED,
          tlsStatus = SubStepStatus.SKIPPED,
          wsStatus = SubStepStatus.SKIPPED,
          overallStatus = OverallIpStatus.SKIPPED,
          failureCause = FailureCause.UNSUPPORTED_NETWORK_IPV6,
          detailMessageFa = "شبکه فعلی از IPv6 پشتیبانی نمی‌کند (Skip شد؛ IP خراب محسوب نشد)",
          detailMessageEn = "UNSUPPORTED_NETWORK: current network lacks IPv6 routing (skipped)",
          phase1Qualified = false,
        )
      }

      val targetPort = vlessConfig?.port ?: settings.defaultPort
      val sni = vlessConfig?.effectiveSni.orEmpty()
      val wsHost = vlessConfig?.wsHost?.ifBlank { sni }.orEmpty()
      val wsPath = vlessConfig?.wsPath.orEmpty()
      val isWsTransport = vlessConfig?.network == "ws"
      val expectsTls = vlessConfig == null || vlessConfig.security == "tls"

      val icmpMs =
        if (settings.enableOptionalIcmpPing) {
          runRealIcmpPing(candidate.ip, candidate.ipVersion)
        } else {
          null
        }

      var lastOutcome: Phase1ProbeOutcome? = null
      val attempts = settings.phase1Retries.coerceIn(1, 3)
      for (attempt in 1..attempts) {
        val outcome =
          executeSingleProbe(
            candidate = candidate,
            port = targetPort,
            sni = sni,
            wsHost = wsHost,
            wsPath = wsPath,
            isWsTransport = isWsTransport,
            expectsTls = expectsTls,
            icmpMs = icmpMs,
            settings = settings,
          )
        lastOutcome = outcome
        if (outcome.phase1Qualified) return@withContext outcome
        // Don't retry certificate errors or unsupported configurations
        if (
          outcome.failureCause == FailureCause.TLS_CERT_ERROR ||
            outcome.failureCause == FailureCause.WS_UPGRADE_HTTP_ERROR
        ) {
          return@withContext outcome
        }
      }
      lastOutcome!!
    }

  private fun executeSingleProbe(
    candidate: IpCandidate,
    port: Int,
    sni: String,
    wsHost: String,
    wsPath: String,
    isWsTransport: Boolean,
    expectsTls: Boolean,
    icmpMs: Long?,
    settings: ScanSettings,
  ): Phase1ProbeOutcome {
    var tcpSocket: Socket? = null
    var sslSocket: SSLSocket? = null
    var tcpConnected = false
    try {
      val inetAddr = InetAddress.getByName(candidate.ip)
      val sock = Socket()
      tcpSocket = sock

      val tcpStart = System.nanoTime()
      sock.connect(InetSocketAddress(inetAddr, port), settings.tcpTimeoutMs)
      tcpConnected = true
      val tcpMs = ((System.nanoTime() - tcpStart) / 1_000_000L).coerceAtLeast(1L)

      // Determine TLS requirement
      if (!expectsTls || sni.isBlank()) {
        val tlsStatus = SubStepStatus.NOT_CONFIGURED
        if (isWsTransport && !expectsTls && wsHost.isNotBlank() && wsPath.isNotBlank()) {
          // Plaintext WS probe
          return probeWebSocket(
            activeSocket = sock,
            candidate = candidate,
            port = port,
            sni = sni,
            wsHost = wsHost,
            wsPath = wsPath,
            icmpMs = icmpMs,
            tcpMs = tcpMs,
            tlsStatus = tlsStatus,
            tlsMs = null,
            wsTimeoutMs = settings.wsTimeoutMs,
          )
        }
        return Phase1ProbeOutcome(
          ip = candidate.ip,
          ipVersion = candidate.ipVersion,
          port = port,
          sniUsed = sni,
          hostUsed = wsHost,
          icmpPingMs = icmpMs,
          tcpStatus = SubStepStatus.PASS,
          tcpMs = tcpMs,
          tlsStatus = tlsStatus,
          tlsMs = null,
          wsStatus = if (isWsTransport) SubStepStatus.NOT_CONFIGURED else SubStepStatus.NOT_CONFIGURED,
          wsMs = null,
          overallStatus = OverallIpStatus.TCP_PASS,
          failureCause = FailureCause.NONE,
          detailMessageFa =
            if (sni.isBlank()) "اتصال TCP برقرار شد؛ SNI تنظیم نشده (TLS تست نشد)"
            else "اتصال TCP برقرار شد",
          detailMessageEn =
            if (sni.isBlank()) "TCP connected; TLS NOT_CONFIGURED (no SNI)" else "TCP connected",
          phase1Qualified = true,
        )
      }

      // Perform TLS Handshake over connected TCP socket
      sock.soTimeout = settings.tlsTimeoutMs
      val factory =
        if (settings.allowInsecureTlsDiagnostic) {
          createInsecureDiagnosticSslFactory()
        } else {
          SSLSocketFactory.getDefault() as SSLSocketFactory
        }

      val tlsStart = System.nanoTime()
      val wrapped = factory.createSocket(sock, sni, port, true) as SSLSocket
      sslSocket = wrapped
      wrapped.soTimeout = settings.tlsTimeoutMs

      val params = wrapped.sslParameters
      params.serverNames = listOf(SNIHostName(sni))
      if (isWsTransport) {
        params.applicationProtocols = arrayOf("http/1.1")
      }
      wrapped.sslParameters = params
      wrapped.startHandshake()

      if (!settings.allowInsecureTlsDiagnostic) {
        val verified = HttpsURLConnection.getDefaultHostnameVerifier().verify(sni, wrapped.session)
        if (!verified) {
          throw SSLPeerUnverifiedException("Hostname verification failed for SNI=$sni")
        }
      }
      val tlsMs = ((System.nanoTime() - tlsStart) / 1_000_000L).coerceAtLeast(1L)
      val tlsStatus =
        if (settings.allowInsecureTlsDiagnostic) SubStepStatus.DIAGNOSTIC_INSECURE_PASS
        else SubStepStatus.PASS

      // Perform WebSocket upgrade if configured
      if (isWsTransport) {
        if (wsHost.isBlank() || wsPath.isBlank()) {
          return Phase1ProbeOutcome(
            ip = candidate.ip,
            ipVersion = candidate.ipVersion,
            port = port,
            sniUsed = sni,
            hostUsed = wsHost,
            icmpPingMs = icmpMs,
            tcpStatus = SubStepStatus.PASS,
            tcpMs = tcpMs,
            tlsStatus = tlsStatus,
            tlsMs = tlsMs,
            wsStatus = SubStepStatus.NOT_CONFIGURED,
            wsMs = null,
            overallStatus = OverallIpStatus.TLS_PASS,
            failureCause = FailureCause.NONE,
            detailMessageFa = "TCP و TLS موفق؛ WebSocket به دلیل نبود Host/Path تست نشد (NOT_CONFIGURED)",
            detailMessageEn = "TCP & TLS passed; WebSocket NOT_CONFIGURED (missing Host or Path)",
            phase1Qualified = true,
          )
        }
        return probeWebSocket(
          activeSocket = wrapped,
          candidate = candidate,
          port = port,
          sni = sni,
          wsHost = wsHost,
          wsPath = wsPath,
          icmpMs = icmpMs,
          tcpMs = tcpMs,
          tlsStatus = tlsStatus,
          tlsMs = tlsMs,
          wsTimeoutMs = settings.wsTimeoutMs,
        )
      }

      return Phase1ProbeOutcome(
        ip = candidate.ip,
        ipVersion = candidate.ipVersion,
        port = port,
        sniUsed = sni,
        hostUsed = wsHost,
        icmpPingMs = icmpMs,
        tcpStatus = SubStepStatus.PASS,
        tcpMs = tcpMs,
        tlsStatus = tlsStatus,
        tlsMs = tlsMs,
        wsStatus = SubStepStatus.NOT_CONFIGURED,
        wsMs = null,
        overallStatus = OverallIpStatus.TLS_PASS,
        failureCause = FailureCause.NONE,
        detailMessageFa =
          if (tlsStatus == SubStepStatus.DIAGNOSTIC_INSECURE_PASS) {
            "TCP موفق؛ TLS در حالت تشخیصی بدون اعتبارسنجی گواهی برقرار شد"
          } else {
            "TCP و TLS با گواهی معتبر برقرار شد"
          },
        detailMessageEn =
          if (tlsStatus == SubStepStatus.DIAGNOSTIC_INSECURE_PASS) {
            "TCP ok; TLS connected in INSECURE diagnostic mode"
          } else {
            "TCP & verified TLS handshake succeeded"
          },
        phase1Qualified = true,
      )
    } catch (e: SocketTimeoutException) {
      return Phase1ProbeOutcome(
        ip = candidate.ip,
        ipVersion = candidate.ipVersion,
        port = port,
        sniUsed = sni,
        hostUsed = wsHost,
        icmpPingMs = icmpMs,
        tcpStatus = if (tcpConnected) SubStepStatus.PASS else SubStepStatus.FAIL,
        tcpMs = null,
        tlsStatus = if (tcpConnected) SubStepStatus.FAIL else SubStepStatus.PENDING,
        wsStatus = SubStepStatus.PENDING,
        overallStatus = OverallIpStatus.PHASE1_FAIL,
        failureCause = if (tcpConnected) FailureCause.TLS_TIMEOUT else FailureCause.TCP_TIMEOUT,
        detailMessageFa = if (tcpConnected) "تایم‌اوت در TLS Handshake" else "تایم‌اوت در اتصال TCP",
        detailMessageEn = if (tcpConnected) "TLS handshake timeout" else "TCP connect timeout",
        phase1Qualified = false,
      )
    } catch (e: SSLPeerUnverifiedException) {
      return Phase1ProbeOutcome(
        ip = candidate.ip,
        ipVersion = candidate.ipVersion,
        port = port,
        sniUsed = sni,
        hostUsed = wsHost,
        icmpPingMs = icmpMs,
        tcpStatus = SubStepStatus.PASS,
        tlsStatus = SubStepStatus.FAIL,
        wsStatus = SubStepStatus.PENDING,
        overallStatus = OverallIpStatus.PHASE1_FAIL,
        failureCause = FailureCause.TLS_CERT_ERROR,
        detailMessageFa = "عدم تطابق یا نامعتبر بودن گواهی TLS برای SNI: $sni",
        detailMessageEn = "TLS certificate/hostname verification failed for $sni",
        phase1Qualified = false,
      )
    } catch (e: SSLException) {
      val certLike =
        e.message?.contains("CertPath", ignoreCase = true) == true ||
          e.message?.contains("Trust anchor", ignoreCase = true) == true
      return Phase1ProbeOutcome(
        ip = candidate.ip,
        ipVersion = candidate.ipVersion,
        port = port,
        sniUsed = sni,
        hostUsed = wsHost,
        icmpPingMs = icmpMs,
        tcpStatus = SubStepStatus.PASS,
        tlsStatus = SubStepStatus.FAIL,
        wsStatus = SubStepStatus.PENDING,
        overallStatus = OverallIpStatus.PHASE1_FAIL,
        failureCause = if (certLike) FailureCause.TLS_CERT_ERROR else FailureCause.TLS_HANDSHAKE_FAILED,
        detailMessageFa =
          if (certLike) "خطای زنجیره گواهی TLS: ${e.message}"
          else "شکست در TLS Handshake: ${e.message}",
        detailMessageEn = "TLS handshake failed: ${e.message}",
        phase1Qualified = false,
      )
    } catch (e: ConnectException) {
      return Phase1ProbeOutcome(
        ip = candidate.ip,
        ipVersion = candidate.ipVersion,
        port = port,
        sniUsed = sni,
        hostUsed = wsHost,
        icmpPingMs = icmpMs,
        tcpStatus = SubStepStatus.FAIL,
        tlsStatus = SubStepStatus.PENDING,
        wsStatus = SubStepStatus.PENDING,
        overallStatus = OverallIpStatus.PHASE1_FAIL,
        failureCause = FailureCause.TCP_CONN_REFUSED,
        detailMessageFa = "اتصال TCP رد شد (Connection Refused)",
        detailMessageEn = "TCP connection refused",
        phase1Qualified = false,
      )
    } catch (e: NoRouteToHostException) {
      return Phase1ProbeOutcome(
        ip = candidate.ip,
        ipVersion = candidate.ipVersion,
        port = port,
        sniUsed = sni,
        hostUsed = wsHost,
        icmpPingMs = icmpMs,
        tcpStatus = SubStepStatus.FAIL,
        tlsStatus = SubStepStatus.PENDING,
        wsStatus = SubStepStatus.PENDING,
        overallStatus = OverallIpStatus.PHASE1_FAIL,
        failureCause = FailureCause.TCP_UNREACHABLE,
        detailMessageFa = "مسیر شبکه به IP در دسترس نیست (No route to host)",
        detailMessageEn = "No route to host",
        phase1Qualified = false,
      )
    } catch (e: Exception) {
      val msg = e.message ?: e::class.java.simpleName
      return Phase1ProbeOutcome(
        ip = candidate.ip,
        ipVersion = candidate.ipVersion,
        port = port,
        sniUsed = sni,
        hostUsed = wsHost,
        icmpPingMs = icmpMs,
        tcpStatus = if (tcpConnected) SubStepStatus.PASS else SubStepStatus.FAIL,
        tlsStatus = if (tcpConnected) SubStepStatus.FAIL else SubStepStatus.PENDING,
        wsStatus = SubStepStatus.PENDING,
        overallStatus = OverallIpStatus.PHASE1_FAIL,
        failureCause = if (tcpConnected) FailureCause.TLS_HANDSHAKE_FAILED else FailureCause.TCP_UNREACHABLE,
        detailMessageFa = "خطا در بررسی اولیه: $msg",
        detailMessageEn = "Probe error: $msg",
        phase1Qualified = false,
      )
    } finally {
      runCatching { sslSocket?.close() }
      runCatching { tcpSocket?.close() }
    }
  }

  private fun probeWebSocket(
    activeSocket: Socket,
    candidate: IpCandidate,
    port: Int,
    sni: String,
    wsHost: String,
    wsPath: String,
    icmpMs: Long?,
    tcpMs: Long,
    tlsStatus: SubStepStatus,
    tlsMs: Long?,
    wsTimeoutMs: Int,
  ): Phase1ProbeOutcome {
    return try {
      activeSocket.soTimeout = wsTimeoutMs
      val keyBytes = ByteArray(16)
      random.nextBytes(keyBytes)
      val secKey = encodeBase64(keyBytes)
      val normalizedPath = if (wsPath.startsWith('/')) wsPath else "/$wsPath"

      val req =
        buildString {
          append("GET ").append(normalizedPath).append(" HTTP/1.1\r\n")
          append("Host: ").append(wsHost).append("\r\n")
          append("Upgrade: websocket\r\n")
          append("Connection: Upgrade\r\n")
          append("Sec-WebSocket-Key: ").append(secKey).append("\r\n")
          append("Sec-WebSocket-Version: 13\r\n")
          append("User-Agent: CloudFrontScanner/1.0\r\n")
          append("\r\n")
        }

      val wsStart = System.nanoTime()
      val out = activeSocket.getOutputStream()
      out.write(req.toByteArray(Charsets.UTF_8))
      out.flush()

      val reader = BufferedReader(InputStreamReader(activeSocket.getInputStream(), Charsets.UTF_8))
      val statusLine = reader.readLine().orEmpty()
      val wsMs = ((System.nanoTime() - wsStart) / 1_000_000L).coerceAtLeast(1L)

      val code =
        statusLine
          .split(' ')
          .getOrNull(1)
          ?.toIntOrNull()

      if (code == 101) {
        Phase1ProbeOutcome(
          ip = candidate.ip,
          ipVersion = candidate.ipVersion,
          port = port,
          sniUsed = sni,
          hostUsed = wsHost,
          icmpPingMs = icmpMs,
          tcpStatus = SubStepStatus.PASS,
          tcpMs = tcpMs,
          tlsStatus = tlsStatus,
          tlsMs = tlsMs,
          wsStatus = SubStepStatus.PASS,
          wsMs = wsMs,
          wsHttpCode = 101,
          overallStatus = OverallIpStatus.WS_PASS,
          failureCause = FailureCause.NONE,
          detailMessageFa = "ارتقاء WebSocket موفق (101 Switching Protocols) — آماده تست تونل",
          detailMessageEn = "WebSocket upgrade succeeded (HTTP 101 Switching Protocols)",
          phase1Qualified = true,
        )
      } else {
        Phase1ProbeOutcome(
          ip = candidate.ip,
          ipVersion = candidate.ipVersion,
          port = port,
          sniUsed = sni,
          hostUsed = wsHost,
          icmpPingMs = icmpMs,
          tcpStatus = SubStepStatus.PASS,
          tcpMs = tcpMs,
          tlsStatus = tlsStatus,
          tlsMs = tlsMs,
          wsStatus = SubStepStatus.FAIL,
          wsMs = wsMs,
          wsHttpCode = code,
          overallStatus = OverallIpStatus.PHASE1_FAIL,
          failureCause = FailureCause.WS_UPGRADE_HTTP_ERROR,
          detailMessageFa =
            "WebSocket رد شد: پاسخ HTTP ${code ?: "نامشخص"} ($statusLine)",
          detailMessageEn = "WebSocket upgrade rejected: HTTP ${code ?: "unknown"} ($statusLine)",
          phase1Qualified = false,
        )
      }
    } catch (_: SocketTimeoutException) {
      Phase1ProbeOutcome(
        ip = candidate.ip,
        ipVersion = candidate.ipVersion,
        port = port,
        sniUsed = sni,
        hostUsed = wsHost,
        icmpPingMs = icmpMs,
        tcpStatus = SubStepStatus.PASS,
        tcpMs = tcpMs,
        tlsStatus = tlsStatus,
        tlsMs = tlsMs,
        wsStatus = SubStepStatus.FAIL,
        wsMs = null,
        overallStatus = OverallIpStatus.PHASE1_FAIL,
        failureCause = FailureCause.WS_TIMEOUT,
        detailMessageFa = "تایم‌اوت در Handshake وب‌سوکت",
        detailMessageEn = "WebSocket handshake timeout",
        phase1Qualified = false,
      )
    } catch (e: Exception) {
      Phase1ProbeOutcome(
        ip = candidate.ip,
        ipVersion = candidate.ipVersion,
        port = port,
        sniUsed = sni,
        hostUsed = wsHost,
        icmpPingMs = icmpMs,
        tcpStatus = SubStepStatus.PASS,
        tcpMs = tcpMs,
        tlsStatus = tlsStatus,
        tlsMs = tlsMs,
        wsStatus = SubStepStatus.FAIL,
        wsMs = null,
        overallStatus = OverallIpStatus.PHASE1_FAIL,
        failureCause = FailureCause.WS_FAILED,
        detailMessageFa = "خطا در Handshake وب‌سوکت: ${e.message}",
        detailMessageEn = "WebSocket handshake error: ${e.message}",
        phase1Qualified = false,
      )
    }
  }

  /**
   * Executes genuine /system/bin/ping or ping6 if permitted on device without root.
   * Returns null if blocked/unavailable; never synthesizes a fake number.
   */
  private fun runRealIcmpPing(ip: String, ipVersion: IpVersion): Long? {
    return try {
      val cmd = if (ipVersion == IpVersion.IPV6) "ping6" else "ping"
      val proc = ProcessBuilder(cmd, "-c", "1", "-W", "2", ip).redirectErrorStream(true).start()
      val completed = proc.waitFor(2500, TimeUnit.MILLISECONDS)
      if (!completed) {
        proc.destroyForcibly()
        return null
      }
      if (proc.exitValue() != 0) return null
      val output = proc.inputStream.bufferedReader().readText()
      val match = Regex("time=([0-9]+(?:\\.[0-9]+)?)\\s*ms").find(output)
      match?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.toLong()?.coerceAtLeast(1L)
    } catch (_: Exception) {
      null
    }
  }

  @Suppress("CustomX509TrustManager", "TrustAllX509TrustManager")
  private fun createInsecureDiagnosticSslFactory(): SSLSocketFactory {
    val trustAll =
      arrayOf<TrustManager>(
        object : X509TrustManager {
          override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

          override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

          override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
      )
    val ctx = SSLContext.getInstance("TLS")
    ctx.init(null, trustAll, random)
    return ctx.socketFactory
  }

  private fun encodeBase64(bytes: ByteArray): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val sb = StringBuilder(((bytes.size + 2) / 3) * 4)
    var i = 0
    while (i < bytes.size) {
      val b0 = bytes[i].toInt() and 0xFF
      val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
      val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
      sb.append(alphabet[(b0 ushr 2) and 0x3F])
      sb.append(alphabet[((b0 and 0x03) shl 4) or ((b1 ushr 4) and 0x0F)])
      sb.append(if (i + 1 < bytes.size) alphabet[((b1 and 0x0F) shl 2) or ((b2 ushr 6) and 0x03)] else '=')
      sb.append(if (i + 2 < bytes.size) alphabet[b2 and 0x3F] else '=')
      i += 3
    }
    return sb.toString()
  }
}
