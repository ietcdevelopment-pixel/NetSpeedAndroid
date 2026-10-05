# Net Speed Test

A small Android 7.0+ network speed test using Cloudflare's speed endpoints.

## What it measures

- Median latency from seven lightweight HTTPS requests
- Download throughput from a streamed 50 MB response
- Upload throughput from a streamed 10 MB request
- Active network transport: Wi-Fi, Mobile Data, Ethernet, VPN, or no network

The app has no login, advertising, analytics, or background service. It requests only
`INTERNET` and `ACCESS_NETWORK_STATE`.

## Build

```shell
./gradlew clean
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
