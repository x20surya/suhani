"""The ledger: clients, orders, payments, dues.

Single source of truth for both the voice agent and the text debug chat.
Every function returns plain dicts (JSON-safe) so tool layers stay thin.
Client lookup is always fuzzy: raw STT names never touch the DB directly.
"""

import datetime as dt
import json
import sqlite3
from zoneinfo import ZoneInfo

from . import db, translit

IST = ZoneInfo("Asia/Kolkata")

MATCH_THRESHOLD = 0.72

# Filler words that ride along in spoken references ("जैन वाली", "सुनीता जी")
_FILLER_TOKENS = {"val", "vala", "vale", "ji"}


def _norm_tokens(text: str) -> set[str]:
    toks = {translit.normalize(w) for w in text.split()}
    return {t for t in toks if len(t) >= 3 and t not in _FILLER_TOKENS}


def _token_close(t: str, u: str) -> bool:
    if t == u:
        return True
    if len(t) >= 4 and (t in u or u in t):
        return True
    return translit.similarity(t, u) >= 0.8


def _tokens_covered(needles: set[str], haystack: set[str]) -> bool:
    """True if every needle token has a close counterpart in haystack."""
    return bool(needles) and all(any(_token_close(t, u) for u in haystack) for t in needles)


class Ledger:
    def __init__(self, conn: sqlite3.Connection | None = None):
        self.conn = conn or db.connect()

    # ---------- clients ----------

    def add_client(self, name: str, phone: str | None = None) -> dict:
        norm = translit.normalize(name)
        # Idempotency guard: a voice loop can double-fire add_client. An identical
        # (normalized) name is a duplicate, not a second person - real namesakes get
        # distinguishing names ("सुनीता जैन", "सुनीता वासवानी ब्रेंटवुड वाली").
        dup = self.conn.execute("SELECT id FROM clients WHERE name_norm=?", (norm,)).fetchone()
        if dup:
            if phone and not self.get_client(dup["id"])["phone"]:
                self.conn.execute("UPDATE clients SET phone=? WHERE id=?", (phone, dup["id"]))
                self.conn.commit()
            return {**self.get_client(dup["id"]), "duplicate_ignored": True}
        cur = self.conn.execute(
            "INSERT INTO clients (name, name_norm, aliases, phone) VALUES (?, ?, ?, ?)",
            (name.strip(), norm, json.dumps([name.strip()]), phone),
        )
        self.conn.commit()
        return self.get_client(cur.lastrowid)

    def get_client(self, client_id: int) -> dict:
        row = self.conn.execute("SELECT * FROM clients WHERE id=?", (client_id,)).fetchone()
        if row is None:
            raise ValueError(f"client {client_id} not found")
        return db.row_to_dict(row)

    def add_alias(self, client_id: int, alias: str) -> dict:
        """Record how STT actually heard this client's name, improving future matches."""
        client = self.get_client(client_id)
        aliases = client["aliases"]
        if alias.strip() and alias.strip() not in aliases:
            aliases.append(alias.strip())
            self.conn.execute(
                "UPDATE clients SET aliases=? WHERE id=?", (json.dumps(aliases), client_id)
            )
            self.conn.commit()
        return self.get_client(client_id)

    def find_client(self, query: str) -> list[dict]:
        """Fuzzy, transliteration-aware search. Returns matches ranked by score."""
        # Strip honorifics that ride along in speech.
        q = query.strip()
        for suffix in (" जी", " ji", " didi", " दीदी", " aunty", " आंटी", " आँटी", " भाभी"):
            if q.lower().endswith(suffix.strip().lower()) and len(q) > len(suffix):
                q = q[: -len(suffix)].strip()
        q_norm = translit.normalize(q)
        q_tokens = _norm_tokens(q)
        scored: list[tuple[float, dict]] = []
        for row in self.conn.execute("SELECT * FROM clients"):
            client = db.row_to_dict(row)
            sources = [client["name"]] + client["aliases"]
            score = max(
                translit.similarity(q_norm, translit.normalize(s)) for s in sources
            )
            # Token-aware scoring for real speech patterns: "सुनीता जैन" spoken as
            # just "सुनीता", or with a locality tag ("सुनीता वासवानी ब्रेंटवुड वाली").
            for s in sources:
                s_tokens = _norm_tokens(s)
                if _tokens_covered(s_tokens, q_tokens):
                    score = max(score, 0.9)   # full stored name present in speech
                elif _tokens_covered(q_tokens, s_tokens):
                    score = max(score, 0.85)  # partial reference (may be ambiguous)
            if score >= MATCH_THRESHOLD:
                scored.append((score, client))
        scored.sort(key=lambda t: t[0], reverse=True)
        return [{**c, "match_score": round(s, 2)} for s, c in scored]

    def list_clients(self) -> list[dict]:
        return [db.row_to_dict(r) for r in self.conn.execute("SELECT * FROM clients ORDER BY name")]

    # ---------- orders ----------

    def add_order(
        self,
        client_id: int,
        garment: str,
        price: float,
        due_date: str | None = None,
        advance: float = 0,
        notes: str | None = None,
    ) -> dict:
        self.get_client(client_id)  # validates
        # Idempotency guard: a voice loop can double-fire the same write (double
        # confirmation, retried turn). An identical open order = almost certainly
        # a duplicate, not a second garment - return the existing one instead.
        dup = self.conn.execute(
            """SELECT id FROM orders WHERE client_id=? AND garment=? AND price=?
               AND IFNULL(due_date,'')=IFNULL(?,'') AND status='open'""",
            (client_id, garment.strip(), price, due_date),
        ).fetchone()
        if dup:
            return {**self.get_order(dup["id"]), "duplicate_ignored": True}
        cur = self.conn.execute(
            "INSERT INTO orders (client_id, garment, price, due_date, notes) VALUES (?, ?, ?, ?, ?)",
            (client_id, garment.strip(), price, due_date, notes),
        )
        order_id = cur.lastrowid
        if advance and advance > 0:
            self.conn.execute(
                "INSERT INTO payments (client_id, order_id, amount, note) VALUES (?, ?, ?, 'advance')",
                (client_id, order_id, advance),
            )
        self.conn.commit()
        return self.get_order(order_id)

    def get_order(self, order_id: int) -> dict:
        row = self.conn.execute(
            """SELECT o.*, c.name AS client_name,
                      o.price - COALESCE((SELECT SUM(amount) FROM payments p WHERE p.order_id = o.id), 0) AS balance
               FROM orders o JOIN clients c ON c.id = o.client_id WHERE o.id=?""",
            (order_id,),
        ).fetchone()
        if row is None:
            raise ValueError(f"order {order_id} not found")
        return dict(row)

    def update_order(
        self,
        order_id: int,
        garment: str | None = None,
        price: float | None = None,
        due_date: str | None = None,
        notes: str | None = None,
        status: str | None = None,
    ) -> dict:
        order = self.get_order(order_id)
        fields = {
            "garment": garment if garment is not None else order["garment"],
            "price": price if price is not None else order["price"],
            "due_date": due_date if due_date is not None else order["due_date"],
            "notes": notes if notes is not None else order["notes"],
            "status": status if status is not None else order["status"],
        }
        delivered_at = order["delivered_at"]
        if status == "delivered" and not delivered_at:
            delivered_at = dt.datetime.now(IST).isoformat()
        self.conn.execute(
            "UPDATE orders SET garment=?, price=?, due_date=?, notes=?, status=?, delivered_at=? WHERE id=?",
            (fields["garment"], fields["price"], fields["due_date"], fields["notes"],
             fields["status"], delivered_at, order_id),
        )
        self.conn.commit()
        return self.get_order(order_id)

    def orders_for_client(self, client_id: int, open_only: bool = True) -> list[dict]:
        q = """SELECT o.*, c.name AS client_name,
                      o.price - COALESCE((SELECT SUM(amount) FROM payments p WHERE p.order_id = o.id), 0) AS balance
               FROM orders o JOIN clients c ON c.id = o.client_id
               WHERE o.client_id=?"""
        if open_only:
            q += " AND o.status NOT IN ('delivered', 'cancelled')"
        q += " ORDER BY o.due_date IS NULL, o.due_date"
        return [dict(r) for r in self.conn.execute(q, (client_id,))]

    def all_open_orders(self) -> list[dict]:
        rows = self.conn.execute(
            """SELECT o.*, c.name AS client_name,
                      o.price - COALESCE((SELECT SUM(amount) FROM payments p WHERE p.order_id = o.id), 0) AS balance
               FROM orders o JOIN clients c ON c.id = o.client_id
               WHERE o.status NOT IN ('delivered', 'cancelled')
               ORDER BY o.due_date IS NULL, o.due_date"""
        )
        return [dict(r) for r in rows]

    def search_orders(self, query: str, limit: int = 12) -> list[dict]:
        """Free-text search across ALL orders - open, delivered and cancelled - matching
        query tokens against garment, notes and client name (transliteration-aware).
        Powers "वो maroon वाला किसका था?" even after the order is closed. Ranked by how
        many query tokens matched, then recency."""
        q_tokens = _norm_tokens(query)
        if not q_tokens:
            return []
        rows = self.conn.execute(
            """SELECT o.*, c.name AS client_name,
                      o.price - COALESCE((SELECT SUM(amount) FROM payments p WHERE p.order_id = o.id), 0) AS balance
               FROM orders o JOIN clients c ON c.id = o.client_id
               ORDER BY o.id DESC"""
        )
        scored: list[tuple[float, dict]] = []
        for r in rows:
            o = dict(r)
            hay = _norm_tokens(" ".join(
                str(x) for x in (o["garment"], o["notes"] or "", o["client_name"])
            ))
            hits = sum(1 for t in q_tokens if any(_token_close(t, u) for u in hay))
            if hits:
                scored.append((hits / len(q_tokens), o))
        scored.sort(key=lambda t: t[0], reverse=True)
        return [{**o, "match": round(s, 2)} for s, o in scored[:limit]]

    def recent_payments(self, limit: int = 20) -> list[dict]:
        rows = self.conn.execute(
            """SELECT p.*, c.name AS client_name FROM payments p
               JOIN clients c ON c.id = p.client_id
               ORDER BY p.created_at DESC LIMIT ?""",
            (limit,),
        )
        return [dict(r) for r in rows]

    def orders_due(self, date_from: str, date_to: str) -> list[dict]:
        """Open orders due in [date_from, date_to], both ISO dates inclusive."""
        rows = self.conn.execute(
            """SELECT o.*, c.name AS client_name,
                      o.price - COALESCE((SELECT SUM(amount) FROM payments p WHERE p.order_id = o.id), 0) AS balance
               FROM orders o JOIN clients c ON c.id = o.client_id
               WHERE o.status = 'open' AND o.due_date IS NOT NULL
                 AND date(o.due_date) >= date(?) AND date(o.due_date) <= date(?)
               ORDER BY o.due_date""",
            (date_from, date_to),
        )
        return [dict(r) for r in rows]

    def overdue_orders(self, today: str | None = None) -> list[dict]:
        today = today or dt.datetime.now(IST).date().isoformat()
        rows = self.conn.execute(
            """SELECT o.*, c.name AS client_name,
                      o.price - COALESCE((SELECT SUM(amount) FROM payments p WHERE p.order_id = o.id), 0) AS balance
               FROM orders o JOIN clients c ON c.id = o.client_id
               WHERE o.status = 'open' AND o.due_date IS NOT NULL AND date(o.due_date) < date(?)
               ORDER BY o.due_date""",
            (today,),
        )
        return [dict(r) for r in rows]

    # ---------- money ----------

    def mark_paid(
        self, client_id: int, amount: float, order_id: int | None = None, note: str | None = None
    ) -> dict:
        """Record money received. Without an order_id, the amount is allocated to
        open orders oldest-due-first, so per-order balances stay consistent with
        the client's total due (remainder becomes unallocated credit)."""
        self.get_client(client_id)
        if order_id is not None:
            self.conn.execute(
                "INSERT INTO payments (client_id, order_id, amount, note) VALUES (?, ?, ?, ?)",
                (client_id, order_id, amount, note),
            )
        else:
            remaining = amount
            for o in self.orders_for_client(client_id):
                if remaining <= 0:
                    break
                balance = o["balance"]
                if balance <= 0:
                    continue
                take = min(balance, remaining)
                self.conn.execute(
                    "INSERT INTO payments (client_id, order_id, amount, note) VALUES (?, ?, ?, ?)",
                    (client_id, o["id"], take, note),
                )
                remaining -= take
            if remaining > 0:
                self.conn.execute(
                    "INSERT INTO payments (client_id, order_id, amount, note) VALUES (?, NULL, ?, ?)",
                    (client_id, remaining, note),
                )
        self.conn.commit()
        return {"client": self.get_client(client_id), "paid": amount, "due_left": self.client_due(client_id)}

    def client_due(self, client_id: int) -> float:
        """Total outstanding = sum of order prices minus all payments (advance included)."""
        row = self.conn.execute(
            """SELECT COALESCE((SELECT SUM(price) FROM orders WHERE client_id=? AND status != 'cancelled'), 0)
                    - COALESCE((SELECT SUM(amount) FROM payments WHERE client_id=?), 0) AS due""",
            (client_id, client_id),
        ).fetchone()
        return round(row["due"], 2)

    def all_dues(self) -> list[dict]:
        out = []
        for client in self.list_clients():
            due = self.client_due(client["id"])
            if due > 0:
                out.append({"client_id": client["id"], "name": client["name"], "due": due})
        out.sort(key=lambda d: d["due"], reverse=True)
        return out

    # ---------- memory notes (Suhani's यादें) ----------
    # Personal context across sessions. Hard-capped so token cost can't creep.

    MAX_NOTES = 40
    MAX_NOTES_CHARS = 3000

    def add_note(self, content: str) -> dict:
        content = content.strip()
        if not content:
            raise ValueError("empty note")
        cur = self.conn.execute("INSERT INTO notes (content) VALUES (?)", (content,))
        self.conn.commit()
        pruned = self._prune_notes()
        return {"id": cur.lastrowid, "content": content, "pruned_old_notes": pruned}

    def list_notes(self) -> list[dict]:
        return [dict(r) for r in self.conn.execute("SELECT * FROM notes ORDER BY id")]

    def forget_note(self, note_id: int) -> dict:
        cur = self.conn.execute("DELETE FROM notes WHERE id=?", (note_id,))
        self.conn.commit()
        return {"deleted": cur.rowcount == 1, "note_id": note_id}

    def _prune_notes(self) -> int:
        """Drop oldest notes until under both caps. Returns how many were pruned."""
        pruned = 0
        while True:
            rows = self.conn.execute("SELECT id, content FROM notes ORDER BY id").fetchall()
            total_chars = sum(len(r["content"]) for r in rows)
            if len(rows) <= self.MAX_NOTES and total_chars <= self.MAX_NOTES_CHARS:
                break
            self.conn.execute("DELETE FROM notes WHERE id=?", (rows[0]["id"],))
            pruned += 1
        if pruned:
            self.conn.commit()
        return pruned

    # ---------- reminders (mirror of the Kotlin feature, for harness parity) ----------

    def add_reminder(self, content: str, due_at: str) -> dict:
        content = content.strip()
        if not content:
            raise ValueError("याद की बात खाली नहीं हो सकती")
        dt.datetime.fromisoformat(due_at)  # validates
        dup = self.conn.execute(
            "SELECT id FROM reminders WHERE content=? AND due_at=? AND done=0",
            (content, due_at),
        ).fetchone()
        if dup:
            return {"id": dup["id"], "content": content, "due_at": due_at, "duplicate_ignored": True}
        cur = self.conn.execute(
            "INSERT INTO reminders (content, due_at) VALUES (?, ?)", (content, due_at)
        )
        self.conn.commit()
        return {"id": cur.lastrowid, "content": content, "due_at": due_at}

    def list_reminders(self) -> list[dict]:
        return [dict(r) for r in self.conn.execute(
            "SELECT * FROM reminders WHERE done=0 ORDER BY due_at")]

    def cancel_reminder(self, reminder_id: int) -> dict:
        cur = self.conn.execute(
            "UPDATE reminders SET done=1 WHERE id=? AND done=0", (reminder_id,))
        self.conn.commit()
        return {"cancelled": cur.rowcount == 1, "reminder_id": reminder_id}

    # ---------- briefing ----------

    def daily_briefing(self, today: str | None = None) -> dict:
        today = today or dt.datetime.now(IST).date().isoformat()
        base = dt.date.fromisoformat(today)
        tomorrow = (base + dt.timedelta(days=1)).isoformat()
        return {
            "date": today,
            "due_today": self.orders_due(today, today),
            "due_tomorrow": self.orders_due(tomorrow, tomorrow),
            # Mirror of the Kotlin field: future orders were invisible to
            # "आज क्या काम है?" without it.
            "upcoming": self.orders_due(
                (base + dt.timedelta(days=2)).isoformat(),
                (base + dt.timedelta(days=14)).isoformat(),
            ),
            "overdue": self.overdue_orders(today),
            "money_pending": self.all_dues(),
        }
