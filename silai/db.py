"""SQLite storage. One user, WAL mode, zero ops."""

import json
import sqlite3
from pathlib import Path

from . import config

_SCHEMA = """
CREATE TABLE IF NOT EXISTS clients (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    name_norm TEXT NOT NULL,
    aliases TEXT NOT NULL DEFAULT '[]',
    phone TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE TABLE IF NOT EXISTS orders (
    id INTEGER PRIMARY KEY,
    client_id INTEGER NOT NULL REFERENCES clients(id),
    garment TEXT NOT NULL,
    notes TEXT,
    price REAL NOT NULL DEFAULT 0,
    due_date TEXT,
    status TEXT NOT NULL DEFAULT 'open',          -- open | done | delivered
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    delivered_at TEXT
);
CREATE TABLE IF NOT EXISTS payments (
    id INTEGER PRIMARY KEY,
    client_id INTEGER NOT NULL REFERENCES clients(id),
    order_id INTEGER REFERENCES orders(id),
    amount REAL NOT NULL,
    note TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE TABLE IF NOT EXISTS notes (
    id INTEGER PRIMARY KEY,
    content TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE TABLE IF NOT EXISTS push_subs (
    id INTEGER PRIMARY KEY,
    subscription TEXT NOT NULL UNIQUE,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_orders_client ON orders(client_id);
CREATE INDEX IF NOT EXISTS idx_orders_due ON orders(due_date);
CREATE INDEX IF NOT EXISTS idx_payments_client ON payments(client_id);
"""


def connect(db_path: Path | str | None = None) -> sqlite3.Connection:
    path = Path(db_path) if db_path else config.DB_PATH
    path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(path, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA foreign_keys=ON")
    conn.executescript(_SCHEMA)
    return conn


def backup(conn: sqlite3.Connection, dest_dir: Path, keep: int = 14) -> Path:
    """Consistent online backup (WAL-safe) named by date; prunes to `keep` files."""
    dest_dir.mkdir(parents=True, exist_ok=True)
    import datetime as dt

    dest = dest_dir / f"silai-{dt.date.today().isoformat()}.db"
    target = sqlite3.connect(dest)
    try:
        conn.backup(target)
    finally:
        target.close()
    backups = sorted(dest_dir.glob("silai-*.db"))
    for old in backups[:-keep]:
        old.unlink(missing_ok=True)
    return dest


def row_to_dict(row: sqlite3.Row) -> dict:
    d = dict(row)
    if "aliases" in d and isinstance(d["aliases"], str):
        d["aliases"] = json.loads(d["aliases"])
    return d
