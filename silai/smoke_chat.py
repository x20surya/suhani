"""Scripted end-to-end smoke test of the text brain (real Claude, real tools).

Run against a throwaway DB:
    DB_PATH=data/smoke.db python -m silai.smoke_chat        (bash)
    $env:DB_PATH="data/smoke.db"; python -m silai.smoke_chat (powershell)

Drives the canonical flow: new client -> follow-ups -> confirmed order ->
briefing -> dues, then verifies the ledger actually contains what was said.
"""

import sys

from . import config
from .brain_text import ChatSession
from .ledger import Ledger

SCRIPT = [
    "सुनीता का ब्लाउज़ लिख लो, शुक्रवार तक देना है, पाँच सौ रुपये। दो सौ एडवांस दिए हैं।",
    "हाँ, नई ग्राहक है।",
    "हाँ, लिख दो।",
    "आज क्या देना है?",
    "किसका कितना पैसा बाकी है?",
]


def main() -> None:
    if str(config.DB_PATH).endswith("silai.db"):
        print("Refusing to smoke-test against the real ledger. Set DB_PATH=data/smoke.db first.")
        sys.exit(2)

    session = ChatSession()
    for line in SCRIPT:
        print(f"\nआप : {line}")
        reply = session.send(line)
        print(f"सुहानी: {reply}")

    print("\n--- ledger verification ---")
    ledger = Ledger()
    clients = ledger.list_clients()
    orders = ledger.all_open_orders()
    ok = True

    if len(clients) == 1 and "सुन" in clients[0]["name"]:
        print(f"OK  client created: {clients[0]['name']}")
    else:
        ok = False
        print(f"FAIL clients: {[c['name'] for c in clients]}")

    if len(orders) == 1 and orders[0]["price"] == 500 and orders[0]["balance"] == 300:
        print(f"OK  order: {orders[0]['garment']} price=500 advance=200 balance=300 due={orders[0]['due_date']}")
    else:
        ok = False
        print(f"FAIL orders: {orders}")

    turns = len(session.messages)
    print(f"\n{'SMOKE TEST PASSED' if ok else 'SMOKE TEST FAILED'} ({turns} messages in history)")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
