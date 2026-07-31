"""Validate all API keys WITHOUT spending anything. Run: python -m silai.check_keys

Zero-cost methods:
- Anthropic: GET /v1/models (auth-only endpoint, no tokens billed)
- Sarvam:    intentionally-invalid TTS request; a valid key gets a 400/422
             validation error (nothing synthesized, nothing billed), an invalid
             key gets 401/403
- LiveKit:   ListRooms server API call (free control-plane call)
"""

import asyncio
import sys

import httpx

from . import config

OK = "PASS"
BAD = "FAIL"


def check_anthropic() -> tuple[str, str]:
    import anthropic

    try:
        client = anthropic.Anthropic(api_key=config.ANTHROPIC_API_KEY)
        models = [m.id for m in client.models.list(limit=50)]
        note = f"auth OK, {len(models)} models visible"
        if config.CLAUDE_MODEL not in models:
            note += f"; NOTE: configured model '{config.CLAUDE_MODEL}' not in list (aliases may still resolve)"
        return OK, note
    except anthropic.AuthenticationError:
        return BAD, "invalid API key (401)"
    except anthropic.PermissionDeniedError as e:
        return BAD, f"key rejected (403): {e.message[:120]}"
    except Exception as e:
        return BAD, f"{type(e).__name__}: {str(e)[:160]}"


def check_sarvam() -> tuple[str, str]:
    try:
        r = httpx.post(
            "https://api.sarvam.ai/text-to-speech",
            headers={"api-subscription-key": config.SARVAM_API_KEY},
            json={},  # deliberately invalid: rejected before any billable work
            timeout=20,
        )
        if r.status_code in (401, 403):
            return BAD, f"invalid API key ({r.status_code})"
        if r.status_code in (400, 422):
            return OK, f"auth OK (validation error {r.status_code} as expected, nothing billed)"
        return OK, f"unexpected status {r.status_code} but not an auth failure: {r.text[:120]}"
    except Exception as e:
        return BAD, f"{type(e).__name__}: {str(e)[:160]}"


async def _check_livekit_async() -> tuple[str, str]:
    from livekit import api as lk_api

    url = config.LIVEKIT_URL.replace("wss://", "https://").replace("ws://", "http://")
    lkapi = lk_api.LiveKitAPI(url=url, api_key=config.LIVEKIT_API_KEY, api_secret=config.LIVEKIT_API_SECRET)
    try:
        rooms = await lkapi.room.list_rooms(lk_api.ListRoomsRequest())
        return OK, f"auth OK, {len(rooms.rooms)} active room(s)"
    except Exception as e:
        msg = str(e)[:160]
        if "401" in msg or "unauthorized" in msg.lower() or "invalid" in msg.lower():
            return BAD, f"invalid key/secret: {msg}"
        return BAD, f"{type(e).__name__}: {msg}"
    finally:
        await lkapi.aclose()


def check_livekit() -> tuple[str, str]:
    try:
        return asyncio.run(_check_livekit_async())
    except Exception as e:
        return BAD, f"{type(e).__name__}: {str(e)[:160]}"


def main() -> None:
    checks = [
        ("ANTHROPIC_API_KEY", config.ANTHROPIC_API_KEY, check_anthropic),
        ("SARVAM_API_KEY", config.SARVAM_API_KEY, check_sarvam),
        ("LIVEKIT (url+key+secret)", config.LIVEKIT_API_KEY, check_livekit),
    ]
    failures = 0
    for name, value, fn in checks:
        if not value or value.startswith("TODO"):
            print(f"{BAD}  {name}: not set in .env")
            failures += 1
            continue
        status, note = fn()
        print(f"{status}  {name}: {note}")
        if status == BAD:
            failures += 1
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
