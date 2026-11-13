package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.network.AndroidLibXrayRunner
import com.example.core.network.AwsIpRangeRepository
import com.example.core.network.NetworkCapabilityChecker
import com.example.core.network.NetworkStatusSnapshot
import com.example.core.network.Phase1ProbeEngine
import com.example.core.network.XrayTunnelRunner
import com.example.data.local.AppDatabase
import com.example.data.local.ExportFormat
import com.example.data.local.ReportExporter
import com.example.data.local.ScanResultEntity
import com.example.data.local.ScanSessionEntity
import com.example.data.local.SettingsDataStore
import com.example.domain.model.FailureCause
import com.example.domain.model.IpCandidate
import com.example.domain.model.IpVersion
import com.example.domain.model.ManualParseReport
import com.example.domain.model.OverallIpStatus
import com.example.domain.model.Phase2SelectionMode
import com.example.domain.model.Phase2TunnelOutcome
import com.example.domain.model.ScanMode
import com.example.domain.model.ScanPhase
import com.example.domain.model.ScanSettings
import com.example.domain.model.TunnelTestUrl
import com.example.domain.model.VlessConfig
import com.example.scanner.ScanCoordinator
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReportExporterAndCoordinatorTest {

  private lateinit var context: Context
  private lateinit var db: AppDatabase

  @Before
  fun setup() {
    context = ApplicationProvider.getApplicationContext()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun `report exporter generates TXT, CSV, and JSON while redacting UUIDs`() {
    val secretUuid = "abcdef12-3456-7890-abcd-ef1234567890"
    val session =
      ScanSessionEntity(
        sessionId = "sess01",
        createdAtMillis = 1794532000000L,
        updatedAtMillis = 1794532005000L,
        scanMode = "AUTO_CLOUDFRONT",
        ipFamilyPreference = "BOTH_50_50",
        scanPreset = "BALANCED",
        awsSyncToken = "1794524225",
        awsCreateDate = "2026-11-12-22-57-05",
        awsIsFromCache = false,
        vlessRedactedSummary = "VLESS ($secretUuid) | port=443",
        settingsSnapshotJson = """{"defaultPort":443}""",
        phase = "COMPLETED",
        totalInputs = 2,
        validInputs = 2,
        duplicatesRemoved = 0,
        invalidInputsCount = 0,
        phase1CompletedCount = 2,
        tcpPassCount = 2,
        tcpFailCount = 0,
        tlsPassCount = 1,
        tlsFailCount = 1,
        wsPassCount = 1,
        wsFailCount = 0,
        skippedCount = 0,
        phase2TargetCount = 1,
        phase2CompletedCount = 1,
        tunnelPassCount = 1,
        tunnelFailCount = 0,
        inconclusiveCount = 0,
        ratioNote = "50/50",
      )
    val results =
      listOf(
        ScanResultEntity(
          sessionId = "sess01",
          ip = "18.160.1.1",
          ipVersion = "IPV4",
          sourceCidr = "18.160.0.0/15",
          isManual = false,
          port = 443,
          sniUsed = "cdn.test",
          hostUsed = "cdn.test",
          overallStatus = "TUNNEL_PASS",
          icmpPingMs = null,
          tcpStatus = "PASS",
          tcpMs = 45,
          tlsStatus = "PASS",
          tlsMs = 95,
          wsStatus = "PASS",
          wsMs = 120,
          wsHttpCode = 101,
          tunnelStatus = "TUNNEL_PASS",
          tunnelAttempts = 3,
          tunnelSuccesses = 3,
          tunnelTimeoutsOrDrops = 0,
          tunnelMedianMs = 210,
          tunnelMinMs = 190,
          tunnelMaxMs = 240,
          externalIpObserved = null,
          downloadKbps = null,
          stabilityScore = 100.0,
          failureCause = "NONE",
          detailMessageFa = "تونل موفق",
          detailMessageEn = "TUNNEL_PASS",
          phase1Qualified = true,
          updatedAtMillis = 1794532005000L,
        )
      )

    val txt = ReportExporter.buildContent(ExportFormat.TXT, session, results)
    val csv = ReportExporter.buildContent(ExportFormat.CSV, session, results)
    val json = ReportExporter.buildContent(ExportFormat.JSON, session, results)

    assertFalse(txt.contains(secretUuid))
    assertFalse(csv.contains(secretUuid))
    assertFalse(json.contains(secretUuid))
    assertTrue(txt.contains("18.160.1.1"))
    assertTrue(csv.contains("aws_sync_token=1794524225"))

    val parsedJson = JSONObject(json)
    assertEquals("1794524225", parsedJson.getString("awsSyncToken"))
    assertEquals(1, parsedJson.getJSONArray("results").length())
  }

  @Test
  fun `median and outbound stats parser work accurately`() {
    assertEquals(200L, AndroidLibXrayRunner.computeMedian(listOf(350L, 120L, 200L)))
    assertEquals(200L, AndroidLibXrayRunner.computeMedian(listOf(100L, 150L, 250L, 400L)))
    val stats =
      AndroidLibXrayRunner.parseStatsMap("cf-proxy,uplink,1240;cf-proxy,downlink,3920;")
    assertEquals(1240L, stats["cf-proxy:uplink"])
    assertEquals(3920L, stats["cf-proxy:downlink"])
  }

  @Test
  fun `forced SOCKS5 client fails when proxy port is closed and NEVER falls back to direct internet`() {
    val closedSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val deadPort = closedSocket.localPort
    closedSocket.close()

    val runner = AndroidLibXrayRunner(context)
    var threw = false
    try {
      runner.executeRequestStrictlyViaSocks5(
        localSocksPort = deadPort,
        testUrl =
          TunnelTestUrl(
            id = "test",
            url = "https://www.gstatic.com/generate_204",
            expectedStatusCode = 204,
            timeoutMs = 1500,
          ),
        allowInsecureTlsDiagnostic = false,
      )
    } catch (_: Exception) {
      threw = true
    }
    assertTrue("Must throw when local SOCKS5 proxy is dead instead of direct fallback", threw)
  }

  @Test
  fun `ScanCoordinator persists Phase 1 and Phase 2 pass and fail results and logs`() = runTest {
    val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val openPort = server.localPort
    val acceptThread =
      thread {
        repeat(2) { runCatching { server.accept().close() } }
      }

    try {
      val settingsStore = SettingsDataStore(context)
      val awsRepo = AwsIpRangeRepository(db.scanDao())
      val netChecker =
        object : NetworkCapabilityChecker {
          override fun checkNetworkStatus() =
            NetworkStatusSnapshot(hasInternet = true, hasGlobalIpv6 = false)
        }
      val phase1 = Phase1ProbeEngine(netChecker)
      val fakeXray =
        object : XrayTunnelRunner {
          override fun xrayVersion() = "test-xray"

          override suspend fun runTunnelTest(
            candidate: IpCandidate,
            vlessConfig: VlessConfig,
            settings: ScanSettings,
          ): Phase2TunnelOutcome {
            return if (candidate.ip == "127.0.0.1") {
              Phase2TunnelOutcome(
                ip = candidate.ip,
                ipVersion = candidate.ipVersion,
                attempts = 3,
                successes = 3,
                timeoutsOrDrops = 0,
                tunnelStatus = OverallIpStatus.TUNNEL_PASS,
                medianMs = 180L,
                minMs = 160L,
                maxMs = 210L,
                failureCause = FailureCause.NONE,
                detailMessageFa = "موفق",
                detailMessageEn = "TUNNEL_PASS",
                stabilityScore = 100.0,
              )
            } else {
              Phase2TunnelOutcome(
                ip = candidate.ip,
                ipVersion = candidate.ipVersion,
                attempts = 3,
                successes = 0,
                timeoutsOrDrops = 3,
                tunnelStatus = OverallIpStatus.TUNNEL_FAIL,
                failureCause = FailureCause.TUNNEL_REQUEST_TIMEOUT,
                detailMessageFa = "ناموفق",
                detailMessageEn = "TUNNEL_REQUEST_TIMEOUT",
                stabilityScore = 0.0,
              )
            }
          }
        }

      val coordinator =
        ScanCoordinator(db.scanDao(), settingsStore, awsRepo, phase1, fakeXray)

      val settings =
        ScanSettings(
          scanMode = ScanMode.MANUAL_INPUT,
          defaultPort = openPort,
          tcpTimeoutMs = 1000,
          phase1Workers = 2,
        )
      val manual =
        ManualParseReport(
          uniqueIps =
            listOf(
              IpCandidate("127.0.0.1", IpVersion.IPV4, isManual = true),
              IpCandidate("2600:9000::1", IpVersion.IPV6, isManual = true),
            ),
          totalInputEntries = 2,
          validEntriesBeforeDedup = 2,
          duplicatesRemoved = 0,
          errors = emptyList(),
          cidrNotices = emptyList(),
        )

      coordinator.startPhase1Scan(settings, vlessConfig = null, awsSnapshot = null, manualReport = manual)

      // Wait until Phase 1 finishes on Dispatchers.IO
      var waited = 0
      while (
        coordinator.liveState.value.phase != ScanPhase.PHASE1_COMPLETED &&
          coordinator.liveState.value.lastErrorBanner == null &&
          waited < 100
      ) {
        Thread.sleep(50)
        waited++
      }
      assertEquals(null, coordinator.liveState.value.lastErrorBanner)
      assertEquals(ScanPhase.PHASE1_COMPLETED, coordinator.liveState.value.phase)
      val sid = coordinator.liveState.value.activeSessionId!!
      val p1Results = db.scanDao().getResultsForSession(sid)
      assertEquals(2, p1Results.size)
      assertEquals(1, coordinator.liveState.value.qualifiedPhase1Count)
      assertEquals(1, coordinator.liveState.value.skippedCount)

      val vless =
        VlessConfig(
          rawUri = "vless://uuid@h:$openPort?type=ws&security=none&host=t&path=/",
          uuid = "uuid",
          address = "h",
          port = openPort,
          network = "ws",
          security = "none",
          wsHost = "t",
          wsPath = "/",
        )
      coordinator.startPhase2TunnelScan(
        sessionId = sid,
        settings = settings,
        vlessConfig = vless,
        selectionMode = Phase2SelectionMode.ALL,
        customCount = 50,
      )
      waited = 0
      while (
        coordinator.liveState.value.phase != ScanPhase.COMPLETED &&
          coordinator.liveState.value.lastErrorBanner == null &&
          waited < 100
      ) {
        Thread.sleep(50)
        waited++
      }
      assertEquals(null, coordinator.liveState.value.lastErrorBanner)
      assertEquals(ScanPhase.COMPLETED, coordinator.liveState.value.phase)
      assertEquals(1, coordinator.liveState.value.tunnelPassCount)

      val logs = db.scanDao().getAllLogsForSession(sid)
      assertTrue(logs.any { it.eventCode == "TUNNEL_PASS" })
      assertTrue(logs.any { it.level == "SKIP" })
    } finally {
      runCatching { server.close() }
      acceptThread.join(1000)
    }
  }
}
