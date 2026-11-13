# Architecture, Test Logic & Third-Party Licenses

## 1. Architecture Overview
- **Language & UI**: 100% Kotlin + Jetpack Compose (Material 3, `Vazirmatn` + `JetBrains Mono`, Persian RTL primary + English LTR toggle, Dark/Light/System themes).
- **Persistence**: Room (`AppDatabase`) for scan sessions, per-IP stage results, live logs, and cached AWS IP ranges; DataStore Preferences (`SettingsDataStore`) for user settings and private VLESS URI storage. Zero cloud accounts, zero telemetry.
- **Background Execution**: `ScanForegroundService` (`foregroundServiceType="dataSync"`) wired to `ScanCoordinator` so scans survive screen rotation and backgrounding with Pause/Resume/Stop notification controls.

## 2. Two-Phase Verification Engine
### Phase 1: Fast Screening (`Phase1ProbeEngine`)
1. **Network / IPv6 Check**: Checks `ConnectivityManager` / `LinkProperties` / `NetworkInterface`. If an IPv6 target is probed on an IPv4-only network, it is recorded as `SKIPPED (UNSUPPORTED_NETWORK_IPV6)` rather than `FAIL`.
2. **Optional ICMP Ping**: Runs `/system/bin/ping` or `ping6` only if enabled and supported without root. Never fabricates ping latency.
3. **TCP Connect**: Direct `Socket.connect` to candidate IP + port measured with `System.nanoTime()`.
4. **TLS Handshake**: Wraps the connected socket with `SSLSocket`, sets `SNIHostName`, validates certificate chain and hostname via `HttpsURLConnection.getDefaultHostnameVerifier()`. If SNI is missing, marked `NOT_CONFIGURED` (never substitutes `www.google.com`). Diagnostic insecure mode is opt-in and explicitly tagged `DIAGNOSTIC_INSECURE_PASS`.
5. **WebSocket Upgrade**: Sends RFC 6455 `Upgrade: websocket` request with VLESS `Host` & `Path`; only `HTTP 101 Switching Protocols` passes.

### Phase 2: Real Xray Core Tunnel Test (`AndroidLibXrayRunner`)
1. **Config Isolation**: `VlessParser.buildXrayTestConfigJson` replaces **only** `vnext[0].address` with the candidate IP while preserving UUID, port, flow, encryption, network/transport, TLS/SNI/FP/ALPN, WS Host/Path, gRPC settings.
2. **Zero Direct Fallback**: Xray JSON includes only `cf-proxy` (`vless`) and `block` (`blackhole`) outbounds—**no `freedom` outbound**. The Android test client connects via `Socket(Proxy(Proxy.Type.SOCKS, 127.0.0.1:<ephemeralPort>))` with `InetSocketAddress.createUnresolved(host, port)` so DNS resolves inside the tunnel and direct phone internet fallback is impossible.
3. **Proof of Traversal**: After checking expected HTTP status code (and optional body substring) on configured HTTPS test URLs across N repetitions, `CoreController.queryAllOutboundTrafficStats()` verifies `cf-proxy:uplink > 0` and `cf-proxy:downlink > 0`. If unprovable, status is `INCONCLUSIVE`, never `TUNNEL_PASS`.
4. **Ranking**: `TUNNEL_PASS` sorted by highest success ratio, lowest median tunnel latency, and stability score. Phase 1 passes are never listed as healthy tunnels.

## 3. Libraries & Licenses
| Library / Component | Version | License |
|---|---|---|
| `AndroidLibXrayLite` (`libv2ray.aar`) | `v26.11.9` | LGPL-3.0 |
| `XTLS/Xray-core` (bundled in AAR) | `v1.260327+` | MPL-2.0 |
| AndroidX Core / Lifecycle / Room / Navigation / DataStore / CameraX | BOM / catalog | Apache-2.0 |
| Square OkHttp | `4.10.0` | Apache-2.0 |
| ML Kit Barcode Scanning | `17.3.0` | Android / Google APIs Terms |
| Vazirmatn & JetBrains Mono Fonts | Bundled TTF | OFL-1.1 |
