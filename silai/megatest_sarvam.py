"""Mega-test: generated scenario matrix against Sarvam-105B (fixed/low config).

4 languages (hindi, hinglish, english, marwari) x 5 speaking styles x 11
transaction categories, with varied names, amounts (incl. Hindi number words),
and date forms. Expected outcomes computed deterministically and verified
against the real ledger code. Reuses the bakeoff harness; Claude path untouched.

Run one language batch:  python -m silai.megatest_sarvam <hindi|hinglish|english|marwari>
Results: data/bakeoff/mega_<lang>.json
"""

import datetime as dt
import json
import sys

from . import config, db
from .bakeoff_sarvam import IST, TODAY, SarvamChat, iso, next_weekday
from .ledger import Ledger

from openai import OpenAI

FRIDAY = next_weekday(TODAY, 4)
MONDAY = next_weekday(TODAY, 0)
TUESDAY = next_weekday(TODAY, 1)

NAMES = ["सुनीता", "मीना", "राधा", "कविता", "पूजा", "अंजू", "सीमा", "गीता", "लता", "आशा"]
GARMENTS = ["ब्लाउज़", "कुर्ती", "साड़ी फॉल", "पेटीकोट", "सूट", "लहंगा", "चोली"]

# number words per language: (numeric, {lang: phrase})
PRICES = [
    (500, {"hindi": "पाँच सौ", "hinglish": "five hundred", "english": "five hundred rupees", "marwari": "पांच सौ"}),
    (400, {"hindi": "चार सौ", "hinglish": "four sau", "english": "four hundred rupees", "marwari": "चार सौ"}),
    (150, {"hindi": "डेढ़ सौ", "hinglish": "150", "english": "one fifty", "marwari": "डेढ़ सौ"}),
    (1200, {"hindi": "बारह सौ", "hinglish": "twelve hundred", "english": "twelve hundred rupees", "marwari": "बारा सौ"}),
]
HARD_NUMBERS = [
    (350, {"hindi": "साढ़े तीन सौ", "hinglish": "साढ़े तीन सौ", "english": "three hundred fifty", "marwari": "साढ़े तीन सौ"}),
    (1500, {"hindi": "डेढ़ हज़ार", "hinglish": "डेढ़ हज़ार", "english": "fifteen hundred", "marwari": "डेढ़ हजार"}),
    (250, {"hindi": "ढाई सौ", "hinglish": "ढाई सौ", "english": "two fifty", "marwari": "ढाई सौ"}),
    (125, {"hindi": "सवा सौ", "hinglish": "सवा सौ", "english": "one twenty five", "marwari": "सवा सौ"}),
]
DATES = [
    (TODAY + dt.timedelta(days=1), {"hindi": "कल", "hinglish": "kal tak", "english": "by tomorrow", "marwari": "काल"}),
    (TODAY + dt.timedelta(days=2), {"hindi": "परसों", "hinglish": "परसों तक", "english": "day after tomorrow", "marwari": "परसूं"}),
    (FRIDAY, {"hindi": "शुक्रवार तक", "hinglish": "Friday तक", "english": "by Friday", "marwari": "शुक्रवार तांई"}),
    (TODAY + dt.timedelta(days=14), {"hindi": "दो हफ़्ते बाद", "hinglish": "two weeks में", "english": "in two weeks", "marwari": "दो हफ्तां बाद"}),
    (dt.date(2026, 8, 15), {"hindi": "पंद्रह अगस्त को", "hinglish": "15 August को", "english": "on August 15", "marwari": "पंद्रह अगस्त नै"}),
]

YES = {"hindi": "हाँ, लिख दो।", "hinglish": "haan, likh do.", "english": "Yes, write it down.", "marwari": "हाँ सा, लिख दो।"}
NEW_CLIENT_YES = {"hindi": "हाँ, नई ग्राहक है। एडवांस नहीं।", "hinglish": "haan nayi client hai, no advance.",
                  "english": "Yes, she is a new client. No advance.", "marwari": "हाँ सा, नई ग्राहक है। एडवांस कोनी।"}

# order phrasing styles: template({n}=name, {g}=garment, {p}=price phrase, {d}=date phrase)
ORDER_STYLES = {
    "hindi": [
        "{n} का {g} लिख लो, {d} देना है, {p} का।",
        "ज़रा {n} का एक {g} लिखना, {p} का, {d}।",
        "अच्छा सुनो, {n} आई थी - {g} बनवाना है उसे, {d} चाहिए, {p} लूँगी।",
        "{n} - {g} - {p} - {d}। लिख लो।",
        "वो क्या है ना, {n} का {g} है, हाँ, {p} का बोला है, {d} देना है।",
    ],
    "hinglish": [
        "{n} का {g} likh lo, {d} dena hai, {p} का।",
        "Note kar lo - {n} ka {g}, {p}, {d}.",
        "{n} aayi thi, uska {g} banana hai, {d}, {p} ka.",
        "{n} - {g} - {p} - {d}. Likh lena.",
        "Ek order add karo: {n} ka {g}, {p}, {d}.",
    ],
    "english": [
        "Add an order for {n} - one {g}, {p}, due {d}.",
        "Please note down a {g} for {n}, {p}, {d}.",
        "{n} came by, she wants a {g} {d}, {p}.",
        "{n} - {g} - {p} - {d}. Write it.",
        "New order: {g} for {n}, price {p}, deadline {d}.",
    ],
    "marwari": [
        "म्हारी {n} रो {g} लिख लियो सा, {d} देणो है, {p} रो।",
        "{n} रो एक {g} लिखो सा, {p} रो, {d}।",
        "अरे सुणो सा, {n} आई ही - {g} बणाणो है, {d} चाइजै, {p} लेस्यां।",
        "{n} - {g} - {p} - {d}। लिख लो सा।",
        "एक काम लिखो सा: {n} रो {g}, {p} रो, {d}।",
    ],
}

PAY_PARTIAL = {
    "hindi": "{n} ने {p} दे दिए, बाकी बाद में देगी।",
    "hinglish": "{n} ne {p} de diye, baaki baad mein.",
    "english": "{n} paid {p}, she will pay the rest later.",
    "marwari": "{n} {p} दे गी सा, बाकी पछै देसी।",
}
PAY_FULL = {
    "hindi": "{n} के पूरे पैसे आ गए, सारा हिसाब चुकता।",
    "hinglish": "{n} ke pure paise aa gaye, full payment.",
    "english": "{n} paid in full, everything is settled.",
    "marwari": "{n} रा पूरा पइसा आयग्या सा, हिसाब चुकता।",
}
DELIVERED = {
    "hindi": "{n} का {g} दे दिया आज उसे।",
    "hinglish": "{n} ka {g} deliver ho gaya aaj.",
    "english": "I delivered {n}'s {g} today.",
    "marwari": "{n} रो {g} दे दियो सा आज।",
}
RESCHEDULE = {
    "hindi": "{n} का {g} अब शुक्रवार नहीं, सोमवार को देना है।",
    "hinglish": "{n} ka {g} ab Friday nahi, Monday ko dena hai.",
    "english": "{n}'s {g} is now due Monday, not Friday.",
    "marwari": "{n} रो {g} अबै शुक्रवार कोनी, सोमवार नै देणो है सा।",
}
Q_BALANCE = {
    "hindi": "{n} का कितना बाकी है?",
    "hinglish": "{n} ka balance kitna hai?",
    "english": "How much does {n} owe?",
    "marwari": "{n} रो कित्तो बाकी है सा?",
}
Q_TOTAL = {
    "hindi": "कुल कितने पैसे बाकी हैं सबके?",
    "hinglish": "Total kitna paisa pending hai?",
    "english": "How much money is pending in total?",
    "marwari": "कुल कित्ता पइसा बाकी है सा?",
}
Q_TODAY = {
    "hindi": "आज क्या देना है?",
    "hinglish": "Aaj kya dena hai?",
    "english": "What is due today?",
    "marwari": "आज कांई देणो है सा?",
}
CORRECTION = {
    "hindi": "नहीं रुको, {p} का है।",
    "hinglish": "nahi ruko, {p} ka hai.",
    "english": "No wait, it is {p}.",
    "marwari": "ना सा, {p} रो है।",
}
MULTI = {
    "hindi": "{n} के दो काम लिखो - {g1} {p1} का और {g2} {p2} का, दोनों शुक्रवार तक। एडवांस नहीं।",
    "hinglish": "{n} ke do kaam - {g1} {p1} ka, {g2} {p2} ka, dono Friday tak. No advance.",
    "english": "Two orders for {n}: a {g1} for {p1} and a {g2} for {p2}, both by Friday. No advance.",
    "marwari": "{n} रा दो काम लिखो सा - {g1} {p1} रो अर {g2} {p2} रो, दोन्यूं शुक्रवार तांई। एडवांस कोनी।",
}
GARBAGE = {
    "hindi": ["वो... क्या बोलते हैं... हाँ वो...", "सुन... {n}... नहीं रहने दो..."],
    "hinglish": ["woh... kya bolte... umm...", "sun... {n}... nahi chhodo..."],
    "english": ["that... what do you call it... umm...", "listen... {n}... no never mind..."],
    "marwari": ["वो... कांई कैवै... हां वो...", "सुणो... {n}... ना रैण दो..."],
}

NUM_WORDS = {125: "सवा सौ", 150: "डेढ़ सौ", 200: "दो सौ", 250: "ढाई सौ", 300: "तीन सौ",
             350: "साढ़े तीन सौ", 400: "चार सौ", 500: "पाँच सौ", 700: "सात सौ",
             900: "नौ सौ", 1200: "बारह सौ", 1500: "डेढ़ हज़ार"}


def amount_in(reply: str, amount: int) -> bool:
    return str(amount) in reply or NUM_WORDS.get(amount, "") in reply


class Conv:
    """One generated conversation with adaptive turn-feeding and checks."""

    def __init__(self, name, seed, opening, followups, done, checks):
        self.name, self.seed, self.opening = name, seed, opening
        self.followups, self.done, self.checks = followups, done, checks


def gen_conversations(lang: str) -> list[Conv]:
    convs: list[Conv] = []
    ni = 0

    def next_name():
        nonlocal ni
        ni += 1
        return NAMES[ni % len(NAMES)]

    def order_checks(name, garment, price, due):
        def checks(lg, replies):
            orders = lg.all_open_orders()
            o = orders[0] if orders else None
            return [
                (f"order written once ({garment})", len(orders) == 1, str(len(orders))),
                (f"price {price}", bool(o) and o["price"] == price, str(o and o["price"])),
                (f"due {due}", bool(o) and o["due_date"] == due, str(o and o["due_date"])),
            ]
        return checks

    def order_done(lg, _):
        return len(lg.all_open_orders()) >= 1

    # 1) new-client orders: 5 styles x rotating price/date  (15 checks)
    for si, style in enumerate(ORDER_STYLES[lang]):
        n = next_name()
        g = GARMENTS[si % len(GARMENTS)]
        price, pw = PRICES[si % len(PRICES)]
        due_d, dw = DATES[si % len(DATES)]
        opening = style.format(n=n, g=g, p=pw[lang], d=dw[lang])
        convs.append(Conv(f"order_style{si+1}", None, opening,
                          [NEW_CLIENT_YES[lang], YES[lang], YES[lang]],
                          order_done, order_checks(n, g, price, iso(due_d))))

    # 2) existing-client orders: 3 convs (9 checks)
    for si in range(3):
        n = next_name()
        g = GARMENTS[(si + 2) % len(GARMENTS)]
        price, pw = PRICES[(si + 1) % len(PRICES)]
        due_d, dw = DATES[(si + 2) % len(DATES)]

        def seed(lg, n=n):
            lg.add_client(n)

        def checks(lg, replies, n=n, g=g, price=price, due=iso(due_d)):
            orders = lg.all_open_orders()
            o = orders[0] if orders else None
            return [
                ("no duplicate client", len(lg.list_clients()) == 1, str(len(lg.list_clients()))),
                (f"price {price}", bool(o) and o["price"] == price, str(o and o["price"])),
                (f"due {due}", bool(o) and o["due_date"] == due, str(o and o["due_date"])),
            ]

        opening = ORDER_STYLES[lang][si].format(n=n, g=g, p=pw[lang], d=dw[lang])
        convs.append(Conv(f"order_existing{si+1}", seed, opening,
                          [YES[lang], YES[lang], YES[lang]], order_done, checks))

    # 3) date-form torture: all 5 date forms, direct style (15 checks)
    for di, (due_d, dw) in enumerate(DATES):
        n = next_name()
        g = GARMENTS[di % len(GARMENTS)]
        price, pw = PRICES[di % len(PRICES)]
        opening = ORDER_STYLES[lang][0].format(n=n, g=g, p=pw[lang], d=dw[lang])
        convs.append(Conv(f"date_form{di+1}", None, opening,
                          [NEW_CLIENT_YES[lang], YES[lang], YES[lang]],
                          order_done, order_checks(n, g, price, iso(due_d))))

    # 4) hard number words: 4 convs (12 checks)
    for hi, (price, pw) in enumerate(HARD_NUMBERS):
        n = next_name()
        g = GARMENTS[hi % len(GARMENTS)]
        due_d, dw = DATES[hi % len(DATES)]
        opening = ORDER_STYLES[lang][0].format(n=n, g=g, p=pw[lang], d=dw[lang])
        convs.append(Conv(f"hard_number{hi+1}", None, opening,
                          [NEW_CLIENT_YES[lang], YES[lang], YES[lang]],
                          order_done, order_checks(n, g, price, iso(due_d))))

    # 5) payments: partial, full, partial-then-query (6 checks)
    n1 = next_name()

    def seed_pay(lg, n=n1):
        c = lg.add_client(n)
        lg.add_order(c["id"], "कुर्ती", 500, due_date=iso(TODAY + dt.timedelta(days=2)))

    convs.append(Conv("pay_partial", seed_pay,
                      PAY_PARTIAL[lang].format(n=n1, p=PRICES[0][1][lang] if False else NUM_WORDS[200] if lang in ("hindi", "marwari") else "two hundred"),
                      [YES[lang]],
                      lambda lg, _: lg.client_due(1) == 300,
                      lambda lg, replies: [("partial payment -> due 300", lg.client_due(1) == 300, f"due={lg.client_due(1)}")]))

    n2 = next_name()

    def seed_pay2(lg, n=n2):
        c = lg.add_client(n)
        lg.add_order(c["id"], "सूट", 900, due_date=iso(TODAY + dt.timedelta(days=3)))

    convs.append(Conv("pay_full", seed_pay2, PAY_FULL[lang].format(n=n2),
                      [YES[lang]],
                      lambda lg, _: lg.client_due(1) == 0,
                      lambda lg, replies: [("full payment -> due 0", lg.client_due(1) == 0, f"due={lg.client_due(1)}")]))

    n3 = next_name()

    def seed_pay3(lg, n=n3):
        c = lg.add_client(n)
        lg.add_order(c["id"], "ब्लाउज़", 500, advance=200)

    convs.append(Conv("balance_query", seed_pay3, Q_BALANCE[lang].format(n=n3), [],
                      lambda lg, replies: bool(replies),
                      lambda lg, replies: [("states 300", amount_in(replies[-1], 300), replies[-1][:90]),
                                           ("no accidental writes", lg.client_due(1) == 300, str(lg.client_due(1)))]))

    # 6) delivery: 2 convs (4 checks)
    for di in range(2):
        n = next_name()
        g = GARMENTS[di]

        def seed_del(lg, n=n, g=g):
            c = lg.add_client(n)
            lg.add_order(c["id"], g, 400, due_date=iso(TODAY))

        def checks_del(lg, replies):
            row = dict(lg.conn.execute("SELECT * FROM orders").fetchone())
            return [("status delivered", row["status"] == "delivered", row["status"]),
                    ("money still due (no phantom payment)", lg.client_due(1) == 400, str(lg.client_due(1)))]

        convs.append(Conv(f"delivery{di+1}", seed_del, DELIVERED[lang].format(n=n, g=g),
                          [YES[lang]],
                          lambda lg, _: dict(lg.conn.execute("SELECT * FROM orders").fetchone())["status"] == "delivered",
                          checks_del))

    # 7) reschedule: 2 convs (2 checks)
    for ri in range(2):
        n = next_name()
        g = GARMENTS[ri + 3]

        def seed_re(lg, n=n, g=g):
            c = lg.add_client(n)
            lg.add_order(c["id"], g, 600, due_date=iso(FRIDAY))

        convs.append(Conv(f"reschedule{ri+1}", seed_re, RESCHEDULE[lang].format(n=n, g=g),
                          [YES[lang]],
                          lambda lg, _: lg.all_open_orders()[0]["due_date"] == iso(MONDAY),
                          lambda lg, replies: [("due moved to Monday", lg.all_open_orders()[0]["due_date"] == iso(MONDAY),
                                                lg.all_open_orders()[0]["due_date"])]))

    # 8) aggregate queries: total + today (3 checks)
    def seed_q(lg):
        a = lg.add_client(next_name())
        b = lg.add_client(next_name())
        lg.add_order(a["id"], "ब्लाउज़", 500, due_date=iso(TODAY), advance=200)
        lg.add_order(b["id"], "कुर्ती", 400, due_date=iso(TODAY + dt.timedelta(days=5)))

    convs.append(Conv("total_query", seed_q, Q_TOTAL[lang], [],
                      lambda lg, replies: bool(replies),
                      lambda lg, replies: [("total 700 stated", amount_in(replies[-1], 700), replies[-1][:100])]))
    convs.append(Conv("today_query", seed_q, Q_TODAY[lang], [],
                      lambda lg, replies: bool(replies),
                      lambda lg, replies: [("mentions ब्लाउज़ due today", "ब्लाउज़" in replies[-1] or "blouse" in replies[-1].lower(), replies[-1][:100]),
                                           ("omits far कुर्ती", "कुर्ती" not in replies[-1] and "kurti" not in replies[-1].lower(), replies[-1][:100])]))

    # 9) corrections: 2 convs (4 checks)
    for ci in range(2):
        n = next_name()
        g = GARMENTS[ci]
        price0, pw0 = PRICES[ci]
        price1, pw1 = HARD_NUMBERS[ci]
        due_d, dw = DATES[ci]
        opening = ORDER_STYLES[lang][0].format(n=n, g=g, p=pw0[lang], d=dw[lang])

        def checks_c(lg, replies, price1=price1):
            orders = lg.all_open_orders()
            return [("one order only", len(orders) == 1, str(len(orders))),
                    (f"corrected to {price1}", bool(orders) and orders[0]["price"] == price1, str(orders and orders[0]["price"]))]

        convs.append(Conv(f"correction{ci+1}", None, opening,
                          [NEW_CLIENT_YES[lang], CORRECTION[lang].format(p=pw1[lang]), YES[lang], YES[lang]],
                          lambda lg, _, price1=price1: bool(lg.all_open_orders()) and lg.all_open_orders()[0]["price"] == price1,
                          checks_c))

    # 10) multi-order: 2 convs (6 checks)
    for mi in range(2):
        n = next_name()
        g1, g2 = GARMENTS[mi], GARMENTS[mi + 4]
        (p1, pw1), (p2, pw2) = PRICES[mi], PRICES[mi + 2]
        opening = MULTI[lang].format(n=n, g1=g1, p1=pw1[lang], g2=g2, p2=pw2[lang])

        def checks_m(lg, replies, g1=g1, g2=g2, p1=p1, p2=p2):
            orders = {o["garment"]: o for o in lg.all_open_orders()}
            a, b = orders.get(g1), orders.get(g2)
            return [("two orders", len(orders) == 2, str(list(orders))),
                    (f"{g1}={p1}", bool(a) and a["price"] == p1, str(a and a["price"])),
                    (f"{g2}={p2}", bool(b) and b["price"] == p2, str(b and b["price"]))]

        convs.append(Conv(f"multi{mi+1}", None, opening,
                          [NEW_CLIENT_YES[lang], YES[lang], YES[lang]],
                          lambda lg, _: len(lg.all_open_orders()) >= 2, checks_m))

    # 11) garbage: 2 convs (2 checks)
    for gi, tmpl in enumerate(GARBAGE[lang]):
        n = next_name()

        def seed_g(lg, n=n):
            lg.add_client(n)

        convs.append(Conv(f"garbage{gi+1}", seed_g, tmpl.format(n=n), [],
                          lambda lg, replies: bool(replies),
                          lambda lg, replies: [("no writes on fragment", len(lg.all_open_orders()) == 0 and len(lg.list_clients()) == 1,
                                                (replies[-1][:80] if replies else "(no reply)"))]))

    return convs


def main() -> None:
    lang = sys.argv[1] if len(sys.argv) > 1 else "hindi"
    only = sys.argv[2:]  # optional name prefixes, e.g. pay_ delivery reschedule
    assert lang in ORDER_STYLES
    client = OpenAI(api_key=config.SARVAM_API_KEY, base_url="https://api.sarvam.ai/v1",
                    default_headers={"api-subscription-key": config.SARVAM_API_KEY})

    stats = {"calls": 0, "in_tokens": 0, "out_tokens": 0, "latency": [], "tools": [],
             "empties": 0, "empty_retries": 0}
    run = {"lang": lang, "config": "fixed", "effort": "low", "convs": []}
    total_pass = total_checks = 0

    convs = gen_conversations(lang)
    if only:
        convs = [c for c in convs if any(c.name.startswith(p) for p in only)]
    print(f"[{lang}] {len(convs)} conversations" + (f" (filter: {only})" if only else ""))
    for conv in convs:
        ledger = Ledger(conn=db.connect(":memory:"))
        if conv.seed:
            conv.seed(ledger)
        chat = SarvamChat(client, "fixed", "low", ledger, stats)
        replies, transcript = [], []
        r = chat.send(conv.opening)
        replies.append(r)
        transcript.append({"user": conv.opening, "reply": r})
        for fu in conv.followups:
            if conv.done(ledger, replies):
                break
            r = chat.send(fu)
            replies.append(r)
            transcript.append({"user": fu, "reply": r})
        results = conv.checks(ledger, replies)
        ok = sum(1 for _, o, _ in results if o)
        total_pass += ok
        total_checks += len(results)
        flag = "OK " if ok == len(results) else "FAIL"
        print(f"{flag} {conv.name}: {ok}/{len(results)}"
              + ("" if ok == len(results) else "  " + "; ".join(f"{c}->{d}" for c, o, d in results if not o)))
        run["convs"].append({"name": conv.name, "passed": ok, "total": len(results),
                             "checks": [{"name": c, "ok": o, "detail": str(d)[:150]} for c, o, d in results],
                             "transcript": transcript})

    lat = sorted(stats["latency"]) or [0]
    p = lambda q: lat[min(len(lat) - 1, int(q * len(lat)))]  # noqa: E731
    run["summary"] = {"passed": total_pass, "total": total_checks, "calls": stats["calls"],
                      "lat_avg": round(sum(lat) / len(lat), 2), "lat_p50": round(p(0.5), 2),
                      "lat_p90": round(p(0.9), 2), "lat_max": round(lat[-1], 2),
                      "in_tokens": stats["in_tokens"], "out_tokens": stats["out_tokens"],
                      "empties": stats["empties"], "empty_retries": stats["empty_retries"]}
    out = config.ROOT / "data" / "bakeoff" / f"mega_{lang}{'_subset' if only else ''}.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(run, ensure_ascii=False, indent=1), encoding="utf-8")
    s = run["summary"]
    print(f"\n[{lang}] {s['passed']}/{s['total']} checks | {s['calls']} calls | "
          f"lat avg {s['lat_avg']}s p90 {s['lat_p90']}s max {s['lat_max']}s | empties {s['empties']}")


if __name__ == "__main__":
    main()
