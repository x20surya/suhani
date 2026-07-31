"""Ledger + fuzzy matching tests. Pure stdlib - run now, no API keys needed:
    python -m unittest discover tests -v
"""

import datetime as dt
import json
import unittest

from silai import db, tools
from silai.ledger import Ledger


def fresh_ledger() -> Ledger:
    return Ledger(conn=db.connect(":memory:"))


class TestFuzzyClients(unittest.TestCase):
    def setUp(self):
        self.ledger = fresh_ledger()
        self.sunita = self.ledger.add_client("सुनीता", phone="9999900001")
        self.meena = self.ledger.add_client("मीना")
        self.ledger.add_client("राधा वर्मा")

    def test_exact_devanagari(self):
        matches = self.ledger.find_client("सुनीता")
        self.assertEqual(matches[0]["id"], self.sunita["id"])

    def test_duplicate_add_client_returns_existing(self):
        again = self.ledger.add_client("सुनीता")
        self.assertEqual(again["id"], self.sunita["id"])
        self.assertTrue(again.get("duplicate_ignored"))
        self.assertEqual(len([c for c in self.ledger.list_clients() if c["name"] == "सुनीता"]), 1)

    def test_distinct_fuller_name_still_creates(self):
        jain = self.ledger.add_client("सुनीता जैन")
        self.assertNotEqual(jain["id"], self.sunita["id"])
        self.assertNotIn("duplicate_ignored", jain)

    def test_roman_spelling_matches_devanagari(self):
        for spelling in ("Sunita", "sunita", "Suneeta", "sunitha"):
            matches = self.ledger.find_client(spelling)
            self.assertTrue(matches, f"no match for {spelling!r}")
            self.assertEqual(matches[0]["id"], self.sunita["id"], spelling)

    def test_misheard_devanagari_variant(self):
        matches = self.ledger.find_client("सुनिता")  # short i, common STT slip
        self.assertTrue(matches)
        self.assertEqual(matches[0]["id"], self.sunita["id"])

    def test_honorific_stripped(self):
        matches = self.ledger.find_client("मीना आंटी")
        self.assertTrue(matches)
        self.assertEqual(matches[0]["id"], self.meena["id"])

    def test_unknown_name_returns_empty(self):
        self.assertEqual(self.ledger.find_client("कविता"), [])

    def test_alias_learning(self):
        self.ledger.add_alias(self.sunita["id"], "Sunita didi")
        matches = self.ledger.find_client("Sunita didi")
        self.assertEqual(matches[0]["id"], self.sunita["id"])


class TestOrdersAndDues(unittest.TestCase):
    def setUp(self):
        self.ledger = fresh_ledger()
        self.c = self.ledger.add_client("सुनीता")

    def test_order_with_advance_balance(self):
        order = self.ledger.add_order(
            self.c["id"], "ब्लाउज़", price=500, due_date="2026-08-01", advance=200
        )
        self.assertEqual(order["balance"], 300)
        self.assertEqual(self.ledger.client_due(self.c["id"]), 300)

    def test_mark_paid_clears_due(self):
        self.ledger.add_order(self.c["id"], "कुर्ती", price=400)
        self.ledger.mark_paid(self.c["id"], 400)
        self.assertEqual(self.ledger.client_due(self.c["id"]), 0)
        self.assertEqual(self.ledger.all_dues(), [])

    def test_partial_payment(self):
        self.ledger.add_order(self.c["id"], "साड़ी फॉल-पीको", price=150)
        self.ledger.mark_paid(self.c["id"], 100)
        self.assertEqual(self.ledger.client_due(self.c["id"]), 50)

    def test_orders_due_window_and_overdue(self):
        self.ledger.add_order(self.c["id"], "ब्लाउज़", 500, due_date="2026-08-01")
        self.ledger.add_order(self.c["id"], "सूट", 900, due_date="2026-08-05")
        self.ledger.add_order(self.c["id"], "लहंगा", 2000, due_date="2026-07-20")
        due = self.ledger.orders_due("2026-08-01", "2026-08-01")
        self.assertEqual([o["garment"] for o in due], ["ब्लाउज़"])
        overdue = self.ledger.overdue_orders(today="2026-07-28")
        self.assertEqual([o["garment"] for o in overdue], ["लहंगा"])

    def test_delivered_order_leaves_due_window(self):
        o = self.ledger.add_order(self.c["id"], "ब्लाउज़", 500, due_date="2026-08-01")
        self.ledger.update_order(o["id"], status="delivered")
        self.assertEqual(self.ledger.orders_due("2026-08-01", "2026-08-01"), [])
        # Money still owed until paid, even after delivery.
        self.assertEqual(self.ledger.client_due(self.c["id"]), 500)

    def test_unallocated_payment_flows_to_orders_fifo(self):
        o1 = self.ledger.add_order(self.c["id"], "ब्लाउज़", 300, due_date="2026-08-01")
        o2 = self.ledger.add_order(self.c["id"], "सूट", 200, due_date="2026-08-05")
        self.ledger.mark_paid(self.c["id"], 400)  # no order_id: allocate oldest-due first
        self.assertEqual(self.ledger.get_order(o1["id"])["balance"], 0)
        self.assertEqual(self.ledger.get_order(o2["id"])["balance"], 100)
        self.assertEqual(self.ledger.client_due(self.c["id"]), 100)

    def test_overpayment_becomes_credit(self):
        self.ledger.add_order(self.c["id"], "ब्लाउज़", 300)
        self.ledger.mark_paid(self.c["id"], 500)
        self.assertEqual(self.ledger.client_due(self.c["id"]), -200)  # credit on account

    def test_cancelled_order_leaves_everything(self):
        o = self.ledger.add_order(self.c["id"], "ब्लाउज़", 500, due_date="2026-08-01")
        self.ledger.update_order(o["id"], status="cancelled")
        self.assertEqual(self.ledger.all_open_orders(), [])
        self.assertEqual(self.ledger.orders_due("2026-08-01", "2026-08-01"), [])
        self.assertEqual(self.ledger.client_due(self.c["id"]), 0)

    def test_duplicate_order_ignored(self):
        o1 = self.ledger.add_order(self.c["id"], "ब्लाउज़", 500, due_date="2026-08-01")
        o2 = self.ledger.add_order(self.c["id"], "ब्लाउज़", 500, due_date="2026-08-01")
        self.assertTrue(o2.get("duplicate_ignored"))
        self.assertEqual(o1["id"], o2["id"])
        self.assertEqual(len(self.ledger.all_open_orders()), 1)
        # A genuinely different order is not blocked.
        o3 = self.ledger.add_order(self.c["id"], "ब्लाउज़", 600, due_date="2026-08-01")
        self.assertFalse(o3.get("duplicate_ignored"))
        self.assertEqual(len(self.ledger.all_open_orders()), 2)

    def test_briefing_shape(self):
        self.ledger.add_order(self.c["id"], "ब्लाउज़", 500, due_date="2026-07-28")
        b = self.ledger.daily_briefing(today="2026-07-28")
        self.assertEqual(len(b["due_today"]), 1)
        self.assertEqual(b["due_today"][0]["client_name"], "सुनीता")
        self.assertEqual(b["money_pending"][0]["due"], 500)


class TestRealisticNamePatterns(unittest.TestCase):
    """Mom's actual speech: full names, locality tags, or bare first names."""

    def setUp(self):
        self.ledger = fresh_ledger()
        self.vasvani = self.ledger.add_client("सुनीता वासवानी")
        self.jain = self.ledger.add_client("सुनीता जैन")

    def test_full_name_matches_exactly_one(self):
        for q, expect in [("सुनीता जैन", self.jain), ("सुनीता वासवानी", self.vasvani),
                          ("Sunita Jain", self.jain)]:
            m = self.ledger.find_client(q)
            self.assertEqual(m[0]["id"], expect["id"], q)
            top = [x for x in m if x["match_score"] >= 0.9]
            self.assertEqual(len(top), 1, f"{q}: {[(x['name'], x['match_score']) for x in m]}")

    def test_bare_first_name_returns_both(self):
        m = self.ledger.find_client("सुनीता")
        self.assertEqual(len(m), 2, str([(x["name"], x["match_score"]) for x in m]))

    def test_locality_tag_still_matches(self):
        m = self.ledger.find_client("सुनीता वासवानी ब्रेंटवुड वाली")
        self.assertTrue(m, "locality-tagged full name found nobody")
        self.assertEqual(m[0]["id"], self.vasvani["id"])

    def test_surname_with_filler(self):
        m = self.ledger.find_client("जैन वाली सुनीता")
        self.assertTrue(m)
        self.assertEqual(m[0]["id"], self.jain["id"])

    def test_unrelated_name_no_match(self):
        self.assertEqual(self.ledger.find_client("मीना"), [])

    def test_learned_locality_alias(self):
        self.ledger.add_alias(self.vasvani["id"], "ब्रेंटवुड वाली सुनीता")
        m = self.ledger.find_client("ब्रेंटवुड वाली")
        self.assertTrue(m)
        self.assertEqual(m[0]["id"], self.vasvani["id"])


class TestSearchOrders(unittest.TestCase):
    """Attribute recall across the whole order lifecycle - open, delivered, cancelled."""

    def setUp(self):
        self.ledger = fresh_ledger()
        self.sunita = self.ledger.add_client("सुनीता")
        self.shruti = self.ledger.add_client("श्रुति")
        self.geeta = self.ledger.add_client("गीता")
        self.red = self.ledger.add_order(self.sunita["id"], "ब्लाउज़", 400, notes="red colour")
        self.maroon = self.ledger.add_order(self.shruti["id"], "ब्लाउज़", 400,
                                            notes="maroon colour, पफ स्लीव")
        self.lehenga = self.ledger.add_order(self.geeta["id"], "लहंगा", 2000,
                                             notes="gold गोटा पत्ती")

    def test_color_finds_open_order(self):
        hits = self.ledger.search_orders("maroon ब्लाउज़")
        self.assertEqual(hits[0]["client_name"], "श्रुति")

    def test_finds_delivered_order(self):
        self.ledger.update_order(self.maroon["id"], status="delivered")
        hits = self.ledger.search_orders("maroon")
        self.assertEqual(hits[0]["client_name"], "श्रुति")
        self.assertEqual(hits[0]["status"], "delivered")

    def test_finds_cancelled_order_with_status(self):
        self.ledger.update_order(self.lehenga["id"], status="cancelled")
        hits = self.ledger.search_orders("गोटा पत्ती")
        self.assertEqual(hits[0]["client_name"], "गीता")
        self.assertEqual(hits[0]["status"], "cancelled")

    def test_pattern_query(self):
        hits = self.ledger.search_orders("पफ स्लीव वाला")
        self.assertEqual(hits[0]["client_name"], "श्रुति")

    def test_client_name_query(self):
        hits = self.ledger.search_orders("गीता लहंगा")
        self.assertEqual(hits[0]["client_name"], "गीता")

    def test_no_match_returns_empty(self):
        self.assertEqual(self.ledger.search_orders("purple शेरवानी"), [])
        self.assertEqual(self.ledger.search_orders(""), [])

    def test_best_match_ranked_first(self):
        hits = self.ledger.search_orders("red ब्लाउज़")
        self.assertEqual(hits[0]["client_name"], "सुनीता")


class TestResolveDueDate(unittest.TestCase):
    """Deterministic date resolution for the Sarvam brain (Wed 2026-07-29 as 'today')."""

    TODAY = dt.date(2026, 7, 29)  # a Wednesday

    def resolve(self, **args):
        return json.loads(tools.resolve_due_date(args, today=self.TODAY))

    def test_next_friday(self):
        self.assertEqual(self.resolve(weekday="शुक्रवार")["date"], "2026-07-31")

    def test_same_weekday_means_next_week(self):
        self.assertEqual(self.resolve(weekday="बुधवार")["date"], "2026-08-05")

    def test_days_from_today(self):
        self.assertEqual(self.resolve(days_from_today=2)["date"], "2026-07-31")
        self.assertEqual(self.resolve(days_from_today=14)["date"], "2026-08-12")

    def test_day_of_month_future(self):
        self.assertEqual(self.resolve(day_of_month=15, month=8)["date"], "2026-08-15")

    def test_day_of_month_past_rolls_to_next_year(self):
        self.assertEqual(self.resolve(day_of_month=15, month=3)["date"], "2027-03-15")

    def test_no_input_is_error(self):
        self.assertIn("error", self.resolve())


class TestMemoryNotes(unittest.TestCase):
    def setUp(self):
        self.ledger = fresh_ledger()

    def test_add_and_list(self):
        n = self.ledger.add_note("बेटी की शादी अगले महीने है")
        notes = self.ledger.list_notes()
        self.assertEqual(len(notes), 1)
        self.assertEqual(notes[0]["id"], n["id"])
        self.assertIn("शादी", notes[0]["content"])

    def test_forget(self):
        n = self.ledger.add_note("कुछ")
        self.assertTrue(self.ledger.forget_note(n["id"])["deleted"])
        self.assertEqual(self.ledger.list_notes(), [])
        self.assertFalse(self.ledger.forget_note(999)["deleted"])

    def test_count_cap_prunes_oldest(self):
        for i in range(self.ledger.MAX_NOTES + 5):
            self.ledger.add_note(f"बात {i}")
        notes = self.ledger.list_notes()
        self.assertEqual(len(notes), self.ledger.MAX_NOTES)
        self.assertEqual(notes[0]["content"], "बात 5")  # oldest five pruned

    def test_char_cap_prunes(self):
        big = "क" * 1000
        for _ in range(5):
            self.ledger.add_note(big)
        total = sum(len(n["content"]) for n in self.ledger.list_notes())
        self.assertLessEqual(total, self.ledger.MAX_NOTES_CHARS)

    def test_empty_note_rejected(self):
        with self.assertRaises(ValueError):
            self.ledger.add_note("   ")

    def test_dispatch_remember_roundtrip(self):
        import json

        from silai import tools

        out = json.loads(tools.dispatch(self.ledger, "remember", {"content": "मालकिन को चाय पसंद है"}))
        self.assertIn("id", out)
        gone = json.loads(tools.dispatch(self.ledger, "forget_note", {"note_id": out["id"]}))
        self.assertTrue(gone["deleted"])


class TestDispatchJSON(unittest.TestCase):
    def test_dispatch_roundtrip(self):
        import json

        from silai import tools

        ledger = fresh_ledger()
        out = json.loads(tools.dispatch(ledger, "add_client", {"name": "मीना"}))
        self.assertEqual(out["name"], "मीना")
        found = json.loads(tools.dispatch(ledger, "find_client", {"name": "Meena"}))
        self.assertEqual(found["matches"][0]["id"], out["id"])
        err = json.loads(tools.dispatch(ledger, "add_order", {"client_id": 999, "garment": "x", "price": 1}))
        self.assertIn("error", err)


if __name__ == "__main__":
    unittest.main()
