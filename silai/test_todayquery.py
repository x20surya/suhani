"""Reproduce "do I have work today -> random replies" against the SHIPPED
prompt. Seeds a ledger with one due-today order + one future order, asks the
question in many phrasings, and checks (a) a briefing tool was called and
(b) the reply mentions the due-today client/garment.

Run: python -m silai.test_todayquery
"""

import datetime as dt
import re
from pathlib import Path
from zoneinfo import ZoneInfo

from . import db, prompts
from .ledger import Ledger

_KT = Path(__file__).resolve().parent.parent / "suhani-app/app/src/main/kotlin/app/suhani/core/Prompts.kt"
_src = _KT.read_text(encoding="utf-8")
prompts.STABLE_SYSTEM = re.search(r'val STABLE_SYSTEM = """(.*?)"""', _src, re.S).group(1)
prompts.SARVAM_GUARDS = re.search(r'val SARVAM_GUARDS = """(.*?)"""', _src, re.S).group(1)

from silai import tools as tools_mod  # noqa: E402
from silai.brain_text import SarvamChatSession  # noqa: E402

TODAY = dt.datetime.now(ZoneInfo("Asia/Kolkata")).date().isoformat()
NEXT_WEEK = (dt.datetime.now(ZoneInfo("Asia/Kolkata")).date() + dt.timedelta(days=7)).isoformat()

QUERIES = [
    "आज कुछ काम है क्या?",
    "do i have work today?",
    "aaj kuch dena hai kya?",
    "आज का क्या schedule है?",
    "koi order pending hai aaj ka?",
    "आज क्या देना है?",
]

BRIEFING_TOOLS = {"daily_briefing", "orders_due", "list_open_orders", "check_dues"}


def main() -> None:
    real_dispatch = tools_mod.dispatch
    passed = 0
    for q in QUERIES:
        ledger = Ledger(conn=db.connect(":memory:"))
        c = ledger.add_client("सुनीता")
        ledger.add_order(c["id"], "ब्लाउज़", 500, due_date=TODAY)
        c2 = ledger.add_client("मीना")
        ledger.add_order(c2["id"], "कुर्ती", 400, due_date=NEXT_WEEK)

        called: list = []
        tools_mod.dispatch = lambda lg, name, args: (called.append(name), real_dispatch(lg, name, args))[1]
        s = SarvamChatSession(ledger=ledger)
        reply = s.send(q)
        tools_mod.dispatch = real_dispatch

        used_tool = any(t in BRIEFING_TOOLS for t in called)
        mentioned = ("सुनीता" in reply or "ब्लाउज़" in reply or "blouse" in reply.lower())
        ok = used_tool and mentioned
        passed += ok
        print(f"{'OK ' if ok else 'FAIL'} {q!r}\n     tools={called} reply={reply[:90]!r}")
    print(f"\n{passed}/{len(QUERIES)}")


main()
