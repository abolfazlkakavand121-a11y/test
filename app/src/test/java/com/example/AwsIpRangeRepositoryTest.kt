package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.network.AwsIpRangeRepository
import com.example.data.local.AppDatabase
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AwsIpRangeRepositoryTest {

  private lateinit var db: AppDatabase

  private val sampleAwsJson =
    """
    {
      "syncToken": "1794524225",
      "createDate": "2026-11-12-22-57-05",
      "prefixes": [
        {"ip_prefix": "18.160.0.0/15", "region": "GLOBAL", "service": "CLOUDFRONT", "network_border_group": "GLOBAL"},
        {"ip_prefix": "52.84.0.0/15", "region": "us-east-1", "service": "CLOUDFRONT", "network_border_group": "us-east-1"},
        {"ip_prefix": "15.158.0.0/16", "region": "GLOBAL", "service": "CLOUDFRONT_ORIGIN_FACING", "network_border_group": "GLOBAL"},
        {"ip_prefix": "3.5.140.0/22", "region": "ap-northeast-2", "service": "S3", "network_border_group": "ap-northeast-2"}
      ],
      "ipv6_prefixes": [
        {"ipv6_prefix": "2600:9000::/28", "region": "GLOBAL", "service": "CLOUDFRONT", "network_border_group": "GLOBAL"},
        {"ipv6_prefix": "2600:9000:eee::/48", "region": "eusc-de-east-1", "service": "CLOUDFRONT", "network_border_group": "eusc-de-east-1"},
        {"ipv6_prefix": "2a05:d07a:a000::/40", "region": "GLOBAL", "service": "CLOUDFRONT_ORIGIN_FACING", "network_border_group": "GLOBAL"}
      ]
    }
    """.trimIndent()

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
  fun `parses ONLY CLOUDFRONT GLOBAL for main pool and separates ORIGIN_FACING and regional`() {
    val snap = AwsIpRangeRepository.parseOfficialAwsJson(sampleAwsJson, 1_000_000L, isFromCache = false)
    assertEquals("1794524225", snap.syncToken)
    assertEquals("2026-11-12-22-57-05", snap.createDate)

    // Primary Edge pool must ONLY contain CLOUDFRONT + GLOBAL
    assertEquals(listOf("18.160.0.0/15"), snap.globalEdgeV4.map { it.cidr })
    assertEquals(listOf("2600:9000::/28"), snap.globalEdgeV6.map { it.cidr })

    // Regional & Origin-Facing must be isolated
    assertEquals(listOf("52.84.0.0/15"), snap.regionalEdgeV4.map { it.cidr })
    assertEquals(listOf("2600:9000:eee::/48"), snap.regionalEdgeV6.map { it.cidr })
    assertEquals(listOf("15.158.0.0/16"), snap.originFacingV4.map { it.cidr })
    assertEquals(listOf("2a05:d07a:a000::/40"), snap.originFacingV6.map { it.cidr })
  }

  @Test
  fun `offline fallback serves cached snapshot with cache and stale flags`() = runTest {
    var shouldFail = false
    val mockClient =
      OkHttpClient.Builder()
        .addInterceptor { chain ->
          if (shouldFail) throw java.io.IOException("Simulated offline")
          val url = chain.request().url.toString()
          val body =
            if (url.contains("ip-ranges.json")) sampleAwsJson
            else """{"CLOUDFRONT_GLOBAL_IP_LIST":["18.160.0.0/15"]}"""
          Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody())
            .build()
        }
        .build()

    val repo = AwsIpRangeRepository(db.scanDao(), mockClient)
    val first = repo.fetchLatestOrCached(forceRefresh = true)
    assertTrue(first.isSuccess)
    assertFalse(first.getOrThrow().isFromCache)
    assertEquals("1794524225", first.getOrThrow().syncToken)

    // Go offline and verify fallback returns cached data marked fromCache
    shouldFail = true
    val offline = repo.fetchLatestOrCached(forceRefresh = true)
    assertTrue(offline.isSuccess)
    val cachedSnap = offline.getOrNull()
    assertNotNull(cachedSnap)
    assertTrue(cachedSnap!!.isFromCache)
    assertTrue(cachedSnap.isStaleWarning)
    assertEquals("1794524225", cachedSnap.syncToken)
  }
}
