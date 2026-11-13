package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.network.AwsIpRangeRepository
import com.example.core.network.CidrMath
import com.example.core.network.NetworkCapabilityChecker
import com.example.core.network.NetworkStatusSnapshot
import com.example.core.network.Phase1ProbeEngine
import com.example.data.local.AppDatabase
import com.example.domain.model.IpFamilyPreference
import com.example.domain.model.OverallIpStatus
import com.example.domain.model.ScanSettings
import com.example.domain.model.SubStepStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real-network integration verification test (distinct from mocked/loopback unit tests).
 * Tests fetching live AWS ip-ranges.json over HTTPS and probing real sampled CloudFront GLOBAL edge IPs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LiveNetworkVerificationTest {

  private lateinit var db: AppDatabase

  @Before
  fun setup() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun `live fetch AWS ip-ranges json and Phase 1 TCP probe on sampled GLOBAL CloudFront IP`() =
    runTest {
      val repo = AwsIpRangeRepository(db.scanDao())
      val res = repo.fetchLatestOrCached(forceRefresh = true)
      assertTrue("Expected live AWS ip-ranges.json fetch to succeed", res.isSuccess)
      val snap = res.getOrThrow()
      assertTrue("Expected > 50 GLOBAL IPv4 prefixes", snap.globalEdgeV4.size > 50)
      assertTrue("Expected > 10 GLOBAL IPv6 prefixes", snap.globalEdgeV6.size > 10)
      assertTrue("Expected syncToken non-empty", snap.syncToken.isNotBlank())
      assertTrue("Expected createDate non-empty", snap.createDate.isNotBlank())

      val sample =
        CidrMath.sampleAutoCloudFront(
          v4Prefixes = snap.globalEdgeV4,
          v6Prefixes = snap.globalEdgeV6,
          familyPref = IpFamilyPreference.IPV4_ONLY,
          requestedCount = 5,
        )
      assertTrue(sample.candidates.isNotEmpty())

      val checker =
        object : NetworkCapabilityChecker {
          override fun checkNetworkStatus() =
            NetworkStatusSnapshot(hasInternet = true, hasGlobalIpv6 = false)
        }
      val engine = Phase1ProbeEngine(checker)
      val outcomes =
        sample.candidates.map { cand ->
          engine.probeCandidate(cand, ScanSettings(defaultPort = 443, tcpTimeoutMs = 3000), null)
        }
      assertTrue(
        "At least one sampled CloudFront GLOBAL edge IP should accept TCP 443",
        outcomes.any {
          it.tcpStatus == SubStepStatus.PASS && it.overallStatus == OverallIpStatus.TCP_PASS
        },
      )
    }
}
