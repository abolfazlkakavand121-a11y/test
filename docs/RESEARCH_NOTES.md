# Technical Research & Reference Notes (CloudFront Scanner)

Date of verification: 2026-11-12

## 1. Official AWS Sources Investigated
1. **AWS `ip-ranges.json`** (`https://ip-ranges.amazonaws.com/ip-ranges.json`)
   - Verified live structure (`syncToken: 1794524225`, `createDate: 2026-11-12-22-57-05`).
   - Critical finding: Filtering purely by `service == "CLOUDFRONT"` returns **216** IPv4 entries across multiple regional edges (`us-east-1`, `eu-west-1`, etc.) and **35** IPv6 entries. Restricting strictly to `service == "CLOUDFRONT" && region == "GLOBAL"` yields **118** IPv4 and **33** IPv6 primary global edge prefixes.
   - `CLOUDFRONT_ORIGIN_FACING` (49 IPv4, 38 IPv6) represents CloudFront-to-Origin egress ranges and MUST NOT be mixed into the Edge candidate pool.
2. **AWS CloudFront Dedicated List** (`https://d7uri8nf7uskq.cloudfront.net/tools/list-cloudfront-ips`)
   - JSON object with `CLOUDFRONT_GLOBAL_IP_LIST` (and regional list). Used strictly as a secondary informational cross-check; `ip-ranges.json` always has priority.
3. **AWS CloudFront WebSocket Documentation** (`distribution-working-with.websockets.html`)
   - Confirmed CloudFront supports HTTP/1.1 `Upgrade: websocket` (`101 Switching Protocols`) over TLS/HTTPS when the Distribution and Origin are configured for WebSocket. CloudFront is **not** a generic TCP proxy; raw VLESS+TCP and REALITY (which requires direct origin X25519 TLS handshakes) cannot traverse CloudFront edge nodes.

## 2. Secondary & Open-Source References Evaluated
1. **`joetek/aws-ip-ranges-json`**
   - Idea adopted: Tracking `syncToken` and `createDate` changes cleanly. Never used as a primary authority over official AWS HTTPS endpoints.
2. **`MatinSenPai/SenPaiScanner`**
   - Ideas adopted: Two-phase architecture (fast Phase 1 probe -> real embedded Xray Phase 2 validation), VLESS URI parameter extraction, live results sorting/copying, pause/stop/resume workflow, and exportable TXT/CSV/JSON outputs.
   - Excluded: All Cloudflare-specific logic, Cloudflare IP ranges, and Cloudflare `cdn-cgi/trace` assumptions.
3. **`WhiteDNS/WhiteDNS-cleanip-finder`**
   - Ideas adopted: Foreground Service execution on Android, CIDR range handling without UI freeze, pause/resume/stop telemetry controls, and multi-format Storage Access Framework (SAF) export.
4. **`2dust/AndroidLibXrayLite` (`v26.11.9`)**
   - Inspected `libv2ray.aar` (LGPL-3.0) wrapping `github.com/xtls/xray-core v1.260327.1-0.20261109052709-676eaf66f8f0` (MPL-2.0).
   - Provides native `libgojni.so` for all 4 ABIs (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) and clean Java/Kotlin bindings (`Libv2ray.initCoreEnv`, `Libv2ray.newCoreController`, `CoreController.startLoop`, `CoreController.stopLoop`, `CoreController.queryAllOutboundTrafficStats`) with zero extra Android permissions.
