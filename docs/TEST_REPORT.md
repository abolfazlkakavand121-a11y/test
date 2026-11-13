# گزارش تست و خروجی بیلد (CloudFront Scanner)

## ۱. مشخصات فایل APK خروجی
- **مسیر فایل APK امضاشده**: `app/build/outputs/apk/debug/app-debug.apk`
- **نوع امضا**: امضاشده با `debugConfig` (`debug.keystore` محلی) — آماده نصب مستقیم (Sideload) روی دستگاه‌های اندرویدی (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`).
- **نکته درباره بیلد Release**: برای جلوگیری از قرارگیری کلید خصوصی در سورس کد، بیلد `release` نیازمند تنظیم فایل Keystore شخصی (`my-upload-key.jks`) و متغیرهای محیطی `STORE_PASSWORD` و `KEY_PASSWORD` در زمان بیلد است.
- **حجم فایل**: `181 MB` (شامل باینری‌های Native هسته Xray در `libv2ray.aar` برای هر ۴ معماری + `geoip.dat` و `geosite.dat`).
- **هش SHA-256 (بیلد تاییدشده)**:
  `b57b2403726388e0f87fb89a81ebc468d6cc36fbc42cc343e2a419a7e3fc5770`

## ۲. خلاصه نتایج تست‌ها (`:app:testDebugUnitTest` — ۲۲/۲۲ موفق)

### الف) تست‌های واقعی شبکه (Live Network Tests)
- **`LiveNetworkVerificationTest`** *(واقعی - بدون Mock)*:
  - دریافت واقعی فایل `https://ip-ranges.amazonaws.com/ip-ranges.json` با اعتبارسنجی TLS.
  - بررسی استخراج بیش از ۵۰ رنج `CLOUDFRONT/GLOBAL` IPv4 و بیش از ۱۰ رنج IPv6 همراه با `syncToken` و `createDate`.
  - نمونه‌گیری تصادفی از رنج‌های `GLOBAL` و برقراری اتصال واقعی TCP پورت 443 روی IPهای واقعی Amazon CloudFront.

### ب) تست‌های واحد و شبیه‌ساز محلی (Unit / Loopback / Robolectric — جدا از شبکه واقعی)
- **`AwsIpRangeRepositoryTest`** *(Mock HTTP + Room In-Memory)*: تفکیک دقیق `CLOUDFRONT + GLOBAL` از `CLOUDFRONT_ORIGIN_FACING` و رنج‌های منطقه‌ای، و بازیابی Cache آفلاین با پرچم هشدار قدیمی بودن.
- **`CidrMathAndInputTest`** *(Unit)*: نسبت دقیق ۵۰/۵۰ IPv4/IPv6 در حالت خودکار، پر کردن ظرفیت از خانواده دیگر و گزارش نسبت واقعی، نمونه‌گیری بدون تکرار از CIDRهای عظیم (`/28` IPv6 و `/8` IPv4) بدون مصرف حافظه، حفظ تمام IPهای دستی بدون اعمال فیلتر ۵۰/۵۰، حذف تکراری و گزارش خطای خط به خط.
- **`VlessParserTest`** *(Robolectric)*: Parse دقیق `vless://`، حفظ تمام پارامترها، جایگزینی صرفاً آدرس سرور، خطا دادن برای پارامترهای ناشناخته، تشخیص ناسازگاری `REALITY` و `TCP` خام با CloudFront، و نبود `freedom` outbound در JSON خروجی Xray.
- **`Phase1ProbeEngineTest`** *(Localhost `ServerSocket`)*: ثبت `SKIPPED (UNSUPPORTED_NETWORK_IPV6)` در نبود IPv6، تفکیک `TCP_PASS`، شکست `TLS Handshake`، موفقیت `WS 101` و رد `WS 403`.
- **`ReportExporterAndCoordinatorTest`** *(Robolectric + Room + Fake Xray)*: تولید خروجی TXT/CSV/JSON با مخفی‌سازی UUID، محاسبه Median، Parse آمار `cf-proxy`، اثبات عدم Fallback مستقیم در صورت بسته بودن پورت SOCKS5 محلی، و ذخیره کامل مرحله ۱ و ۲ + لاگ‌ها در Room.
- **`ComposeUiRobolectricTest` & `ExampleRobolectricTest`** *(Robolectric UI)*: بررسی `app_name`، نمایش صفحه Scan، سوییچ به حالت دستی و ورود IPv4/IPv6، ناوبری بین هر ۵ تب و تغییر زبان فارسی/انگلیسی.

## ۳. موارد تست‌نشده و محدودیت‌های محیطی صادقانه
1. **تست End-to-End واقعی Xray روی سرور VLESS زنده و نصب روی گوشی فیزیکی / شبیه‌ساز**:
   - در محیط بیلد ابری بدون Emulator/ADB و بدون یک سرور واقعی VLESS+WS+CloudFront کاربر، **اجرای باینری JNI `libgojni.so` روی دستگاه واقعی Android و عبور ترافیک از یک Distribution واقعی کاربر تست نشد**.
   - با این حال، ادغام `libv2ray.aar` (`2dust/AndroidLibXrayLite v26.11.9`)، تولید JSON واقعی Xray بدون `freedom` outbound، اتصال اجباری `SOCKS5` بدون Fallback، و محاسبه Median و کنترل آمار Outbound در کد پیاده‌سازی و بخش‌های منطقی/سوکت محلی آن تست شده‌اند.
2. **اسکن QR با دوربین واقعی (`CameraX` + `ML Kit`)**: نیازمند دوربین فیزیکی گوشی است و روی JVM تست نشد (ساخت و کامپایل کامل انجام شده است).
