# TailorPA / Suhani

Suhani is a Hindi voice assistant (Android app) for Surya's mother, a home tailor.
She manages clients, orders, payments/dues, and voice reminders through natural Hindi/Hinglish conversation.
Target device: POCO X6 5G (Android 16, HyperOS), sideloaded debug APK.

## Repo layout

- `suhani-app/` - the PRODUCTION app. Kotlin, plain Views, minSdk 33, arm64-only. Everything runs on-device except Sarvam API calls (Saaras v4 STT, Sarvam-105B brain, Bulbul v3 TTS).
- `silai/` - Python mirror + test harness. Used to gauntlet-test prompts and mirror ledger logic for parity tests. NOT production.
- `deploy/` - leftover from the old LiveKit/Docker server stack. Dead. `web/` and `webapp/` (the React PWA and its build output) were deleted when the Android app took over; recover them from git history if the benchmark is ever needed. Do not extend any of it.
- `silai/web.py` and `config.WEB_DIR` still point at the deleted `web/` directory, so `.\dev.ps1 web` will fail at startup. Harness commands (`test`, `chat`, `smoke`) are unaffected.
- `suhani.apk` at repo root - the current build artifact. Refresh it after every build.

## Build and test

```powershell
cd suhani-app
.\gradlew.bat assembleDebug testDebugUnitTest --no-daemon
Copy-Item app\build\outputs\apk\debug\app-debug.apk ..\suhani.apk -Force
```

Unit tests are pure-JVM (sqlite-jdbc driver seam), no emulator needed.
All 56 must pass before shipping an APK.

## Hard rules

- `suhani-app/.../core/Ledger.kt` is the source of truth for ledger logic. Mirror changes into `silai/ledger.py` only when harness parity needs it.
- EVERY write tool needs an idempotency guard. The Sarvam model multi-fires tool calls; add_client, add_order, mark_paid, and addReminder all have guards - keep the pattern for new tools.
- Ledger validation errors are Hindi strings; they flow back to the model as tool results, so keep them short and speakable.
- Prompt lines in `Prompts.kt` are gauntlet-tested (300/304). Do not reword existing lines casually; add new lines instead, and re-run the harness subset if behavior might drift.
- Use positive-only prompt instructions. Negative examples prime the bad tokens in this model.
- Never trust org.json leniency for model output: use the strict parse helpers in `Brain.kt`.

## Keys and debugging

- `SARVAM_API_KEY` lives in `.env` and is baked into the APK via BuildConfig; users can override it in-app (Settings in the ··· menu).
- On-device debugging: ··· menu -> Error log (persistent `AppLog`, copyable). The caption shows exactly what Suhani heard - check that first when replies seem wrong.
