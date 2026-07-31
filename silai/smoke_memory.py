"""E2E proof of cross-session memory (real Claude, throwaway DB).

Session 1: mention something personal -> Suhani should quietly remember it.
Session 2: brand-new chat, same ledger -> she should know it.

Run:  $env:DB_PATH="data/smoke.db"; python -m silai.smoke_memory
"""

import sys

from . import config
from .brain_text import ChatSession
from .ledger import Ledger


def main() -> None:
    if str(config.DB_PATH).endswith("silai.db"):
        print("Refusing to run against the real ledger. Set DB_PATH=data/smoke.db first.")
        sys.exit(2)

    ledger = Ledger()

    print("--- session 1 ---")
    s1 = ChatSession(ledger=ledger)
    line = "अच्छा सुनो, मेरी बेटी की शादी अगले महीने है, बहुत काम रहेगा।"
    print(f"आप : {line}")
    print(f"सुहानी: {s1.send(line)}")

    notes = ledger.list_notes()
    print(f"\nsaved notes: {[n['content'] for n in notes]}")
    if not notes:
        print("SMOKE MEMORY FAILED - nothing was remembered")
        sys.exit(1)

    print("\n--- session 2 (fresh chat, same ledger) ---")
    s2 = ChatSession(ledger=ledger)
    q = "मेरे बारे में क्या याद है तुम्हें?"
    print(f"आप : {q}")
    reply = s2.send(q)
    print(f"सुहानी: {reply}")

    ok = ("शादी" in reply) or ("बेटी" in reply)
    print("\n" + ("SMOKE MEMORY PASSED - she remembers across sessions" if ok
                  else "SMOKE MEMORY FAILED - reply does not reference the saved note"))
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
