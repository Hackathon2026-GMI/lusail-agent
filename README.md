# Lusail Stadium Matchday Companion

On-device AI companion for the Lusail Iconic Stadium in Qatar. Scans QR codes at gates, concessions, and merchandise stands, then displays 2-3 floating action bubbles powered by Gemma 4 running locally.

## Features

- **QR Security Validation** — Mr_QR integration: every scanned QR payload is validated by DeepSeek-V4-Pro on GMI Cloud before bubbles are generated
- **CryptoShield Verification** — Holographic seal animation on app launch
- **Floating Bubble UX** — 2-3 contextual action bubbles with staggered animations
- **On-Device Inference** — Gemma 4 via `localhost:8080` for privacy-preserving fan interactions
- **Deep Link Support** — `open.lusail.qa://scan?...` for ticket validation, wayfinding, concessions
- **WebView Bridge** — JavaScript bridge for `open.lusail.qa` web integration

## Architecture

```
QR Scan → Mr_QR Validator (GMI Cloud) → Gemma 4 Inference (localhost) → Floating Bubbles
```

## Setup

1. Set `GMI_API_KEY` in `MainActivity.kt` companion object
2. Ensure Gemma 4 inference server is running on `localhost:8080`
3. Build with Android Studio or `./gradlew assembleDebug`
