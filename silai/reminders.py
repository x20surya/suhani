"""Due-date reminders: daily briefing composed in Hindi, delivered via web push."""

import asyncio
import datetime as dt
import json
import logging
from zoneinfo import ZoneInfo

from pywebpush import WebPushException, webpush

from . import config
from .ledger import Ledger

IST = ZoneInfo("Asia/Kolkata")
logger = logging.getLogger("silai.reminders")


def briefing_text(ledger: Ledger) -> str | None:
    """Short summary for the morning push. None = nothing worth pinging about.

    Labels are English to match the app chrome; client names and garments stay
    in the ledger's own script.
    """
    b = ledger.daily_briefing()
    parts: list[str] = []
    if b["due_today"]:
        names = ", ".join(f"{o['client_name']} ({o['garment']})" for o in b["due_today"][:3])
        parts.append(f"Due today: {names}")
    if b["overdue"]:
        n = len(b["overdue"])
        parts.append(f"{n} job{'s' if n != 1 else ''} late")
    total_due = sum(d["due"] for d in b["money_pending"])
    if total_due > 0:
        parts.append(f"₹{int(total_due)} outstanding")
    return ". ".join(parts) + "." if parts else None


def send_push_to_all(conn, payload: dict) -> int:
    """Send a push to every stored subscription; prunes dead ones. Returns sent count."""
    if not config.VAPID_PRIVATE_KEY:
        logger.warning("VAPID keys not set - skipping push")
        return 0
    sent = 0
    rows = conn.execute("SELECT id, subscription FROM push_subs").fetchall()
    for row in rows:
        try:
            webpush(
                subscription_info=json.loads(row["subscription"]),
                data=json.dumps(payload, ensure_ascii=False),
                vapid_private_key=config.VAPID_PRIVATE_KEY,
                vapid_claims={"sub": config.VAPID_SUBJECT},
            )
            sent += 1
        except WebPushException as e:
            status = getattr(getattr(e, "response", None), "status_code", None)
            if status in (404, 410):  # subscription expired - remove it
                conn.execute("DELETE FROM push_subs WHERE id=?", (row["id"],))
                conn.commit()
            else:
                logger.warning("push failed: %s", e)
    return sent


async def reminder_loop(ledger: Ledger) -> None:
    """Once per day at REMINDER_HOUR IST: send the briefing push + back up the DB."""
    from . import db

    last_sent_date: str | None = None
    while True:
        try:
            now = dt.datetime.now(IST)
            today = now.date().isoformat()
            if now.hour >= config.REMINDER_HOUR and last_sent_date != today:
                text = briefing_text(ledger)
                if text:
                    n = send_push_to_all(
                        ledger.conn,
                        {"title": "Suhani", "body": text},
                    )
                    logger.info("daily briefing push sent to %d device(s)", n)
                try:
                    dest = db.backup(ledger.conn, config.DB_PATH.parent / "backups")
                    logger.info("ledger backed up to %s", dest)
                except Exception:
                    logger.exception("backup failed")
                last_sent_date = today
        except Exception:
            logger.exception("reminder loop error")
        await asyncio.sleep(60)
