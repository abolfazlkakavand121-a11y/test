package com.example.core.network

import com.example.data.local.AwsRangeCacheEntity
import com.example.data.local.ScanDao
import com.example.domain.model.AwsIpRangesSnapshot
import com.example.domain.model.AwsPrefixEntry
import com.example.domain.model.IpVersion
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class AwsIpRangeRepository(
  private val scanDao: ScanDao,
  private val httpClient: OkHttpClient = defaultStrictHttpsClient(),
) {

  companion object {
    const val OFFICIAL_AWS_IP_RANGES_URL = "https://ip-ranges.amazonaws.com/ip-ranges.json"
    const val OFFICIAL_CLOUDFRONT_LIST_URL =
      "https://d7uri8nf7uskq.cloudfront.net/tools/list-cloudfront-ips"
    private const val STALE_THRESHOLD_MS = 7L * 24 * 60 * 60 * 1000L

    fun defaultStrictHttpsClient(): OkHttpClient =
      OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    /**
     * Parses official AWS ip-ranges.json strictly separating:
     * - service == CLOUDFRONT && region == GLOBAL -> globalEdgeV4 / globalEdgeV6 (Primary scan pool)
     * - service == CLOUDFRONT && region != GLOBAL -> regionalEdgeV4 / regionalEdgeV6
     * - service == CLOUDFRONT_ORIGIN_FACING -> originFacingV4 / originFacingV6 (Never mixed into main pool)
     */
    fun parseOfficialAwsJson(
      rawJson: String,
      fetchedAtMillis: Long,
      isFromCache: Boolean,
      secondaryDiffNote: String = "",
    ): AwsIpRangesSnapshot {
      val obj = JSONObject(rawJson)
      val syncToken = obj.optString("syncToken", "")
      val createDate = obj.optString("createDate", "")

      val globalV4 = mutableListOf<AwsPrefixEntry>()
      val regionalV4 = mutableListOf<AwsPrefixEntry>()
      val originV4 = mutableListOf<AwsPrefixEntry>()

      val prefixes = obj.optJSONArray("prefixes") ?: JSONArray()
      for (i in 0 until prefixes.length()) {
        val item = prefixes.optJSONObject(i) ?: continue
        val service = item.optString("service", "")
        val region = item.optString("region", "")
        val cidr = item.optString("ip_prefix", "")
        val nbg = item.optString("network_border_group", "")
        if (cidr.isBlank()) continue

        when (service) {
          "CLOUDFRONT" -> {
            val entry = AwsPrefixEntry(cidr, IpVersion.IPV4, service, region, nbg)
            if (region == "GLOBAL") globalV4 += entry else regionalV4 += entry
          }
          "CLOUDFRONT_ORIGIN_FACING" -> {
            originV4 += AwsPrefixEntry(cidr, IpVersion.IPV4, service, region, nbg)
          }
        }
      }

      val globalV6 = mutableListOf<AwsPrefixEntry>()
      val regionalV6 = mutableListOf<AwsPrefixEntry>()
      val originV6 = mutableListOf<AwsPrefixEntry>()

      val ipv6Prefixes = obj.optJSONArray("ipv6_prefixes") ?: JSONArray()
      for (i in 0 until ipv6Prefixes.length()) {
        val item = ipv6Prefixes.optJSONObject(i) ?: continue
        val service = item.optString("service", "")
        val region = item.optString("region", "")
        val cidr = item.optString("ipv6_prefix", "")
        val nbg = item.optString("network_border_group", "")
        if (cidr.isBlank()) continue

        when (service) {
          "CLOUDFRONT" -> {
            val entry = AwsPrefixEntry(cidr, IpVersion.IPV6, service, region, nbg)
            if (region == "GLOBAL") globalV6 += entry else regionalV6 += entry
          }
          "CLOUDFRONT_ORIGIN_FACING" -> {
            originV6 += AwsPrefixEntry(cidr, IpVersion.IPV6, service, region, nbg)
          }
        }
      }

      val isStale =
        isFromCache && (System.currentTimeMillis() - fetchedAtMillis > STALE_THRESHOLD_MS)

      return AwsIpRangesSnapshot(
        syncToken = syncToken,
        createDate = createDate,
        fetchedAtMillis = fetchedAtMillis,
        isFromCache = isFromCache,
        isStaleWarning = isStale,
        globalEdgeV4 = globalV4,
        globalEdgeV6 = globalV6,
        regionalEdgeV4 = regionalV4,
        regionalEdgeV6 = regionalV6,
        originFacingV4 = originV4,
        originFacingV6 = originV6,
        secondarySourceDiffNote = secondaryDiffNote,
      )
    }

    /**
     * Compacts the official AWS JSON down to only CloudFront-relevant entries before caching in Room
     * so we preserve exact syncToken/createDate and CloudFront prefixes cleanly without wasting disk space.
     */
    fun buildCompactCloudFrontJson(rawAwsJson: String): String {
      val src = JSONObject(rawAwsJson)
      val out = JSONObject()
      out.put("syncToken", src.optString("syncToken", ""))
      out.put("createDate", src.optString("createDate", ""))

      val outV4 = JSONArray()
      val inV4 = src.optJSONArray("prefixes") ?: JSONArray()
      for (i in 0 until inV4.length()) {
        val item = inV4.optJSONObject(i) ?: continue
        val svc = item.optString("service", "")
        if (svc == "CLOUDFRONT" || svc == "CLOUDFRONT_ORIGIN_FACING") {
          outV4.put(item)
        }
      }
      out.put("prefixes", outV4)

      val outV6 = JSONArray()
      val inV6 = src.optJSONArray("ipv6_prefixes") ?: JSONArray()
      for (i in 0 until inV6.length()) {
        val item = inV6.optJSONObject(i) ?: continue
        val svc = item.optString("service", "")
        if (svc == "CLOUDFRONT" || svc == "CLOUDFRONT_ORIGIN_FACING") {
          outV6.put(item)
        }
      }
      out.put("ipv6_prefixes", outV6)
      return out.toString()
    }
  }

  suspend fun loadCachedSnapshot(): AwsIpRangesSnapshot? =
    withContext(Dispatchers.IO) {
      val cached = scanDao.getAwsRangeCache() ?: return@withContext null
      try {
        parseOfficialAwsJson(
          rawJson = cached.rawPayloadJson,
          fetchedAtMillis = cached.fetchedAtMillis,
          isFromCache = true,
          secondaryDiffNote = cached.secondaryDiffNote,
        )
      } catch (_: Exception) {
        null
      }
    }

  suspend fun fetchLatestOrCached(forceRefresh: Boolean = true): Result<AwsIpRangesSnapshot> =
    withContext(Dispatchers.IO) {
      if (!forceRefresh) {
        val cached = loadCachedSnapshot()
        if (cached != null) return@withContext Result.success(cached)
      }

      try {
        val req =
          Request.Builder()
            .url(OFFICIAL_AWS_IP_RANGES_URL)
            .header("Accept", "application/json")
            .build()
        val bodyStr =
          httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
              throw IOException("HTTP ${resp.code} from AWS ip-ranges.json")
            }
            resp.body?.string() ?: throw IOException("Empty response from AWS ip-ranges.json")
          }

        val compactJson = buildCompactCloudFrontJson(bodyStr)
        val now = System.currentTimeMillis()
        val parsed = parseOfficialAwsJson(compactJson, now, isFromCache = false)
        if (parsed.globalEdgeV4.isEmpty() && parsed.globalEdgeV6.isEmpty()) {
          throw IOException("No CLOUDFRONT GLOBAL entries found in AWS response")
        }

        // Secondary informational cross-check against official list-cloudfront-ips (non-blocking)
        val diffNote = fetchSecondaryComparisonNote(parsed)
        val finalSnapshot = parsed.copy(secondarySourceDiffNote = diffNote)

        scanDao.saveAwsRangeCache(
          AwsRangeCacheEntity(
            id = 1,
            syncToken = finalSnapshot.syncToken,
            createDate = finalSnapshot.createDate,
            fetchedAtMillis = now,
            rawPayloadJson = compactJson,
            secondaryDiffNote = diffNote,
          )
        )
        Result.success(finalSnapshot)
      } catch (e: Exception) {
        val cached = loadCachedSnapshot()
        if (cached != null) {
          Result.success(cached.copy(isFromCache = true, isStaleWarning = true))
        } else {
          Result.failure(e)
        }
      }
    }

  private fun fetchSecondaryComparisonNote(primary: AwsIpRangesSnapshot): String {
    return try {
      val req = Request.Builder().url(OFFICIAL_CLOUDFRONT_LIST_URL).build()
      val text =
        httpClient.newCall(req).execute().use { resp ->
          if (!resp.isSuccessful) return ""
          resp.body?.string().orEmpty()
        }
      val obj = JSONObject(text)
      val cfGlobal = obj.optJSONArray("CLOUDFRONT_GLOBAL_IP_LIST") ?: return ""
      val listCidrs = (0 until cfGlobal.length()).mapNotNull { cfGlobal.optString(it) }.toSet()
      val primaryV4 = primary.globalEdgeV4.map { it.cidr }.toSet()
      if (listCidrs == primaryV4) {
        "تطابق کامل با فهرست list-cloudfront-ips (${primaryV4.size} رنج IPv4)"
      } else {
        val onlyInPrimary = (primaryV4 - listCidrs).size
        val onlyInSecondary = (listCidrs - primaryV4).size
        "مقایسه ثانویه: +$onlyInPrimary در ip-ranges.json / +$onlyInSecondary در list-cloudfront-ips (مرجع اصلی AWS اعمال شد)"
      }
    } catch (_: Exception) {
      ""
    }
  }
}
