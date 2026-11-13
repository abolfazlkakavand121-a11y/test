package com.example.core.network

import com.example.domain.model.VlessCompatibilityReport
import com.example.domain.model.VlessConfig
import java.net.URI
import java.net.URLDecoder
import org.json.JSONArray
import org.json.JSONObject

object VlessParser {

  private val KNOWN_QUERY_KEYS =
    setOf(
      "encryption",
      "flow",
      "type",
      "net",
      "security",
      "sni",
      "servername",
      "fp",
      "fingerprint",
      "alpn",
      "allowinsecure",
      "insecure",
      "host",
      "path",
      "servicename",
      "mode",
      "pbk",
      "sid",
      "spx",
      "headertype",
      "authority",
      "extra",
    )

  private val SUPPORTED_NETWORKS = setOf("tcp", "ws", "grpc", "httpupgrade", "xhttp", "splithttp")
  private val SUPPORTED_SECURITY = setOf("none", "tls", "reality")

  fun parse(rawInput: String): Result<VlessConfig> {
    val trimmed = rawInput.trim()
    if (!trimmed.lowercase().startsWith("vless://")) {
      return Result.failure(IllegalArgumentException("لینک باید با vless:// شروع شود / URI must start with vless://"))
    }

    return try {
      val uri = URI(trimmed)
      val uuid = uri.userInfo?.trim().orEmpty()
      if (uuid.isBlank()) {
        return Result.failure(IllegalArgumentException("UUID در لینک VLESS یافت نشد / Missing UUID"))
      }
      val host = uri.host?.trim()?.removePrefix("[")?.removeSuffix("]").orEmpty()
      if (host.isBlank()) {
        return Result.failure(IllegalArgumentException("آدرس سرور در لینک یافت نشد / Missing server host"))
      }
      val port = uri.port
      if (port !in 1..65535) {
        return Result.failure(IllegalArgumentException("پورت نامعتبر است ($port) / Invalid port"))
      }

      val params = parseQueryParams(uri.rawQuery.orEmpty())
      val remark = uri.rawFragment?.let { urlDecode(it) }.orEmpty()

      val encryption = params["encryption"] ?: "none"
      val flow = params["flow"].orEmpty()
      val network = (params["type"] ?: params["net"] ?: "tcp").lowercase()
      val security = (params["security"] ?: "none").lowercase()
      val sni = params["sni"] ?: params["serverName"] ?: params["servername"] ?: ""
      val fp = params["fp"] ?: params["fingerprint"] ?: ""
      val alpnRaw = params["alpn"].orEmpty()
      val alpn =
        alpnRaw
          .split(',')
          .map { it.trim() }
          .filter { it.isNotEmpty() }
      val allowInsecure =
        (params["allowInsecure"] ?: params["allowinsecure"] ?: params["insecure"] ?: "0")
          .lowercase() in setOf("1", "true", "yes")
      val wsHost = params["host"].orEmpty()
      val wsPath = params["path"].orEmpty()
      val grpcServiceName = params["serviceName"] ?: params["servicename"] ?: ""
      val grpcMode = params["mode"] ?: "gun"
      val pbk = params["pbk"].orEmpty()
      val sid = params["sid"].orEmpty()
      val spx = params["spx"].orEmpty()
      val headerType = params["headerType"] ?: params["headertype"] ?: ""

      val unknownParams =
        params.filterKeys { key -> key.lowercase() !in KNOWN_QUERY_KEYS }

      Result.success(
        VlessConfig(
          rawUri = trimmed,
          uuid = uuid,
          address = host,
          port = port,
          encryption = encryption,
          flow = flow,
          network = network,
          security = security,
          sni = sni,
          fingerprint = fp,
          alpn = alpn,
          allowInsecure = allowInsecure,
          wsHost = wsHost,
          wsPath = wsPath,
          grpcServiceName = grpcServiceName,
          grpcMode = grpcMode,
          realityPublicKey = pbk,
          realityShortId = sid,
          realitySpiderX = spx,
          headerType = headerType,
          remark = remark,
          unknownParams = unknownParams,
        )
      )
    } catch (e: Exception) {
      Result.failure(IllegalArgumentException("خطا در خواندن ساختار VLESS URI: ${e.message}"))
    }
  }

  private fun parseQueryParams(rawQuery: String): Map<String, String> {
    if (rawQuery.isBlank()) return emptyMap()
    val map = linkedMapOf<String, String>()
    for (pair in rawQuery.split('&')) {
      if (pair.isBlank()) continue
      val idx = pair.indexOf('=')
      if (idx <= 0) {
        map[urlDecode(pair)] = ""
      } else {
        val k = urlDecode(pair.substring(0, idx))
        val v = urlDecode(pair.substring(idx + 1))
        map[k] = v
      }
    }
    return map
  }

  private fun urlDecode(value: String): String =
    try {
      URLDecoder.decode(value, "UTF-8")
    } catch (_: Exception) {
      value
    }

  fun analyzeCloudFrontCompatibility(config: VlessConfig): VlessCompatibilityReport {
    val errors = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    val unsupported = mutableListOf<String>()

    if (config.network !in SUPPORTED_NETWORKS) {
      unsupported += "network=${config.network}"
      errors += "نوع Transport '${config.network}' پشتیبانی نمیشود."
    }
    if (config.security !in SUPPORTED_SECURITY) {
      unsupported += "security=${config.security}"
      errors += "نوع امنیت '${config.security}' پشتیبانی نمیشود."
    }
    if (config.unknownParams.isNotEmpty()) {
      val keys = config.unknownParams.keys.joinToString(", ")
      unsupported += "unknown_params=[$keys]"
      errors += "پارامترهای ناشناخته در URI وجود دارند و بی‌صدا حذف نمی‌شوند: $keys"
    }
    if (config.encryption != "none") {
      warnings += "encryption=${config.encryption} غیرمعمول است؛ مطمئن شوید با سرور سازگار است."
    }
    if (config.allowInsecure) {
      warnings += "در URI گزینه allowInsecure=true فعال است؛ برای تست معتبر باید گواهی TLS بررسی شود."
    }

    var cfCompatible = true
    val explFa: String
    val explEn: String

    when {
      config.security == "reality" -> {
        cfCompatible = false
        errors += "REALITY از طریق CDN کلادفرانت عبور نمی‌کند (نیازمند اتصال مستقیم TLS با کلید X25519 سرور مبدأ است)."
        explFa =
          "پیکربندی VLESS+Reality با Amazon CloudFront سازگار نیست؛ زیرا CloudFront یک پراکسی معکوس CDN است و TLS را روی Edge خاتمه می‌دهد."
        explEn =
          "VLESS+REALITY is incompatible with Amazon CloudFront CDN because CloudFront terminates TLS at the edge and cannot relay REALITY handshakes."
      }
      config.network == "tcp" -> {
        cfCompatible = false
        errors += "VLESS روی TCP خام از Amazon CloudFront عبور نمی‌کند؛ CloudFront فقط ترافیک HTTP/HTTPS/WebSocket/gRPC-H2 سازگار با Distribution را منتقل می‌کند."
        explFa =
          "CloudFront یک پراکسی عمومی TCP نیست. برای عبور از CloudFront باید از WebSocket (+TLS) یا transport سازگار با Distribution واقعی استفاده شود."
        explEn =
          "CloudFront is not a generic TCP proxy. Raw VLESS over TCP cannot traverse CloudFront edge servers; WebSocket (+TLS) matching your Distribution is required."
      }
      config.network == "ws" -> {
        if (config.wsHost.isBlank() && config.sni.isBlank()) {
          cfCompatible = false
          errors += "برای WebSocket روی CloudFront باید Host و SNI دامین Distribution مشخص باشد."
        }
        if (config.security == "none" && config.port == 443) {
          cfCompatible = false
          errors += "پورت 443 نیازمند security=tls است."
        } else if (config.security == "none") {
          warnings += "اتصال WebSocket بدون TLS است؛ برای CloudFront HTTPS (پورت 443) استفاده از TLS توصیه می‌شود."
        }
        if (config.wsPath.isBlank()) {
          warnings += "Path وب‌سوکت خالی است؛ مطمئن شوید Origin روی مسیر پیش‌فرض / پذیرش دارد."
        }
        explFa =
          if (cfCompatible) {
            "ساختار VLESS + WebSocket${if (config.security == "tls") " + TLS" else ""} با الگوی ترافیک CloudFront سازگار است؛ به شرط آنکه Distribution و Origin واقعاً برای همین Host و Path تنظیم شده باشند."
          } else {
            "پیکربندی WebSocket ناقص است و بدون Host/SNI معتبر قابل تست روی CloudFront نیست."
          }
        explEn =
          if (cfCompatible) {
            "VLESS + WebSocket${if (config.security == "tls") " + TLS" else ""} matches CloudFront proxy requirements, provided your Distribution and Origin actually accept this Host and Path."
          } else {
            "WebSocket configuration is incomplete; a valid Host/SNI is required for CloudFront routing."
          }
      }
      config.network == "grpc" -> {
        if (config.security != "tls" || config.effectiveSni.isBlank()) {
          cfCompatible = false
          errors += "gRPC نیازمند TLS و SNI معتبر است."
        } else {
          warnings += "پشتیبانی CloudFront از gRPC نیازمند تنظیم صریح HTTP/2 در Distribution است؛ در اغلب کانفیگ‌های CloudFront از WebSocket استفاده می‌شود."
        }
        explFa =
          "gRPC روی HTTP/2 فقط در صورت فعال بودن در CloudFront Distribution کار می‌کند و هر gRPC به‌صورت خودکار عبور نمی‌کند."
        explEn =
          "gRPC over HTTP/2 works only if explicitly configured on your CloudFront Distribution; WebSocket+TLS is the standard CloudFront transport."
      }
      else -> {
        warnings += "Transport '${config.network}' نیازمند بررسی سازگاری دقیق با تنظیمات HTTP/CDN در CloudFront Distribution است."
        explFa =
          "سازگاری Transport '${config.network}' وابسته به پشتیبانی واقعی CloudFront Distribution و Origin شماست."
        explEn =
          "Compatibility of '${config.network}' depends on explicit support in your CloudFront Distribution and Origin."
      }
    }

    val canRunPhase2 = errors.isEmpty() && unsupported.isEmpty()
    return VlessCompatibilityReport(
      isValid = unsupported.isEmpty(),
      isCloudFrontCompatible = cfCompatible,
      canRunPhase2 = canRunPhase2,
      warnings = warnings,
      errors = errors,
      unsupportedOptions = unsupported,
      compatibilityExplanationFa = explFa,
      compatibilityExplanationEn = explEn,
    )
  }

  /**
   * Builds a clean Xray-core JSON config for testing [candidateIp] as the server address,
   * preserving all other VLESS parameters, routing socks-in strictly to cf-proxy with NO freedom outbound.
   */
  fun buildXrayTestConfigJson(
    config: VlessConfig,
    candidateIp: String,
    localSocksPort: Int,
    allowInsecureTlsDiagnostic: Boolean = false,
  ): String {
    val root = JSONObject()

    // Log config (errors only to avoid noise/leaking)
    root.put("log", JSONObject().put("loglevel", "warning"))

    // Stats + Policy so we can verify real traffic flowed through cf-proxy
    root.put("stats", JSONObject())
    root.put(
      "policy",
      JSONObject()
        .put(
          "levels",
          JSONObject()
            .put("0", JSONObject().put("statsUserUplink", true).put("statsUserDownlink", true))
        )
        .put(
          "system",
          JSONObject()
            .put("statsOutboundUplink", true)
            .put("statsOutboundDownlink", true)
        ),
    )

    // Local loopback SOCKS5 inbound
    val inbound =
      JSONObject()
        .put("tag", "socks-in")
        .put("listen", "127.0.0.1")
        .put("port", localSocksPort)
        .put("protocol", "socks")
        .put("settings", JSONObject().put("auth", "noauth").put("udp", false))
    root.put("inbounds", JSONArray().put(inbound))

    // Outbound: cf-proxy with candidateIp replacing ONLY address
    val userObj =
      JSONObject()
        .put("id", config.uuid)
        .put("encryption", config.encryption)
    if (config.flow.isNotBlank()) {
      userObj.put("flow", config.flow)
    }

    val vnextObj =
      JSONObject()
        .put("address", candidateIp)
        .put("port", config.port)
        .put("users", JSONArray().put(userObj))

    val streamSettings = JSONObject().put("network", config.network)
    streamSettings.put("security", config.security)

    if (config.security == "tls") {
      val tlsSettings = JSONObject()
      if (config.effectiveSni.isNotBlank()) {
        tlsSettings.put("serverName", config.effectiveSni)
      }
      if (config.alpn.isNotEmpty()) {
        tlsSettings.put("alpn", JSONArray(config.alpn))
      }
      if (config.fingerprint.isNotBlank()) {
        tlsSettings.put("fingerprint", config.fingerprint)
      }
      tlsSettings.put("allowInsecure", allowInsecureTlsDiagnostic)
      streamSettings.put("tlsSettings", tlsSettings)
    } else if (config.security == "reality") {
      val realitySettings = JSONObject()
      if (config.effectiveSni.isNotBlank()) realitySettings.put("serverName", config.effectiveSni)
      if (config.fingerprint.isNotBlank()) realitySettings.put("fingerprint", config.fingerprint)
      if (config.realityPublicKey.isNotBlank()) realitySettings.put("publicKey", config.realityPublicKey)
      if (config.realityShortId.isNotBlank()) realitySettings.put("shortId", config.realityShortId)
      if (config.realitySpiderX.isNotBlank()) realitySettings.put("spiderX", config.realitySpiderX)
      streamSettings.put("realitySettings", realitySettings)
    }

    when (config.network) {
      "ws" -> {
        val wsSettings = JSONObject()
        val path = config.wsPath.ifBlank { "/" }
        wsSettings.put("path", path)
        if (config.wsHost.isNotBlank()) {
          wsSettings.put("headers", JSONObject().put("Host", config.wsHost))
        }
        streamSettings.put("wsSettings", wsSettings)
      }
      "grpc" -> {
        val grpcSettings = JSONObject()
        if (config.grpcServiceName.isNotBlank()) {
          grpcSettings.put("serviceName", config.grpcServiceName)
        }
        grpcSettings.put("multiMode", config.grpcMode == "multi")
        streamSettings.put("grpcSettings", grpcSettings)
      }
      "httpupgrade" -> {
        val httpUpgrade = JSONObject()
        httpUpgrade.put("path", config.wsPath.ifBlank { "/" })
        if (config.wsHost.isNotBlank()) httpUpgrade.put("host", config.wsHost)
        streamSettings.put("httpupgradeSettings", httpUpgrade)
      }
      "xhttp", "splithttp" -> {
        val xhttpSettings = JSONObject()
        xhttpSettings.put("path", config.wsPath.ifBlank { "/" })
        if (config.wsHost.isNotBlank()) xhttpSettings.put("host", config.wsHost)
        streamSettings.put("xhttpSettings", xhttpSettings)
      }
    }

    val proxyOutbound =
      JSONObject()
        .put("tag", "cf-proxy")
        .put("protocol", "vless")
        .put("settings", JSONObject().put("vnext", JSONArray().put(vnextObj)))
        .put("streamSettings", streamSettings)

    // Blackhole outbound only (NO freedom outbound -> direct fallback inside Xray is impossible)
    val blockOutbound =
      JSONObject()
        .put("tag", "block")
        .put("protocol", "blackhole")

    root.put("outbounds", JSONArray().put(proxyOutbound).put(blockOutbound))

    // Strict routing rule: socks-in -> cf-proxy
    val rule =
      JSONObject()
        .put("type", "field")
        .put("inboundTag", JSONArray().put("socks-in"))
        .put("outboundTag", "cf-proxy")
    root.put(
      "routing",
      JSONObject().put("domainStrategy", "AsIs").put("rules", JSONArray().put(rule)),
    )

    return root.toString()
  }
}
