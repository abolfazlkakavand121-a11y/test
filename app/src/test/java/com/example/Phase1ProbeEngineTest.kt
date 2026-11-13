package com.example

import com.example.core.network.NetworkCapabilityChecker
import com.example.core.network.NetworkStatusSnapshot
import com.example.core.network.Phase1ProbeEngine
import com.example.domain.model.FailureCause
import com.example.domain.model.IpCandidate
import com.example.domain.model.IpVersion
import com.example.domain.model.OverallIpStatus
import com.example.domain.model.ScanSettings
import com.example.domain.model.SubStepStatus
import com.example.domain.model.VlessConfig
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase1ProbeEngineTest {

  @Test
  fun `IPv6 candidate is SKIPPED as UNSUPPORTED_NETWORK when network lacks IPv6`() = runTest {
    val checker =
      object : NetworkCapabilityChecker {
        override fun checkNetworkStatus() =
          NetworkStatusSnapshot(hasInternet = true, hasGlobalIpv6 = false)
      }
    val engine = Phase1ProbeEngine(checker)
    val outcome =
      engine.probeCandidate(
        candidate = IpCandidate("2600:9000::1", IpVersion.IPV6),
        settings = ScanSettings(),
        vlessConfig = null,
      )
    assertEquals(OverallIpStatus.SKIPPED, outcome.overallStatus)
    assertEquals(FailureCause.UNSUPPORTED_NETWORK_IPV6, outcome.failureCause)
    assertFalse(outcome.phase1Qualified)
  }

  @Test
  fun `plain TCP open without SNI reports TCP_PASS and TLS NOT_CONFIGURED`() = runTest {
    val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val port = server.localPort
    val t = thread { runCatching { server.accept().close() } }
    try {
      val checker =
        object : NetworkCapabilityChecker {
          override fun checkNetworkStatus() =
            NetworkStatusSnapshot(hasInternet = true, hasGlobalIpv6 = false)
        }
      val engine = Phase1ProbeEngine(checker)
      val outcome =
        engine.probeCandidate(
          candidate = IpCandidate("127.0.0.1", IpVersion.IPV4),
          settings = ScanSettings(defaultPort = port, tcpTimeoutMs = 1500),
          vlessConfig = null,
        )
      assertEquals(SubStepStatus.PASS, outcome.tcpStatus)
      assertEquals(SubStepStatus.NOT_CONFIGURED, outcome.tlsStatus)
      assertEquals(OverallIpStatus.TCP_PASS, outcome.overallStatus)
      assertTrue(outcome.phase1Qualified)
    } finally {
      runCatching { server.close() }
      t.join(1000)
    }
  }

  @Test
  fun `plain HTTP server rejecting TLS handshake reports TCP PASS and TLS FAIL`() = runTest {
    val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val port = server.localPort
    val t =
      thread {
        runCatching {
          server.accept().use { s ->
            s.getOutputStream().write("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray())
            s.getOutputStream().flush()
          }
        }
      }
    try {
      val checker =
        object : NetworkCapabilityChecker {
          override fun checkNetworkStatus() =
            NetworkStatusSnapshot(hasInternet = true, hasGlobalIpv6 = false)
        }
      val engine = Phase1ProbeEngine(checker)
      val vless =
        VlessConfig(
          rawUri = "vless://u@h:$port?type=ws&security=tls&sni=example.com&host=example.com&path=/ws",
          uuid = "u",
          address = "h",
          port = port,
          network = "ws",
          security = "tls",
          sni = "example.com",
          wsHost = "example.com",
          wsPath = "/ws",
        )
      val outcome =
        engine.probeCandidate(
          candidate = IpCandidate("127.0.0.1", IpVersion.IPV4),
          settings = ScanSettings(tcpTimeoutMs = 1500, tlsTimeoutMs = 1500),
          vlessConfig = vless,
        )
      assertEquals(SubStepStatus.PASS, outcome.tcpStatus)
      assertEquals(SubStepStatus.FAIL, outcome.tlsStatus)
      assertEquals(OverallIpStatus.PHASE1_FAIL, outcome.overallStatus)
      assertFalse(outcome.phase1Qualified)
    } finally {
      runCatching { server.close() }
      t.join(1000)
    }
  }

  @Test
  fun `plaintext WebSocket 101 vs 403 rejection are accurately distinguished`() = runTest {
    val server101 = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val t1 =
      thread {
        runCatching {
          server101.accept().use { s ->
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            while (reader.readLine().orEmpty().isNotEmpty()) {
              // drain headers
            }
            s.getOutputStream()
              .write(
                ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n")
                  .toByteArray()
              )
            s.getOutputStream().flush()
          }
        }
      }

    val server403 = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val t2 =
      thread {
        runCatching {
          server403.accept().use { s ->
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            while (reader.readLine().orEmpty().isNotEmpty()) {
              // drain headers
            }
            s.getOutputStream()
              .write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n".toByteArray())
            s.getOutputStream().flush()
          }
        }
      }

    try {
      val checker =
        object : NetworkCapabilityChecker {
          override fun checkNetworkStatus() =
            NetworkStatusSnapshot(hasInternet = true, hasGlobalIpv6 = false)
        }
      val engine = Phase1ProbeEngine(checker)

      val cfg101 =
        VlessConfig(
          rawUri = "vless://u@h:${server101.localPort}?type=ws&security=none&host=cdn.test&path=/ws",
          uuid = "u",
          address = "h",
          port = server101.localPort,
          network = "ws",
          security = "none",
          wsHost = "cdn.test",
          wsPath = "/ws",
        )
      val passRes =
        engine.probeCandidate(IpCandidate("127.0.0.1", IpVersion.IPV4), ScanSettings(), cfg101)
      assertEquals(SubStepStatus.PASS, passRes.wsStatus)
      assertEquals(101, passRes.wsHttpCode)
      assertEquals(OverallIpStatus.WS_PASS, passRes.overallStatus)
      assertTrue(passRes.phase1Qualified)

      val cfg403 = cfg101.copy(port = server403.localPort)
      val failRes =
        engine.probeCandidate(IpCandidate("127.0.0.1", IpVersion.IPV4), ScanSettings(), cfg403)
      assertEquals(SubStepStatus.FAIL, failRes.wsStatus)
      assertEquals(403, failRes.wsHttpCode)
      assertEquals(FailureCause.WS_UPGRADE_HTTP_ERROR, failRes.failureCause)
      assertFalse(failRes.phase1Qualified)
    } finally {
      runCatching { server101.close() }
      runCatching { server403.close() }
      t1.join(1000)
      t2.join(1000)
    }
  }
}
