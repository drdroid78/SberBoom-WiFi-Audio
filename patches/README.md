# StarPTP Android patch (experimental)

This patch adds a StarPTP UDP peer to the existing Android client and corrects two StartStreaming fields inferred from the Salute TV sender implementation.

## Files

- `StarPtpClient.kt` — binds UDP/4391, sends Sync (0x10) and DelayReq (0x11) every 1000 us, answers incoming DelayReq with DelayResp (0x12), and reports packet counters.
- `StreamEngine.ptp.patch` — changes `master_device_id` from the speaker serial to the local SberCast `clientId`; starts PTP before StartStreaming; sends a monotonic-millisecond start timestamp instead of zero; logs PTP counters.
- `StarPtpCodecTest.kt` — standalone wire-format tests.

## Apply

1. Copy `StarPtpClient.kt` into `app/src/main/java/ru/drdroid78/sberboom/audio/`.
2. From the Android project root, apply `patches/StreamEngine.ptp.patch` with `git apply` or `patch -p1`.
3. Build and install the APK.
4. Test with the SberBoom on the same LAN and inspect logs for `StarPTP stats`.

## Verification performed

- `StarPtpClient.kt` compiled with standalone Kotlin compiler.
- Three packet codec checks passed: Sync, DelayReq and DelayResp byte layouts.
- The StreamEngine diff passes `patch -p1 --dry-run` against the source version in the supplied project archive.
- Full Android Gradle build was not completed in this environment: Gradle 8.9 distribution was not cached and outbound DNS/network was unavailable.

## Important limits

- The protocol details were reverse-engineered from Salute TV 1.100.39 `libstardroid.so`; SberBoom firmware compatibility is not yet proven.
- The patch does not bypass authentication and does not generate a backend JWT. StarCommand still requires a valid `BACKEND_JWT`.
- It does not send StarCommand 0x9F. It relies on the speaker's local PTP service learning the connected client from the SberCast session and UDP exchange.
- The choice `master_device_id = clientId` is strongly supported by the stock sender path, but still requires a physical SberBoom test.
- The start timestamp uses the observed Salute TV defaults: 400 ms delay + 512/48 ms block lead, converted to integer milliseconds.

This is a test patch, not a verified working APK.
