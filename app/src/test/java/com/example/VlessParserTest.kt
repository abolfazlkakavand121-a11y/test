package com.example

import com.example.core.network.VlessParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VlessParserTest {

  @Test
  fun `parse valid VLESS WebSocket TLS URI preserves all parameters and redacts secrets`() {
    val uri =
      "vless://11111111-2222-3333-4444-555555555555@example.cloudfront.net:443" +
        "?encryption=none&type=ws&security=tls&sni=cdn.example.com&fp=chrome" +
        "&alpn=http%2F1.1&host=cdn.example.com&path=%2Fws-tunnel#MyCloudFront"

    val parsed = VlessParser.parse(uri)
    assertTrue(parsed.isSuccess)
    val cfg = parsed.getOrThrow()
    assertEquals("11111111-2222-3333-4444-555555555555", cfg.uuid)
    assertEquals(443, cfg.port)
    assertEquals("ws", cfg.network)
    assertEquals("tls", cfg.security)
    assertEquals("cdn.example.com", cfg.sni)
    assertEquals("chrome", cfg.fingerprint)
    assertEquals(listOf("http/1.1"), cfg.alpn)
    assertEquals("cdn.example.com", cfg.wsHost)
    assertEquals("/ws-tunnel", cfg.wsPath)
    assertEquals("MyCloudFront", cfg.remark)
    assertTrue(cfg.unknownParams.isEmpty())

    // Redaction check
    val redacted = cfg.redactedSummary()
    assertFalse(redacted.contains("11111111-2222-3333-4444-555555555555"))
    assertTrue(redacted.contains("11111111-****"))

    // Compatibility check
    val compat = VlessParser.analyzeCloudFrontCompatibility(cfg)
    assertTrue(compat.isValid)
    assertTrue(compat.isCloudFrontCompatible)
    assertTrue(compat.canRunPhase2)

    // Xray JSON generation replaces ONLY address and has NO freedom outbound
    val xrayJson = VlessParser.buildXrayTestConfigJson(cfg, "18.160.5.10", 18080)
    val obj = JSONObject(xrayJson)
    val outbounds = obj.getJSONArray("outbounds")
    assertEquals(2, outbounds.length())
    val proxyOutbound = outbounds.getJSONObject(0)
    val blockOutbound = outbounds.getJSONObject(1)
    assertEquals("cf-proxy", proxyOutbound.getString("tag"))
    assertEquals("blackhole", blockOutbound.getString("protocol"))
    val vnext = proxyOutbound.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0)
    assertEquals("18.160.5.10", vnext.getString("address"))
    assertEquals(443, vnext.getInt("port"))
    val wsSettings = proxyOutbound.getJSONObject("streamSettings").getJSONObject("wsSettings")
    assertEquals("/ws-tunnel", wsSettings.getString("path"))
    assertEquals("cdn.example.com", wsSettings.getJSONObject("headers").getString("Host"))
  }

  @Test
  fun `unknown parameters are surfaced rather than silently dropped`() {
    val uri =
      "vless://11111111-2222-3333-4444-555555555555@example.com:443?type=ws&security=tls&sni=example.com&mysteryFlag=42"
    val cfg = VlessParser.parse(uri).getOrThrow()
    assertTrue(cfg.unknownParams.containsKey("mysteryFlag"))
    val compat = VlessParser.analyzeCloudFrontCompatibility(cfg)
    assertFalse(compat.isValid)
    assertFalse(compat.canRunPhase2)
    assertTrue(compat.unsupportedOptions.any { it.contains("mysteryFlag") })
  }

  @Test
  fun `reality and raw tcp are flagged as incompatible with CloudFront CDN`() {
    val realityUri =
      "vless://11111111-2222-3333-4444-555555555555@1.2.3.4:443?type=tcp&security=reality&sni=www.microsoft.com&pbk=abc&sid=01"
    val cfg = VlessParser.parse(realityUri).getOrThrow()
    val compat = VlessParser.analyzeCloudFrontCompatibility(cfg)
    assertFalse(compat.isCloudFrontCompatible)
    assertFalse(compat.canRunPhase2)
  }
}
