"""Seed demo data for testing and mom's first demo. Run: python -m silai.seed_demo
Refuses to touch a non-empty ledger unless --force is passed."""

import datetime as dt
import sys
from zoneinfo import ZoneInfo

from .ledger import Ledger

IST = ZoneInfo("Asia/Kolkata")


def main() -> None:
    ledger = Ledger()
    if ledger.list_clients() and "--force" not in sys.argv:
        print("Ledger already has clients - not seeding. Use --force to seed anyway.")
        sys.exit(1)

    today = dt.datetime.now(IST).date()
    iso = lambda d: d.isoformat()  # noqa: E731

    sunita = ledger.add_client("सुनीता", phone="9999900001")
    meena = ledger.add_client("मीना")
    radha = ledger.add_client("राधा वर्मा")

    # Due today - shows up in briefing + push.
    ledger.add_order(sunita["id"], "ब्लाउज़", price=500, due_date=iso(today), advance=200,
                     notes="गोल्डन बॉर्डर, पफ स्लीव")
    # Due tomorrow.
    ledger.add_order(meena["id"], "साड़ी फॉल-पीको", price=150, due_date=iso(today + dt.timedelta(days=1)))
    # Overdue - shows the 'late' path.
    ledger.add_order(radha["id"], "कुर्ती", price=400, due_date=iso(today - dt.timedelta(days=2)))
    # Delivered but money pending - shows dues without a due date.
    old = ledger.add_order(sunita["id"], "पेटीकोट", price=200, due_date=iso(today - dt.timedelta(days=7)))
    ledger.update_order(old["id"], status="delivered")

    b = ledger.daily_briefing()
    print(f"Seeded: {len(ledger.list_clients())} clients.")
    print(f"Due today: {[o['garment'] for o in b['due_today']]}")
    print(f"Overdue:   {[o['garment'] for o in b['overdue']]}")
    print(f"Dues:      {[(d['name'], d['due']) for d in b['money_pending']]}")


if __name__ == "__main__":
    main()
