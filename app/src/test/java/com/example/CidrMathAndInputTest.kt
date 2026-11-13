package com.example

import com.example.core.network.CidrMath
import com.example.domain.model.AwsPrefixEntry
import com.example.domain.model.IpFamilyPreference
import com.example.domain.model.IpVersion
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CidrMathAndInputTest {

  @Test
  fun `auto sampling 50_50 produces balanced IPv4 and IPv6 without duplicates`() {
    val v4Prefixes =
      listOf(
        AwsPrefixEntry("18.160.0.0/15", IpVersion.IPV4, "CLOUDFRONT", "GLOBAL"),
        AwsPrefixEntry("54.192.0.0/16", IpVersion.IPV4, "CLOUDFRONT", "GLOBAL"),
      )
    val v6Prefixes =
      listOf(
        AwsPrefixEntry("2600:9000::/28", IpVersion.IPV6, "CLOUDFRONT", "GLOBAL"),
        AwsPrefixEntry("2400:6500:0::/48", IpVersion.IPV6, "CLOUDFRONT", "GLOBAL"),
      )

    val report =
      CidrMath.sampleAutoCloudFront(
        v4Prefixes = v4Prefixes,
        v6Prefixes = v6Prefixes,
        familyPref = IpFamilyPreference.BOTH_50_50,
        requestedCount = 100,
        random = Random(42),
      )

    assertEquals(100, report.candidates.size)
    assertEquals(50, report.v4Count)
    assertEquals(50, report.v6Count)
    assertEquals(100, report.candidates.map { it.ip }.toSet().size)
  }

  @Test
  fun `auto sampling fills remainder when one family is small and reports actual ratio`() {
    val tinyV6 = listOf(AwsPrefixEntry("2600:9000::/126", IpVersion.IPV6, "CLOUDFRONT", "GLOBAL"))
    val largeV4 = listOf(AwsPrefixEntry("18.160.0.0/15", IpVersion.IPV4, "CLOUDFRONT", "GLOBAL"))

    val report =
      CidrMath.sampleAutoCloudFront(
        v4Prefixes = largeV4,
        v6Prefixes = tinyV6,
        familyPref = IpFamilyPreference.BOTH_50_50,
        requestedCount = 20,
        random = Random(7),
      )

    assertEquals(20, report.candidates.size)
    assertTrue(report.v6Count <= 4)
    assertTrue(report.v4Count >= 16)
    assertTrue(report.ratioDescriptionEn.contains("Actual ratio"))
  }

  @Test
  fun `huge IPv6 and IPv4 CIDRs sample safely without expanding memory`() {
    val hugeV6 = CidrMath.parseCidr("2600:9000::/28")
    assertNotNull(hugeV6)
    assertEquals(100, hugeV6!!.hostBits)

    val sampled =
      CidrMath.sampleFromPrefixes(
        prefixes = listOf(AwsPrefixEntry("2600:9000::/28", IpVersion.IPV6, "CLOUDFRONT", "GLOBAL")),
        targetCount = 50,
        alreadySeen = mutableSetOf(),
        random = Random(99),
      )
    assertEquals(50, sampled.size)
    assertEquals(50, sampled.map { it.ip }.toSet().size)
  }

  @Test
  fun `manual input preserves all valid IPv4 and IPv6 regardless of 50_50 rule and deduplicates`() {
    val raw =
      """
      # Comment line
      18.160.1.1
      18.160.1.2
      18.160.1.3
      18.160.1.1
      2600:9000::1
      999.10.10.10
      not_an_ip
      192.0.2.0/30
      """.trimIndent()

    val report = CidrMath.parseManualInput(raw, cidrSampleCap = 10, cidrScanAllUpTo = 256)
    val ips = report.uniqueIps.map { it.ip }

    // 3 unique IPv4 + 1 IPv6 + 2 usable hosts from 192.0.2.0/30 (.1 and .2) = 6
    assertEquals(6, report.uniqueIps.size)
    assertEquals(1, report.duplicatesRemoved)
    assertEquals(2, report.errors.size)
    assertEquals(7, report.errors[0].lineNumber)
    assertEquals(8, report.errors[1].lineNumber)
    assertTrue(ips.contains("18.160.1.1"))
    assertTrue(ips.contains("18.160.1.2"))
    assertTrue(ips.contains("18.160.1.3"))
    assertTrue(ips.contains("2600:9000:0:0:0:0:0:1"))
    assertFalse(ips.contains("999.10.10.10"))
  }

  @Test
  fun `manual large CIDR is capped instead of expanding millions of IPs`() {
    val report =
      CidrMath.parseManualInput(
        rawText = "10.0.0.0/8",
        cidrSampleCap = 25,
        cidrScanAllUpTo = 256,
        random = Random(1),
      )
    assertEquals(25, report.uniqueIps.size)
    assertEquals(1, report.cidrNotices.size)
    assertTrue(report.cidrNotices.first().wasSampled)
  }
}
