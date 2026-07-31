"""Environment configuration. All secrets live in .env (see .env.example)."""

import os
from pathlib import Path

from dotenv import load_dotenv

ROOT = Path(__file__).resolve().parent.parent
load_dotenv(ROOT / ".env")

ANTHROPIC_API_KEY = os.environ.get("ANTHROPIC_API_KEY", "")
SARVAM_API_KEY = os.environ.get("SARVAM_API_KEY", "")

LIVEKIT_URL = os.environ.get("LIVEKIT_URL", "")
LIVEKIT_API_KEY = os.environ.get("LIVEKIT_API_KEY", "")
LIVEKIT_API_SECRET = os.environ.get("LIVEKIT_API_SECRET", "")

# Which LLM drives Suhani: "sarvam" (Sarvam-105B, the launch brain since 2026-07-29)
# or "claude" (Claude Sonnet 5 fallback - one env-var away, no code changes).
BRAIN = os.environ.get("BRAIN", "sarvam").strip().lower()

CLAUDE_MODEL = os.environ.get("CLAUDE_MODEL", "claude-sonnet-5")

# Sarvam brain settings - the bake-off's winning "fixed/low" configuration.
# More effort tested WORSE on this model; budgets below 1024 starve reasoning.
SARVAM_CHAT_MODEL = os.environ.get("SARVAM_CHAT_MODEL", "sarvam-105b")
SARVAM_CHAT_BASE_URL = os.environ.get("SARVAM_CHAT_BASE_URL", "https://api.sarvam.ai/v1")
SARVAM_CHAT_EFFORT = os.environ.get("SARVAM_CHAT_EFFORT", "low")
SARVAM_CHAT_MAX_TOKENS = int(os.environ.get("SARVAM_CHAT_MAX_TOKENS", "1024"))
VOICE_SPEAKER = os.environ.get("VOICE_SPEAKER", "simran")
VOICE_PACE = float(os.environ.get("VOICE_PACE", "1.05"))
VOICE_TEMPERATURE = float(os.environ.get("VOICE_TEMPERATURE", "0.75"))
REMINDER_HOUR = int(os.environ.get("REMINDER_HOUR", "8"))

DB_PATH = ROOT / os.environ.get("DB_PATH", "data/silai.db")

VAPID_PUBLIC_KEY = os.environ.get("VAPID_PUBLIC_KEY", "")
VAPID_PRIVATE_KEY = os.environ.get("VAPID_PRIVATE_KEY", "")
VAPID_SUBJECT = os.environ.get("VAPID_SUBJECT", "mailto:example@example.com")

WEB_DIR = ROOT / "web"

# Prefix only - every /api/token call mints a FRESH room "silai-bahi-<hex>".
# This is load-bearing, not cosmetic: LiveKit dispatches an agent job when a
# room is CREATED. A fixed room name means the second conversation rejoins a
# room that still exists (empty rooms linger ~5 min), no job is dispatched, no
# agent ever joins, and the caller sits on "Suhani didn't answer" forever.
ROOM_PREFIX = "silai-bahi"

# Shared secret guarding every API route once the app is reachable from the
# internet. She receives it once inside the install URL (https://host/?k=...),
# the PWA stores it and replays it as the X-Suhani-Key header, so she never
# sees a login. Empty = open, which is only acceptable on localhost: without it
# anyone who finds the hostname can read the whole ledger and mint a LiveKit
# token that joins her room. Generate one with: python -m silai.genkey
APP_KEY = os.environ.get("APP_KEY", "").strip()


def missing_keys() -> list[str]:
    """Names of required keys that are still unset/TODO, for friendly startup errors."""
    required = {
        "SARVAM_API_KEY": SARVAM_API_KEY,  # STT + TTS always; also the brain when BRAIN=sarvam
        "LIVEKIT_URL": LIVEKIT_URL,
        "LIVEKIT_API_KEY": LIVEKIT_API_KEY,
        "LIVEKIT_API_SECRET": LIVEKIT_API_SECRET,
    }
    if BRAIN == "claude":
        required["ANTHROPIC_API_KEY"] = ANTHROPIC_API_KEY
    return [k for k, v in required.items() if not v or v.startswith("TODO")]
