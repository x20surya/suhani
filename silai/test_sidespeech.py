"""Side-speech ([चुप]) behavior test against live Sarvam with the SHIPPED
prompt (extracted from Prompts.kt). Checks: customer-directed speech ->
[चुप] + no tool damage; Suhani-directed speech -> normal action, never [चुप].

Run: python -m silai.test_sidespeech
"""

import re
from pathlib import Path

from . import db, prompts
from .ledger import Ledger

_KT = Path(__file__).resolve().parent.parent / "suhani-app/app/src/main/kotlin/app/suhani/core/Prompts.kt"
_src = _KT.read_text(encoding="utf-8")
prompts.STABLE_SYSTEM = re.search(r'val STABLE_SYSTEM = """(.*?)"""', _src, re.S).group(1)
prompts.SARVAM_GUARDS = re.search(r'val SARVAM_GUARDS = """(.*?)"""', _src, re.S).group(1)

from .brain_text import SarvamChatSession  # noqa: E402  (after prompt patch)

SIDE = [  # clearly for someone else in the room -> expect [चुप]
    "आइए आइए, बैठिए ना। चाय पियेंगी आप?",
    "अरे बेटा, ज़रा वो कैंची पकड़ाना मुझे।",
    "नहीं दीदी, सात सौ में नहीं हो पाएगा, कपड़ा ही महँगा है।",
    "हाँ हाँ, आपका ब्लाउज़ बन रहा है, परसों आ जाइए ले जाने।",
]
DIRECT = [  # for Suhani -> expect action/answer, NEVER [चुप]
    "सुहानी, आज क्या देना है?",
    "मीना का कुर्ता लिख दो, चार सौ का, शुक्रवार तक।",
]
HANGUP = [  # closing lines -> expect farewell ending in [बंद]
    "ठीक है अभी के लिए, रखती हूँ।",
    "चलो अभी कुछ नहीं है।",
]


def main() -> None:
    ledger = Ledger(conn=db.connect(":memory:"))
    ledger.add_client("मीना")
    passed = 0
    total = 0
    for text in SIDE:
        s = SarvamChatSession(ledger=ledger)
        reply = s.send(text)
        quiet = "[चुप]" in reply or "[chup]" in reply
        total += 1
        passed += quiet
        print(f"{'OK ' if quiet else 'FAIL'} side   : {text[:34]!r} -> {reply[:60]!r}")
    for text in DIRECT:
        s = SarvamChatSession(ledger=ledger)
        reply = s.send(text)
        ok = "[चुप]" not in reply and reply.strip()
        total += 1
        passed += bool(ok)
        print(f"{'OK ' if ok else 'FAIL'} direct : {text[:34]!r} -> {reply[:60]!r}")
    from silai import tools as tools_mod
    real_dispatch = tools_mod.dispatch
    for text in HANGUP:
        called: list = []
        tools_mod.dispatch = lambda lg, name, args: (called.append(name), real_dispatch(lg, name, args))[1]
        s = SarvamChatSession(ledger=ledger)
        reply = s.send(text)
        tools_mod.dispatch = real_dispatch
        ok = ("end_conversation" in called or "[बंद]" in reply) and "[चुप]" not in reply
        total += 1
        passed += bool(ok)
        print(f"{'OK ' if ok else 'FAIL'} hangup : {text[:30]!r} tools={called} -> {reply[:50]!r}")
    orders = ledger.all_open_orders()
    wrote = any(o["client_name"] != "मीना" or "कुर्ता" not in str(o["garment"]) for o in orders)
    print(f"\n{passed}/{total} checks | phantom writes from side-speech: {'YES - BAD' if wrote and len(orders) > 1 else 'none'}")
    print(f"orders in DB: {[(o['client_name'], o['garment']) for o in orders]}")


main()
