package com.example.core.network

import com.example.domain.model.AwsPrefixEntry
import com.example.domain.model.IpCandidate
import com.example.domain.model.IpFamilyPreference
import com.example.domain.model.IpVersion
import com.example.domain.model.ManualCidrNotice
import com.example.domain.model.ManualLineError
import com.example.domain.model.ManualParseReport
import java.math.BigInteger
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.security.SecureRandom
import kotlin.math.ln
import kotlin.math.min
import kotlin.random.Random

data class ParsedCidr(
  val raw: String,
  val ipVersion: IpVersion,
  val networkAddress: BigInteger,
  val prefixLength: Int,
  val hostBits: Int,
  val hostCount: BigInteger,
)

data class AutoSampleReport(
  val candidates: List<IpCandidate>,
  val requestedCount: Int,
  val v4Count: Int,
  val v6Count: Int,
  val ratioDescriptionFa: String,
  val ratioDescriptionEn: String,
)

object CidrMath {

  private val IPV4_MASK = BigInteger("FFFFFFFF", 16)
  private val IPV6_MASK = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF", 16)

  fun detectIpVersion(ipLiteral: String): IpVersion? {
    val cleaned = ipLiteral.trim().removePrefix("[").removeSuffix("]")
    if (cleaned.isEmpty()) return null
    return when {
      isStrictIpv4Literal(cleaned) -> IpVersion.IPV4
      isStrictIpv6Literal(cleaned) -> IpVersion.IPV6
      else -> null
    }
  }

  fun isStrictIpv4Literal(text: String): Boolean {
    val parts = text.split('.')
    if (parts.size != 4) return false
    return parts.all { part ->
      if (part.isEmpty() || part.length > 3) return@all false
      if (part.length > 1 && part.startsWith('0')) return@all false
      val n = part.toIntOrNull() ?: return@all false
      n in 0..255
    }
  }

  fun isStrictIpv6Literal(text: String): Boolean {
    if (!text.contains(':')) return false
    // Avoid DNS resolution by checking characters first
    if (!text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) {
      return false
    }
    return try {
      val addr = InetAddress.getByName(text)
      addr is Inet6Address
    } catch (_: Exception) {
      false
    }
  }

  fun normalizeIp(ipLiteral: String): Pair<String, IpVersion>? {
    val cleaned = ipLiteral.trim().removePrefix("[").removeSuffix("]")
    val version = detectIpVersion(cleaned) ?: return null
    return try {
      val addr = InetAddress.getByName(cleaned)
      val host = addr.hostAddress ?: return null
      host to version
    } catch (_: Exception) {
      null
    }
  }

  fun parseCidr(cidrStr: String): ParsedCidr? {
    val trimmed = cidrStr.trim()
    val slashIdx = trimmed.indexOf('/')
    if (slashIdx <= 0 || slashIdx == trimmed.lastIndex) return null
    val ipPart = trimmed.substring(0, slashIdx).trim().removePrefix("[").removeSuffix("]")
    val prefixLen = trimmed.substring(slashIdx + 1).trim().toIntOrNull() ?: return null
    val version = detectIpVersion(ipPart) ?: return null
    val maxBits = if (version == IpVersion.IPV4) 32 else 128
    if (prefixLen !in 0..maxBits) return null

    val addrBytes =
      try {
        InetAddress.getByName(ipPart).address
      } catch (_: Exception) {
        return null
      }
    val rawInt = BigInteger(1, addrBytes)
    val hostBits = maxBits - prefixLen
    val totalMask = if (version == IpVersion.IPV4) IPV4_MASK else IPV6_MASK
    val netMask =
      if (hostBits == maxBits) BigInteger.ZERO
      else totalMask.shiftRight(hostBits).shiftLeft(hostBits).and(totalMask)
    val netAddr = rawInt.and(netMask)
    val hostCount = BigInteger.ONE.shiftLeft(hostBits)

    return ParsedCidr(
      raw = trimmed,
      ipVersion = version,
      networkAddress = netAddr,
      prefixLength = prefixLen,
      hostBits = hostBits,
      hostCount = hostCount,
    )
  }

  fun ipAtOffset(cidr: ParsedCidr, offset: BigInteger): String {
    require(offset >= BigInteger.ZERO && offset < cidr.hostCount) {
      "Offset $offset out of range for CIDR ${cidr.raw}"
    }
    val target = cidr.networkAddress.add(offset)
    val byteLen = if (cidr.ipVersion == IpVersion.IPV4) 4 else 16
    val rawBytes = target.toByteArray()
    val fixedBytes = ByteArray(byteLen)
    val copyLen = min(rawBytes.size, byteLen)
    System.arraycopy(
      rawBytes,
      rawBytes.size - copyLen,
      fixedBytes,
      byteLen - copyLen,
      copyLen,
    )
    return InetAddress.getByAddress(fixedBytes).hostAddress!!
  }

  fun randomOffset(hostCount: BigInteger, random: Random): BigInteger {
    if (hostCount <= BigInteger.ONE) return BigInteger.ZERO
    val bitLength = hostCount.bitLength()
    while (true) {
      val bytes = ByteArray((bitLength + 7) / 8)
      random.nextBytes(bytes)
      val candidate = BigInteger(1, bytes)
      // Mask excess top bits
      val excessBits = bytes.size * 8 - bitLength
      val masked = if (excessBits > 0) candidate.shiftRight(excessBits) else candidate
      if (masked < hostCount) return masked
    }
  }

  /**
   * Picks a random usable offset in a CIDR.
   * For IPv4 /0../30, avoids .0 network and .255 broadcast if hostCount >= 4.
   */
  fun randomUsableOffset(cidr: ParsedCidr, random: Random): BigInteger {
    if (cidr.ipVersion == IpVersion.IPV4 && cidr.hostCount >= BigInteger.valueOf(4)) {
      val usableCount = cidr.hostCount.subtract(BigInteger.valueOf(2))
      return randomOffset(usableCount, random).add(BigInteger.ONE)
    }
    return randomOffset(cidr.hostCount, random)
  }

  /**
   * Fair, memory-safe random sampling without expanding huge CIDRs.
   * Weighting uses (1 + ln(hostCount)) so small prefixes (/24, /27) still get meaningful coverage
   * while larger blocks (/14, /15) receive balanced proportional representation without starving small blocks.
   */
  fun sampleFromPrefixes(
    prefixes: List<AwsPrefixEntry>,
    targetCount: Int,
    alreadySeen: MutableSet<String>,
    random: Random = SecureRandom().let { sr ->
      object : Random() {
        override fun nextBits(bitCount: Int): Int = sr.nextInt().ushr(32 - bitCount)
      }
    },
  ): List<IpCandidate> {
    if (prefixes.isEmpty() || targetCount <= 0) return emptyList()
    val parsedList = prefixes.mapNotNull { entry -> parseCidr(entry.cidr)?.let { entry to it } }
    if (parsedList.isEmpty()) return emptyList()

    val weights =
      parsedList.map { (_, parsed) ->
        val bits = parsed.hostBits.coerceAtMost(32)
        1.0 + ln((1L shl bits).toDouble()) * 0.65
      }
    val totalWeight = weights.sum()
    val cumulative = DoubleArray(weights.size)
    var running = 0.0
    for (i in weights.indices) {
      running += weights[i]
      cumulative[i] = running
    }

    val result = mutableListOf<IpCandidate>()
    val maxAttempts = (targetCount * 25).coerceAtLeast(500)
    var attempts = 0
    while (result.size < targetCount && attempts < maxAttempts) {
      attempts++
      val r = random.nextDouble() * totalWeight
      var idx = cumulative.binarySearch(r)
      if (idx < 0) idx = -idx - 1
      if (idx >= parsedList.size) idx = parsedList.lastIndex

      val (entry, parsed) = parsedList[idx]
      val offset = randomUsableOffset(parsed, random)
      val ip = ipAtOffset(parsed, offset)
      if (alreadySeen.add(ip)) {
        result += IpCandidate(ip = ip, ipVersion = entry.ipVersion, sourceCidr = entry.cidr, isManual = false)
      }
    }
    return result
  }

  /**
   * Auto-mode sampler enforcing 50/50 IPv4/IPv6 split when BOTH_50_50 is selected,
   * filling remainder from the other family if one lacks enough unique IPs, and reporting exact ratio.
   */
  fun sampleAutoCloudFront(
    v4Prefixes: List<AwsPrefixEntry>,
    v6Prefixes: List<AwsPrefixEntry>,
    familyPref: IpFamilyPreference,
    requestedCount: Int,
    random: Random = Random.Default,
  ): AutoSampleReport {
    val seen = HashSet<String>(requestedCount * 2)
    return when (familyPref) {
      IpFamilyPreference.IPV4_ONLY -> {
        val v4 = sampleFromPrefixes(v4Prefixes, requestedCount, seen, random)
        AutoSampleReport(
          candidates = v4,
          requestedCount = requestedCount,
          v4Count = v4.size,
          v6Count = 0,
          ratioDescriptionFa = "۱۰۰٪ IPv4 (${v4.size} آدرس)",
          ratioDescriptionEn = "100% IPv4 (${v4.size} IPs)",
        )
      }
      IpFamilyPreference.IPV6_ONLY -> {
        val v6 = sampleFromPrefixes(v6Prefixes, requestedCount, seen, random)
        AutoSampleReport(
          candidates = v6,
          requestedCount = requestedCount,
          v4Count = 0,
          v6Count = v6.size,
          ratioDescriptionFa = "۱۰۰٪ IPv6 (${v6.size} آدرس)",
          ratioDescriptionEn = "100% IPv6 (${v6.size} IPs)",
        )
      }
      IpFamilyPreference.BOTH_50_50 -> {
        val targetV4 = requestedCount / 2
        val targetV6 = requestedCount - targetV4

        val v4First = sampleFromPrefixes(v4Prefixes, targetV4, seen, random)
        val v6First = sampleFromPrefixes(v6Prefixes, targetV6, seen, random)

        val combined = mutableListOf<IpCandidate>()
        // Interleave IPv4 and IPv6 so live scan displays both right away
        val v4List = v4First.toMutableList()
        val v6List = v6First.toMutableList()

        val remaining = requestedCount - (v4List.size + v6List.size)
        if (remaining > 0) {
          if (v4List.size < targetV4 && v6Prefixes.isNotEmpty()) {
            v6List += sampleFromPrefixes(v6Prefixes, remaining, seen, random)
          } else if (v6List.size < targetV6 && v4Prefixes.isNotEmpty()) {
            v4List += sampleFromPrefixes(v4Prefixes, remaining, seen, random)
          }
        }

        val maxLen = maxOf(v4List.size, v6List.size)
        for (i in 0 until maxLen) {
          if (i < v4List.size) combined += v4List[i]
          if (i < v6List.size) combined += v6List[i]
        }

        val total = combined.size.coerceAtLeast(1)
        val v4Pct = (v4List.size * 100) / total
        val v6Pct = 100 - v4Pct
        AutoSampleReport(
          candidates = combined,
          requestedCount = requestedCount,
          v4Count = v4List.size,
          v6Count = v6List.size,
          ratioDescriptionFa =
            "نسبت واقعی: ${v4List.size} IPv4 ($v4Pct٪) / ${v6List.size} IPv6 ($v6Pct٪)",
          ratioDescriptionEn =
            "Actual ratio: ${v4List.size} IPv4 ($v4Pct%) / ${v6List.size} IPv6 ($v6Pct%)",
        )
      }
    }
  }

  /**
   * Parses manual user input: single IP per line, multi-line, CSV tokens, or CIDRs.
   * Never drops valid user IPs; auto-detects IPv4/IPv6; reports line errors and duplicates removed;
   * safely controls large CIDRs without expanding millions of IPs into memory.
   */
  fun parseManualInput(
    rawText: String,
    cidrSampleCap: Int = 100,
    cidrScanAllUpTo: Int = 256,
    random: Random = Random.Default,
  ): ManualParseReport {
    val lines = rawText.split('\n', '\r')
    val seen = LinkedHashSet<String>()
    val result = mutableListOf<IpCandidate>()
    val errors = mutableListOf<ManualLineError>()
    val cidrNotices = mutableListOf<ManualCidrNotice>()
    var totalTokens = 0
    var validBeforeDedup = 0
    var duplicates = 0

    lines.forEachIndexed { zeroIdx, rawLine ->
      val lineNumber = zeroIdx + 1
      val strippedComment = rawLine.substringBefore('#').trim()
      if (strippedComment.isEmpty()) return@forEachIndexed

      // Support comma / semicolon / tab separated values (e.g. CSV files), skipping obvious CSV header
      val tokens =
        strippedComment
          .split(',', ';', '\t', ' ')
          .map { it.trim().trim('"', '\'') }
          .filter { it.isNotEmpty() }

      if (tokens.isEmpty()) return@forEachIndexed

      // If first line looks like a CSV header ("ip", "address", "cidr"), ignore cleanly
      if (
        lineNumber == 1 &&
          tokens.any { it.equals("ip", true) || it.equals("address", true) || it.equals("cidr", true) } &&
          tokens.none { detectIpVersion(it) != null || parseCidr(it) != null }
      ) {
        return@forEachIndexed
      }

      for (token in tokens) {
        totalTokens++
        if (token.contains('/')) {
          val parsedCidr = parseCidr(token)
          if (parsedCidr == null) {
            errors +=
              ManualLineError(
                lineNumber = lineNumber,
                rawLine = token,
                reasonFa = "فرمت CIDR نامعتبر است",
                reasonEn = "Invalid CIDR format",
              )
            continue
          }
          val maxThreshold = BigInteger.valueOf(cidrScanAllUpTo.toLong())
          if (parsedCidr.hostCount <= maxThreshold) {
            val countInt = parsedCidr.hostCount.toInt()
            val startOffset =
              if (parsedCidr.ipVersion == IpVersion.IPV4 && countInt >= 4) 1 else 0
            val endOffset =
              if (parsedCidr.ipVersion == IpVersion.IPV4 && countInt >= 4) countInt - 1 else countInt
            var addedFromCidr = 0
            for (off in startOffset until endOffset) {
              val ip = ipAtOffset(parsedCidr, BigInteger.valueOf(off.toLong()))
              validBeforeDedup++
              if (seen.add(ip)) {
                result +=
                  IpCandidate(
                    ip = ip,
                    ipVersion = parsedCidr.ipVersion,
                    sourceCidr = parsedCidr.raw,
                    isManual = true,
                  )
                addedFromCidr++
              } else {
                duplicates++
              }
            }
            cidrNotices +=
              ManualCidrNotice(
                cidr = parsedCidr.raw,
                ipVersion = parsedCidr.ipVersion,
                totalHosts = parsedCidr.hostCount.toString(),
                sampledCount = addedFromCidr,
                wasSampled = false,
              )
          } else {
            val sampleTarget = cidrSampleCap.coerceAtLeast(1)
            val tempSet = HashSet<String>()
            val entry =
              AwsPrefixEntry(
                cidr = parsedCidr.raw,
                ipVersion = parsedCidr.ipVersion,
                service = "MANUAL",
                region = "MANUAL",
              )
            val sampled = sampleFromPrefixes(listOf(entry), sampleTarget, tempSet, random)
            var added = 0
            for (cand in sampled) {
              validBeforeDedup++
              if (seen.add(cand.ip)) {
                result += cand.copy(isManual = true)
                added++
              } else {
                duplicates++
              }
            }
            cidrNotices +=
              ManualCidrNotice(
                cidr = parsedCidr.raw,
                ipVersion = parsedCidr.ipVersion,
                totalHosts = parsedCidr.hostCount.toString(),
                sampledCount = added,
                wasSampled = true,
              )
          }
        } else {
          val normalized = normalizeIp(token)
          if (normalized == null) {
            errors +=
              ManualLineError(
                lineNumber = lineNumber,
                rawLine = token,
                reasonFa = "آدرس IPv4 یا IPv6 نامعتبر است",
                reasonEn = "Invalid IPv4 or IPv6 address",
              )
            continue
          }
          val (ip, version) = normalized
          validBeforeDedup++
          if (seen.add(ip)) {
            result += IpCandidate(ip = ip, ipVersion = version, sourceCidr = "", isManual = true)
          } else {
            duplicates++
          }
        }
      }
    }

    return ManualParseReport(
      uniqueIps = result,
      totalInputEntries = totalTokens,
      validEntriesBeforeDedup = validBeforeDedup,
      duplicatesRemoved = duplicates,
      errors = errors,
      cidrNotices = cidrNotices,
    )
  }
}
