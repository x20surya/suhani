"""Web server: serves the PWA, mints LiveKit tokens, hosts the debug text chat,
stores push subscriptions, and runs the daily reminder loop.

Run:  uvicorn silai.web:app --host 0.0.0.0 --port 8080
"""

import asyncio
import hmac
import json
import logging
import uuid

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from livekit import api as lk_api
from pydantic import BaseModel

from . import config, reminders
from .brain_text import ChatSession
from .ledger import Ledger

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("silai.web")


app = FastAPI(title="Suhani")

# Paths that carry data or spend money. The static shell (/, /assets, /fonts,
# the manifest, the service worker) stays open on purpose: it contains nothing
# private, and the installed PWA opens start_url "/" with no key in the URL, so
# gating it would just show her a 401 instead of the app.
GUARDED_PREFIXES = ("/api/", "/debug.html")


def _key_ok(request: Request) -> bool:
    supplied = request.headers.get("X-Suhani-Key") or request.query_params.get("k") or ""
    # compare_digest: constant time, so the key cannot be recovered by timing.
    return hmac.compare_digest(supplied, config.APP_KEY)


def _is_local(request: Request) -> bool:
    host = (request.client.host if request.client else "") or ""
    return host in ("127.0.0.1", "::1", "localhost")


@app.middleware("http")
async def guard(request: Request, call_next):
    """Gate the API behind the shared app key.

    Middleware rather than a route dependency because /debug.html is served by
    the catch-all StaticFiles mount, which bypasses the router entirely.

    An unset APP_KEY leaves everything open. That is fine on localhost and NOT
    fine on a tunnel, so refuse remote callers outright rather than silently
    serving her ledger to whoever finds the hostname.
    """
    if request.url.path.startswith(GUARDED_PREFIXES):
        if not config.APP_KEY:
            if not _is_local(request):
                return JSONResponse(
                    {
                        "detail": "APP_KEY is not set, so remote access is refused. "
                        "Run `python -m silai.genkey`, put APP_KEY in .env, restart."
                    },
                    status_code=503,
                )
        elif not _key_ok(request):
            return JSONResponse({"detail": "Bad or missing key"}, status_code=401)
    return await call_next(request)

_ledger = Ledger()
_chat_sessions: dict[str, ChatSession] = {}


@app.on_event("startup")
async def _startup() -> None:
    if missing := config.missing_keys():
        logger.warning("Missing keys in .env (fill these in): %s", ", ".join(missing))
    asyncio.create_task(reminders.reminder_loop(_ledger))


@app.get("/healthz")
async def healthz() -> dict:
    return {"ok": True, "missing_keys": config.missing_keys()}


# ---------- voice: LiveKit access token for the PWA ----------

@app.get("/api/token")
async def token(identity: str = "mom") -> dict:
    if not config.LIVEKIT_API_KEY or config.LIVEKIT_API_KEY.startswith("TODO"):
        raise HTTPException(503, "LiveKit keys not configured yet (.env)")
    # Unique identity per connection: a stale tab or double-tap must never kick
    # the live session via a duplicate-identity join.
    unique = f"{identity}-{uuid.uuid4().hex[:6]}"
    # FRESH room per conversation. LiveKit dispatches an agent job on room
    # CREATION, so reusing one name means only the first conversation ever gets
    # an agent - every later tap rejoins the lingering empty room, no job is
    # dispatched, and the caller waits out the timeout on "Suhani didn't answer".
    room_name = f"{config.ROOM_PREFIX}-{uuid.uuid4().hex[:8]}"
    at = (
        lk_api.AccessToken(config.LIVEKIT_API_KEY, config.LIVEKIT_API_SECRET)
        .with_identity(unique)
        .with_name(identity)
        .with_grants(lk_api.VideoGrants(room_join=True, room=room_name))
    )
    logger.info("minted token for room %s", room_name)
    return {"url": config.LIVEKIT_URL, "token": at.to_jwt(), "room": room_name}


# ---------- debug text chat (same brain, no audio) ----------

class ChatIn(BaseModel):
    message: str
    session_id: str | None = None


@app.post("/api/chat")
async def chat(body: ChatIn) -> dict:
    sid = body.session_id or uuid.uuid4().hex
    session = _chat_sessions.setdefault(sid, ChatSession(ledger=_ledger))
    reply = await asyncio.to_thread(session.send, body.message)
    return {"session_id": sid, "reply": reply}


# ---------- ledger peek (debug) ----------

@app.get("/api/briefing")
async def briefing() -> dict:
    return _ledger.daily_briefing()


@app.get("/api/clients")
async def clients() -> dict:
    return {"clients": _ledger.list_clients(), "dues": _ledger.all_dues()}


@app.get("/api/orders")
async def orders() -> dict:
    return {
        "open_orders": _ledger.all_open_orders(),
        "dues": _ledger.all_dues(),
        "recent_payments": _ledger.recent_payments(),
        "notes": _ledger.list_notes(),
    }


# ---------- web push ----------

@app.get("/api/vapid-public-key")
async def vapid_public_key() -> dict:
    return {"key": config.VAPID_PUBLIC_KEY}


class SubscriptionIn(BaseModel):
    subscription: dict


@app.post("/api/push/subscribe")
async def push_subscribe(body: SubscriptionIn) -> dict:
    _ledger.conn.execute(
        "INSERT OR IGNORE INTO push_subs (subscription) VALUES (?)",
        (json.dumps(body.subscription),),
    )
    _ledger.conn.commit()
    return {"ok": True}


@app.post("/api/push/test")
async def push_test() -> dict:
    n = reminders.send_push_to_all(
        _ledger.conn, {"title": "Suhani", "body": "This is a test reminder."}
    )
    return {"sent": n}


# ---------- PWA static files (keep last: catch-all mount) ----------

@app.get("/")
async def index() -> FileResponse:
    return FileResponse(config.WEB_DIR / "index.html")


app.mount("/", StaticFiles(directory=config.WEB_DIR), name="web")
