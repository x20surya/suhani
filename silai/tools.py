"""Anthropic tool schemas + dispatcher over the Ledger.

Used by the text debug chat (brain_text.py). The LiveKit voice agent exposes
the same operations as @function_tool methods in agent.py - both are thin
wrappers over silai.ledger, which is the single source of truth.
"""

import copy
import datetime as dt
import json
from typing import Any

from . import prompts
from .ledger import Ledger

ANTHROPIC_TOOLS: list[dict] = [
    {
        "name": "find_client",
        "description": (
            "ग्राहक को नाम से ढूँढो। Fuzzy matching करता है - STT की गलत spelling भी पकड़ लेता है। "
            "किसी भी ग्राहक से जुड़े काम से पहले हमेशा यह चलाओ। खाली सूची मिले तो ग्राहक नई है।"
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "name": {"type": "string", "description": "जैसा नाम सुना, वैसा ही (honorific हटाने की ज़रूरत नहीं)"}
            },
            "required": ["name"],
            "additionalProperties": False,
        },
    },
    {
        "name": "add_client",
        "description": "नई ग्राहक जोड़ो। सिर्फ़ तब जब find_client से कोई ना मिले और मालकिन ने पक्का कर दिया हो कि नई हैं।",
        "input_schema": {
            "type": "object",
            "properties": {
                "name": {"type": "string", "description": "ग्राहक का नाम, हमेशा Devanagari लिपि में (जैसे: सुनीता, मीना जैन)"},
                "phone": {"type": "string", "description": "optional फ़ोन नंबर"},
            },
            "required": ["name"],
            "additionalProperties": False,
        },
    },
    {
        "name": "add_order",
        "description": "नया ऑर्डर लिखो। मालकिन से 'लिख दूँ?' पूछ कर हाँ मिलने के बाद ही चलाओ।",
        "input_schema": {
            "type": "object",
            "properties": {
                "client_id": {"type": "integer"},
                "garment": {"type": "string", "description": "कपड़ा, हमेशा Devanagari लिपि में (जैसे: ब्लाउज़, साड़ी फॉल-पीको, कुर्ती)"},
                "price": {"type": "number", "description": "कुल दाम, रुपये में"},
                "due_date": {"type": "string", "description": "ISO date YYYY-MM-DD; पता ना हो तो मत भेजो"},
                "advance": {"type": "number", "description": "एडवांस मिला हो तो कितना"},
                "notes": {"type": "string", "description": (
                    "मालकिन ने रंग/नाप/फ़रमाइश बोली हो तो यहाँ ज़रूर डालो, जैसे बोला वैसे ही "
                    "(red बोला तो red)। ना बोली हो तो यह field मत भेजो।"
                )},
            },
            "required": ["client_id", "garment", "price"],
            "additionalProperties": False,
        },
    },
    {
        "name": "update_order",
        "description": (
            "ऑर्डर बदलो: दाम, तारीख़, status, notes। कपड़ा दे दिया = status='delivered'। "
            "मालकिन 'रहने दो/कैंसिल कर दो' कहें = status='cancelled' (पैसे का हिसाब भी हट जाता है)।"
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "order_id": {"type": "integer"},
                "garment": {"type": "string"},
                "price": {"type": "number"},
                "due_date": {"type": "string"},
                "notes": {"type": "string"},
                "status": {"type": "string", "enum": ["open", "done", "delivered", "cancelled"]},
            },
            "required": ["order_id"],
            "additionalProperties": False,
        },
    },
    {
        "name": "orders_for_client",
        "description": "एक ग्राहक के सारे खुले ऑर्डर और हर एक का बाकी पैसा (balance)।",
        "input_schema": {
            "type": "object",
            "properties": {"client_id": {"type": "integer"}},
            "required": ["client_id"],
            "additionalProperties": False,
        },
    },
    {
        "name": "list_open_orders",
        "description": (
            "सारे खुले ऑर्डर एक साथ - हर एक के साथ ग्राहक, कपड़ा, दाम, तारीख़ और notes (रंग वग़ैरह)। "
            "जब मालकिन किसी detail से पूछें ('वो maroon वाला किसका था?') या पूरा काम देखना हो, यह चलाओ।"
        ),
        "input_schema": {"type": "object", "properties": {}, "additionalProperties": False},
    },
    {
        "name": "search_orders",
        "description": (
            "पुराने-नए सारे ऑर्डर में ढूँढो - बंद (delivered/cancelled) भी। रंग, नाप, कपड़े या नाम "
            "जैसे शब्दों से। जब 'वो maroon वाला किसका था?' खुले ऑर्डर में ना मिले, या दिए जा चुके / "
            "पुराने काम की बात हो, यह चलाओ। कुछ ना मिले तो साफ़ कहो - अंदाज़ा कभी मत लगाओ।"
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "query": {"type": "string", "description": "ढूँढने के शब्द, जैसे 'maroon ब्लाउज़' या 'गोटा पत्ती लहंगा'"}
            },
            "required": ["query"],
            "additionalProperties": False,
        },
    },
    {
        "name": "orders_due",
        "description": "किसी तारीख़-सीमा में देने वाले खुले ऑर्डर। 'आज क्या देना है' = आज से आज तक।",
        "input_schema": {
            "type": "object",
            "properties": {
                "date_from": {"type": "string", "description": "ISO date"},
                "date_to": {"type": "string", "description": "ISO date"},
            },
            "required": ["date_from", "date_to"],
            "additionalProperties": False,
        },
    },
    {
        "name": "check_dues",
        "description": "किस-किस का पैसा बाकी है, कितना। ग्राहक बताई हो तो उसका, वरना सबका।",
        "input_schema": {
            "type": "object",
            "properties": {"client_id": {"type": "integer", "description": "optional; ना भेजो तो सबका"}},
            "additionalProperties": False,
        },
    },
    {
        "name": "mark_paid",
        "description": "पैसे मिले - दर्ज करो। मालकिन से पक्का करके ही चलाओ।",
        "input_schema": {
            "type": "object",
            "properties": {
                "client_id": {"type": "integer"},
                "amount": {"type": "number"},
                "order_id": {"type": "integer", "description": "पता हो तो किस ऑर्डर का"},
                "note": {"type": "string"},
            },
            "required": ["client_id", "amount"],
            "additionalProperties": False,
        },
    },
    {
        "name": "daily_briefing",
        "description": "आज का सार: आज देना, कल देना, लेट हो चुके ऑर्डर, कुल बाकी पैसा।",
        "input_schema": {"type": "object", "properties": {}, "additionalProperties": False},
    },
    {
        "name": "remember",
        "description": (
            "मालकिन की निजी बात की छोटी याद लिखो (परिवार, त्योहार, सेहत, पसंद) - अगली बातचीतों में "
            "याद रहेगी। एक वाक्य में। ऑर्डर/पैसे यहाँ मत लिखो।"
        ),
        "input_schema": {
            "type": "object",
            "properties": {"content": {"type": "string", "description": "एक छोटा वाक्य"}},
            "required": ["content"],
            "additionalProperties": False,
        },
    },
    {
        "name": "forget_note",
        "description": "कोई याद गलत/पुरानी हो जाए तो उसे हटाओ (id यादों की सूची में [id] जैसा दिखता है)।",
        "input_schema": {
            "type": "object",
            "properties": {"note_id": {"type": "integer"}},
            "required": ["note_id"],
            "additionalProperties": False,
        },
    },
]

# Deterministic date calculator for the Sarvam brain (it must never do weekday
# arithmetic itself - that was its worst tested failure mode). Not registered in
# ANTHROPIC_TOOLS: Claude resolves dates reliably without it.
RESOLVE_DATE_TOOL: dict = {
    "name": "resolve_due_date",
    "description": (
        "तारीख़ पक्की करने का calculator। 'अगले मंगलवार', 'दो हफ़्ते बाद', '15 अगस्त' जैसी बात को "
        "सही ISO तारीख़ में बदलता है। due_date भेजने से पहले इसी से तारीख़ लो।"
    ),
    "input_schema": {
        "type": "object",
        "properties": {
            "weekday": {"type": "string",
                        "enum": ["सोमवार", "मंगलवार", "बुधवार", "गुरुवार", "शुक्रवार", "शनिवार", "रविवार"],
                        "description": "अगला आने वाला यह वार चाहिए तो"},
            "days_from_today": {"type": "integer", "description": "आज से इतने दिन बाद (कल=1, परसों=2, दो हफ़्ते=14)"},
            "day_of_month": {"type": "integer", "description": "महीने की तारीख़, जैसे 15"},
            "month": {"type": "integer", "description": "1-12; day_of_month के साथ"},
        },
        "additionalProperties": False,
    },
}


def resolve_due_date(args: dict, today: dt.date | None = None) -> str:
    today = today or dt.datetime.now(prompts.IST).date()
    try:
        if args.get("weekday"):
            target = prompts.HINDI_WEEKDAYS.index(args["weekday"])
            d = today + dt.timedelta(days=(target - today.weekday() - 1) % 7 + 1)
        elif args.get("days_from_today") is not None:
            d = today + dt.timedelta(days=int(args["days_from_today"]))
        elif args.get("day_of_month"):
            month = int(args.get("month") or today.month)
            d = dt.date(today.year, month, int(args["day_of_month"]))
            if d < today:
                d = dt.date(today.year + 1, month, int(args["day_of_month"]))
        else:
            return json.dumps({"error": "कोई input नहीं मिला"}, ensure_ascii=False)
        return json.dumps({"date": d.isoformat(), "weekday": prompts.HINDI_DAYS_SHORT[d.weekday()],
                           "human": f"{d.day} {prompts.HINDI_MONTHS[d.month - 1]}"}, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"error": str(e)}, ensure_ascii=False)


def openai_toolset(with_date_guards: bool = True) -> list[dict]:
    """The toolset in OpenAI function-calling format for the Sarvam brain.
    with_date_guards adds the resolve_due_date tool + date hints on descriptions
    (the bake-off's "fixed" config; False = the plain baseline set)."""
    ts = copy.deepcopy(ANTHROPIC_TOOLS)
    if with_date_guards:
        for t in ts:
            if t["name"] == "add_order":
                t["description"] += (
                    " due_date: हफ़्ते के दिन का नाम (शुक्रवार/Friday) हो तो पहले resolve_due_date "
                    "चलाओ; कल/परसों/सीधी तारीख़ calendar से।"
                )
            if t["name"] == "orders_due":
                t["description"] += " 'इस हफ़्ते' = आज से आने वाले रविवार तक।"
        ts.append(RESOLVE_DATE_TOOL)
    ts.append({
        "name": "end_conversation",
        "description": ("बातचीत ख़त्म करो। मालकिन बात समेट दें (\"ठीक है अभी के लिए\", \"चलो अभी कुछ नहीं\") "
                        "तो यह चलाओ और साथ के जवाब में छोटा विदा-वाक्य बोलो। बीच काम में कभी नहीं।"),
        "input_schema": {"type": "object", "properties": {}, "additionalProperties": False},
    })
    return [{"type": "function",
             "function": {"name": t["name"], "description": t["description"],
                          "parameters": t["input_schema"]}} for t in ts]


def dispatch(ledger: Ledger, name: str, args: dict[str, Any]) -> str:
    """Run one tool call; always returns a JSON string for the tool_result."""
    try:
        if name == "resolve_due_date":
            return resolve_due_date(args)
        if name == "end_conversation":
            return json.dumps({"ok": True, "note": "विदा बोलिए, app बंद हो जाएगा"}, ensure_ascii=False)
        if name == "find_client":
            matches = ledger.find_client(args["name"])
            # Remember successful hearings as aliases so matching improves over time.
            if len(matches) == 1:
                ledger.add_alias(matches[0]["id"], args["name"])
            result: Any = {"matches": matches}
        elif name == "add_client":
            result = ledger.add_client(args["name"], args.get("phone"))
        elif name == "add_order":
            result = ledger.add_order(
                client_id=args["client_id"],
                garment=args["garment"],
                price=args["price"],
                due_date=args.get("due_date"),
                advance=args.get("advance", 0) or 0,
                notes=args.get("notes"),
            )
        elif name == "update_order":
            result = ledger.update_order(
                order_id=args["order_id"],
                garment=args.get("garment"),
                price=args.get("price"),
                due_date=args.get("due_date"),
                notes=args.get("notes"),
                status=args.get("status"),
            )
        elif name == "orders_for_client":
            result = {"orders": ledger.orders_for_client(args["client_id"])}
        elif name == "list_open_orders":
            result = {"orders": ledger.all_open_orders()}
        elif name == "search_orders":
            result = {"orders": ledger.search_orders(args["query"])}
        elif name == "orders_due":
            result = {"orders": ledger.orders_due(args["date_from"], args["date_to"])}
        elif name == "check_dues":
            if args.get("client_id"):
                result = {
                    "client": ledger.get_client(args["client_id"]),
                    "due": ledger.client_due(args["client_id"]),
                    "orders": ledger.orders_for_client(args["client_id"]),
                }
            else:
                result = {"dues": ledger.all_dues()}
        elif name == "mark_paid":
            result = ledger.mark_paid(
                args["client_id"], args["amount"], args.get("order_id"), args.get("note")
            )
        elif name == "daily_briefing":
            result = ledger.daily_briefing()
        elif name == "remember":
            result = ledger.add_note(args["content"])
        elif name == "forget_note":
            result = ledger.forget_note(args["note_id"])
        else:
            return json.dumps({"error": f"unknown tool {name}"})
        return json.dumps(result, ensure_ascii=False, default=str)
    except Exception as e:  # tool errors go back to the model, never crash the loop
        return json.dumps({"error": str(e)}, ensure_ascii=False)
