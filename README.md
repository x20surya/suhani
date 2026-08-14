# सुहानी (Suhani) - आपकी सिलाई बही

A real-time Hindi voice assistant for a home tailoring business, named Suhani (after her Bulbul v3 voice).
One button, speak in Hindi/Hinglish: orders, dues, and delivery dates go into the ledger; ask "आज क्या देना है?" any time.

Suhani is an Android app. Everything runs on-device except the three Sarvam API calls.

## Architecture

- **Ears:** Sarvam Saaras v4 streaming STT (Hinglish codemix mode)
- **Brain:** Sarvam-105B, tool-calling over the ledger
- **Voice:** Sarvam Bulbul v3 streaming TTS (speaker `suhani`, pace and expressiveness tunable)
- **Turn-taking:** on-device Silero VAD (v5 ONNX) plus a speaker gate, so side conversation in the room does not trigger a turn
- **Ledger:** SQLite on the device, fuzzy transliteration-aware client matching
- **Reminders:** `ReminderScheduler` over `AlarmManager`, firing a full-screen alarm activity

`suhani-app/.../core/Ledger.kt` is the single source of truth.
`ConversationEngine.kt` drives the mic → STT → brain → tools → TTS loop; everything else is a thin layer over those two.

Target device is a POCO X6 5G (Android 16, HyperOS), running a sideloaded debug APK.
`minSdk 33`, `arm64-v8a` only, which halves the APK and costs nothing on any modern phone.

## Repo layout

- `suhani-app/` - the production app. Kotlin, plain Views, no Compose.
- `silai/` - Python mirror and test harness. Used to gauntlet-test prompts and mirror ledger logic for parity tests. **Not production.**
- `suhani.apk` - the current build artifact at repo root. Refresh it after every build.

## The brain

Suhani runs on **Sarvam-105B** at `reasoning_effort=low`, through Sarvam's OpenAI-compatible endpoint.
That configuration was picked by bake-off, not by taste: across roughly 1,200 scored calls it beat every alternative setting, and raising the effort made it measurably worse (wrong due dates, invented clients, slower replies).
It is also faster than the Claude path it replaced (TTFT 1.0-1.3s against about 1.8s warm) and costs close to nothing at this volume.

Because the model needs more scaffolding than a frontier model does, `Prompts.kt` carries the guard lines and a rolling 21-day calendar table, and `Tools.kt` exposes a deterministic `resolve_due_date`.
Weekday arithmetic goes through that tool rather than through the model.

Prompt lines in `Prompts.kt` are gauntlet-tested at 300/304.
Do not reword existing lines casually. Add new lines instead, and re-run the harness subset if behaviour might drift.

## Hard rules

- Every write tool needs an idempotency guard. The Sarvam model multi-fires tool calls; `add_client`, `add_order`, `mark_paid` and `addReminder` all have guards. Keep the pattern for new tools.
- Ledger validation errors are Hindi strings. They flow back to the model as tool results, so keep them short and speakable.
- Use positive-only prompt instructions. Negative examples prime the bad tokens in this model.
- Never trust `org.json` leniency for model output. Use the strict parse helpers in `Brain.kt`.

## Build and test

```powershell
cd suhani-app
.\gradlew.bat assembleDebug testDebugUnitTest --no-daemon
Copy-Item app\build\outputs\apk\debug\app-debug.apk ..\suhani.apk -Force
```

Unit tests are pure JVM through a sqlite-jdbc driver seam, so no emulator is needed.
All 56 must pass before shipping an APK.

## Keys and debugging

`SARVAM_API_KEY` lives in `.env` and is baked into the APK via `BuildConfig`.
It can be overridden in-app under Settings, in the `···` menu.

On-device debugging is the `···` menu → Error log, a persistent copyable `AppLog`.
The caption shows exactly what Suhani heard: check that first when a reply looks wrong.

The Python harness in `silai/` has its own `.env` keys, documented in `.env.example`.

## Attribution and licence

`ui/AuraView.kt` is vendored from `livekit-examples/agent-starter-react` and carries an Unicorn Studio
"PolyForm Non-Resale License 1.0.0" header. That licence does not exist: the PolyForm family has no
Non-Resale variant and the cited URL 404s. A private, never-sold app satisfies any reading of
"non-resale", but this must be revisited before shipping commercially.

`voice/SileroVad.kt` uses the Silero VAD v5 ONNX model, the same file `livekit-plugins-silero` ships.

There is no `LICENSE` file at the repo root. Add one before making this public.

## Notes

- Language split: the app's own chrome (buttons, headings, status line) is English; everything from the ledger or out of Suhani's mouth stays Hindi.
- Devanagari needs a looser line-height than a default 1.5, which clips matras, and zero letter-spacing, because any tracking at all splits conjuncts.
- The `web/`, `webapp/` and `deploy/` LiveKit + Docker + PWA stack was removed once the Android app took over. It is recoverable from git history if the benchmark is ever needed again.
