# सुहानी (Suhani) - आपकी सिलाई बही

A real-time Hindi voice assistant for a home tailoring business, named Suhani (after her Bulbul v3 voice).
One button, speak in Hindi/Hinglish: orders, dues, and delivery dates go into the ledger; ask "आज क्या देना है?" any time.

## Architecture

- **Ears:** Sarvam Saaras v4 streaming STT (Hinglish codemix mode)
- **Brain:** Sarvam-105B, tool-calling over the ledger (`BRAIN=sarvam`, the default)
- **Voice:** Sarvam Bulbul v3 streaming TTS (speaker `suhani`, pace and expressiveness tunable)
- **Call transport:** LiveKit Agents + LiveKit Cloud (WebRTC, barge-in, turn detection)
- **Ledger:** SQLite (`data/silai.db`), fuzzy transliteration-aware client matching
- **App:** installable PWA - React 19 + LiveKit React components + Tailwind 4 (`webapp/`), web-push due-date reminders

The ledger (`silai/ledger.py`) is the single source of truth.
The voice agent (`silai/agent.py`) and the text debug chat (`silai/brain_text.py`) are thin layers over it.

### The brain

Suhani runs on **Sarvam-105B** at `reasoning_effort=low`, reached through Sarvam's OpenAI-compatible endpoint.
That exact configuration was picked by bake-off, not by taste: across ~1,200 scored calls it beat every alternative setting, and raising the effort made it measurably worse (wrong due dates, invented clients, slower replies).
It is also faster than the Claude path it replaced (TTFT 1.0-1.3s vs ~1.8s warm) and costs roughly nothing at this volume.

Because the model needs more scaffolding than a frontier model does, `silai/prompts.py` carries `SARVAM_GUARDS` and a rolling 21-day `calendar_table()`, and `silai/tools.py` exposes a deterministic `resolve_due_date` tool.
Weekday arithmetic goes through that tool rather than through the model.

Claude stays wired up as a fallback: set `BRAIN=claude` in `.env` and Suhani switches brains on the next restart, no code change.
`CLAUDE_MODEL` picks the model (default `claude-sonnet-5`).
Everything else in the pipeline is brain-agnostic.

## Setup (Windows, first time)

1. Get the keys (details in the `.env.example` comments):
   **Sarvam** (dashboard.sarvam.ai) drives all three of brain, ears and voice, so it is the one key you cannot skip.
   **LiveKit Cloud** (cloud.livekit.io) carries the call.
   **Anthropic** (platform.claude.com) is only needed if you switch to `BRAIN=claude`.
2. Run:

```powershell
.\dev.ps1 setup     # venv + deps + .env template + push keys + tests
notepad .env        # paste the keys
.\dev.ps1 keys      # verify all keys without spending anything
```

## Run locally

```powershell
.\dev.ps1 web       # http://localhost:8080  (PWA + /api/chat debug + reminders)
.\dev.ps1 agent     # voice agent worker (separate terminal)
.\dev.ps1 chat      # text-only chat with the same brain, in the terminal
.\dev.ps1 keys      # zero-spend check that all three providers accept the keys
.\dev.ps1 smoke     # scripted Hindi order flow against a throwaway DB
.\dev.ps1 test      # ledger tests, no keys needed
.\dev.ps1 demo      # seed demo clients/orders (refuses non-empty ledger; --force to override)
.\dev.ps1 voices    # synthesize every Bulbul female voice into data/voice_demo/ to re-pick
.\dev.ps1 tunnel    # HTTPS URL for testing on a phone (cloudflared)
```

Open http://localhost:8080, press the orb, speak Hindi.
The app has two screens (one React app, no page loads between them): the orb, and the बही behind the "Ledger" button.
`/debug.html` is a separate static page for text chat with the same brain, for testing without audio.
For phone testing, use the tunnel URL (mic requires HTTPS off-localhost).

## The frontend

`webapp/` is the React source; `web/` is its build output and is committed.
Committing the output is deliberate: the VPS builds a Docker image without ever running Node, which keeps
`deploy/Dockerfile` a single Python stage and avoids npm resolving platform binaries for the wrong architecture.

```powershell
.\dev.ps1 ui        # build webapp/ -> web/   (run after ANY UI change, then commit web/)
.\dev.ps1 ui-dev    # Vite dev server on :5173 with HMR, /api proxied to :8080
```

The voice UI is built on `@livekit/components-react`: `useVoiceAssistant` for Suhani's real state
(initializing / listening / thinking / speaking), `useAudioPlayback` to recover when Chrome blocks autoplay, and
`BarVisualizer` while Suhani speaks.

The centrepiece is LiveKit's **Aura** shader visualizer, vendored into `webapp/src/components/aura/` from
`livekit-examples/agent-starter-react` and wrapped by `webapp/src/components/SuhaniAura.tsx`.
`SuhaniAura` feeds it the **local microphone** track while listening and the agent's track while Suhani speaks, so the
shape answers "can you hear me?" honestly instead of only animating when the agent talks.
`use-aura.ts` carries one local modification for that, marked `MODIFIED FROM UPSTREAM`.
The aura's colour is the single `ACCENT` constant at the top of `SuhaniAura.tsx`; LiveKit's own default is `#1FD5F9`.

Two gotchas recorded in comments there, both learned the hard way:
the shader's `uBloom` is pinned to 0 upstream on purpose, because bloom raises alpha across the whole quad and turns the
transparent canvas into a visible square, so the glow is a blurred CSS radial behind the canvas instead;
and `react-shader-toy.tsx` leaves its drawing buffer at 300x150, which costs sharpness at `devicePixelRatio` 1 but is
fine on a real phone, and must not be "fixed" by resizing the canvas externally because `iResolution` would go stale.

Licence note: the repo is MIT, but `aura.tsx` carries its own header naming "PolyForm Non-Resale License 1.0.0", which
is not a licence that exists (the PolyForm family has no Non-Resale variant and the cited URL 404s).
A private, never-sold app satisfies any reading of "non-resale"; revisit before shipping this commercially.

Two things in `webapp/src/index.css` are load-bearing and easy to break:
Devanagari gets `line-height: 1.75` and `letter-spacing: 0` under a `:lang(hi)` rule, because Tailwind's Preflight
sets `line-height: 1.5` globally, which clips matras, and any tracking at all splits conjuncts.
Fonts are self-hosted in `webapp/public/fonts/`; the rupee sign lives in Inter's latin-**ext** subset, so removing
that file makes every amount reflow into a fallback face.

## Going live (tunnel)

The moment this is reachable from the internet, `APP_KEY` is mandatory.
Every `/api/` route and `/debug.html` is gated behind it; the static shell stays open so the installed PWA can boot from
`start_url` `/` without a key in the URL.
If `APP_KEY` is unset the server still serves localhost normally but **refuses every non-local request with a 503**, so
a forgotten key fails closed instead of publishing her ledger.

```powershell
.\dev.ps1 genkey    # generate, paste APP_KEY into .env
.\dev.ps1 web       # terminal 1
.\dev.ps1 agent     # terminal 2
.\dev.ps1 tunnel    # terminal 3 -> https://<random>.trycloudflare.com
.\dev.ps1 link      # prints the /?k=... suffix to append
```

Open `https://<tunnel-host>/?k=<APP_KEY>` **once** on her phone.
The app stores the key, strips it from the address bar, and sends it as `X-Suhani-Key` from then on, so she never sees a
login and the home-screen icon keeps working.
Treat that link like a password: anyone holding it can read the ledger and join her room.
Rotating it is `genkey` + new `.env` value + restart + re-send the link.

## Known problem: the agent worker is unstable on Windows

Running the worker on Windows, it dies after roughly every session, in two ways:

1. The turn detector's separate "inference" process loses its IPC pipe (`DuplexClosed` / `WinError 64`).
2. The native WebRTC layer panics: `thread 'tokio-rt-worker' panicked at webrtc-sys\src\rtc_error.rs: malformed serialized RtcError`,
   followed by `worker process is not responding.. worker crashed?`.

After (2) **the process is still alive but accepts no jobs**, so "is python running" is a false positive.
The web server keeps serving happily and the only symptom is "Suhani didn't answer" on her phone.

`.\dev.ps1 agent` therefore runs `deploy/agent-supervisor.ps1`, which polls the worker's own health endpoint on
`:8081` and hard-restarts it when it stops answering.
This makes the failure survivable, not absent: a tap that lands during the ~15-30s recovery window still fails, so
roughly every other conversation can need a second try.

**The actual fix is to stop running the worker on Windows.** Neither failure occurs on Linux, where
`restart: unless-stopped` in docker-compose covers the same ground. Treat the laptop setup as testing only.

Run `.\dev.ps1 status` whenever voice misbehaves - it checks web, the agent's health endpoint, and the tunnel,
and tells you which one is down.

## Deploy (later: ₹0 Oracle free tier or any small VPS)

One command on a fresh Ubuntu server (installs Docker, configures the domain, starts everything):

```bash
bash deploy/server-setup.sh yourdomain.example.in
# then fill .env and: docker compose -f deploy/docker-compose.yml up -d --build
```

The ledger backs itself up daily to `data/backups/` (14 kept); copying `data/` anywhere is a full backup.

## Notes

- Language split: the app's own chrome (buttons, headings, status line, push labels) is English; everything that comes out of the ledger
  or out of Suhani's mouth stays Hindi.
  Data elements carry `lang="hi"`, which is what triggers the Devanagari type rules described above.
- After editing anything in `webapp/`, run `.\dev.ps1 ui` and commit `web/` alongside it, or the deployed app keeps the old UI.
- `python -m silai.agent download-files` fetches VAD + turn-detector models (done by `setup`).
- Brain switch: `BRAIN=sarvam` (default) or `BRAIN=claude`, no code change either way.
  Leave `SARVAM_CHAT_EFFORT=low`; higher effort tested worse on date arithmetic and client disambiguation.
- Voice selection: `VOICE_SPEAKER` in `.env` (currently `suhani`), tuned with `VOICE_PACE=1.05` and `VOICE_TEMPERATURE=0.75`.
  Run `.\dev.ps1 voices` to audition the alternatives before changing it.
- Daily reminder push time: `REMINDER_HOUR` (IST, default 8).
- Backup: `data/` is the only state; copy it anywhere. Litestream can stream it to object storage later.
