"""Spike S2 (host side): turn-taking bake-off on pause-heavy Hindi/Marwari clips.

Builds synthetic "thinking pause" utterances (Bulbul part A + real silence +
Bulbul part B), streams each through Saaras v4's WebSocket at realtime pace,
records the VAD events + finals with timestamps, then simulates endpointing
policies OFFLINE on the recorded event streams:

  vad300         - respond 300ms after END_SPEECH (acoustic-only baseline)
  heur(w)        - completeness heuristics: transcript-so-far looks complete
                   -> respond after 300ms; looks incomplete -> wait w ms
                   (w swept over 1200/1800/2500)

A FALSE CUTOFF = the policy decides to respond at the internal pause instead
of the true end. Smart Turn v3.1 joins this harness later as one more policy;
layers 1-2 must first beat vad300 decisively (the S2 pass bar).

Run:  python -m silai.s2_bakeoff          (results -> data/bakeoff/s2_results.json)
"""

import asyncio
import base64
import json
import re
import time
import wave
from io import BytesIO
from pathlib import Path
from urllib.parse import urlencode

import aiohttp

from . import config

RATE = 16000
OUT = config.ROOT / "data" / "bakeoff"
CLIPS = config.ROOT / "data" / "s2_clips"

# Each scenario: part A (ends mid-thought or complete), pause, part B finishes it.
# 'a_complete' marks whether part A SOUNDS complete (the hard afterthought case).
SCENARIOS = [
    # -- incomplete-sounding part A (heuristics should catch these) --
    dict(id="inc_order_1",  a="सुनीता का ब्लाउज़ लिख दो और",            b="शुक्रवार तक पाँच सौ का।",          pause=1.2, a_complete=False),
    dict(id="inc_order_2",  a="मीना का लहंगा है और उसका दाम",           b="ग्यारह सौ रुपये।",                 pause=1.5, a_complete=False),
    dict(id="inc_price",    a="उसका हिसाब करना है मतलब",                b="तीन सौ रुपये बाकी हैं।",           pause=1.8, a_complete=False),
    dict(id="inc_date",     a="डिलीवरी अगले हफ़्ते की",                 b="मंगलवार को रखना।",                 pause=1.4, a_complete=False),
    dict(id="inc_name",     a="वो नई ग्राहक का नाम",                    b="कविता जैन लिखो।",                  pause=1.6, a_complete=False),
    dict(id="inc_two",      a="दो काम हैं पहला तो",                     b="श्रुति की कुर्ती का नाप।",         pause=1.3, a_complete=False),
    dict(id="inc_advance",  a="एडवांस लिया है दो सौ का",                b="बाकी शुक्रवार को देगी।",           pause=1.2, a_complete=False),
    dict(id="inc_garment",  a="पेटीकोट और साड़ी का फॉल",               b="दोनों सोमवार तक।",                 pause=1.5, a_complete=False),
    dict(id="inc_marwari1", a="थारे सुनीता रो ब्लाउज़ सा",              b="शुक्रवार ताईं लिख दो सा।",         pause=1.4, a_complete=False),
    dict(id="inc_marwari2", a="म्हारी बात सुण पइसा तो",                 b="डेढ़ सौ आयग्या सा।",               pause=1.7, a_complete=False),
    dict(id="inc_count",    a="पूरे तीन ब्लाउज़ हैं एक तो",             b="red वाला परसों का।",               pause=1.3, a_complete=False),
    dict(id="inc_think",    a="उसका दाम रखा था शायद",                   b="पौने पाँच सौ।",                    pause=2.0, a_complete=False),
    dict(id="inc_fitting",  a="फिटिंग के लिए बुलाया है क्योंकि",        b="कमर ढीली थी।",                     pause=1.5, a_complete=False),
    dict(id="inc_alter",    a="अल्टर करना है मगर",                      b="पहले पीको हो जाए।",                pause=1.4, a_complete=False),
    # -- complete-sounding part A + afterthought (speculative-cancel territory) --
    dict(id="aft_order",    a="सुनीता का ब्लाउज़ लिख दो।",              b="हाँ पाँच सौ का रखना।",             pause=1.3, a_complete=True),
    dict(id="aft_paid",     a="मीना ने पैसे दे दिए।",                   b="पूरे सात सौ।",                     pause=1.5, a_complete=True),
    dict(id="aft_date",     a="कल तक तैयार कर दूँगी।",                  b="नहीं परसों कर दो।",                pause=1.6, a_complete=True),
    dict(id="aft_marwari",  a="लहंगो बण ग्यो सा।",                      b="दे दियो सा उनने।",                 pause=1.4, a_complete=True),
    dict(id="aft_briefing", a="आज क्या देना है बताओ।",                  b="और कुल बाकी भी।",                  pause=1.2, a_complete=True),
    dict(id="aft_cancel",   a="वो कुर्ती रहने दो।",                     b="कैंसिल ही कर दो।",                 pause=1.5, a_complete=True),
]

CONNECTIVE_END = re.compile(
    r"(और|का|की|के|से|तक|तो|में|पर|अब|मतलब|यानी|मगर|क्योंकि|शायद|पहला|एक|सा|रो|ताईं|,)\s*$"
)

# Hindi is verb-final: a finished instruction/statement ends in a verb form
# (लिख दो, दे दिए, कर दूँगी, बताओ, है/हैं/था...). Saaras auto-punctuates even
# dangling fragments ("...और" arrives as "...और।"), so punctuation carries no
# signal - the ending WORD does.
VERB_END = re.compile(
    r"(दो|दें|दे|दिया|दिए|दी|दियो|गया|गए|गई|ग्यो|गी|गे|है|हैं|था|थे|थी|हूँ|हो"
    r"|करो|कर|करना|रखो|रखना|देना|लेना|लो|बनाओ|बताओ|सुनाओ|चलाओ|जाओ|जाए|चाहिए"
    r"|िए|िये|ेगा|ेगी|ेंगे|ूँगा|ूँगी|नहीं)\s*$"
)


def looks_complete(text: str) -> bool:
    """Layer-2 heuristic: complete = not connective-ending AND verb-final."""
    t = text.strip()
    if not t:
        return False
    base = re.sub(r"[।?!.,…\s]+$", "", t)
    if not base or CONNECTIVE_END.search(base):
        return False
    return bool(VERB_END.search(base))


async def synth(session: aiohttp.ClientSession, text: str) -> bytes:
    """Bulbul REST -> 16k mono PCM16 (header stripped)."""
    body = {
        "text": text, "model": "bulbul:v3", "speaker": "suhani",
        "target_language_code": "hi-IN", "speech_sample_rate": RATE,
        "pace": 1.05, "temperature": 0.75,
    }
    async with session.post(
        "https://api.sarvam.ai/text-to-speech",
        json=body, headers={"api-subscription-key": config.SARVAM_API_KEY},
    ) as res:
        data = await res.json()
    wav = base64.b64decode(data["audios"][0])
    with wave.open(BytesIO(wav)) as w:
        assert w.getframerate() == RATE, w.getframerate()
        return w.readframes(w.getnframes())


async def build_clip(session: aiohttp.ClientSession, sc: dict) -> dict:
    """Part A + silence + part B (+ lead/tail), cached on disk."""
    CLIPS.mkdir(parents=True, exist_ok=True)
    path = CLIPS / f"{sc['id']}.wav"
    meta_path = CLIPS / f"{sc['id']}.json"
    if not (path.exists() and meta_path.exists()):
        a = await synth(session, sc["a"])
        b = await synth(session, sc["b"])
        silence = b"\x00" * int(RATE * sc["pause"]) * 2
        lead = b"\x00" * int(RATE * 0.4) * 2
        tail = b"\x00" * int(RATE * 0.4) * 2
        pcm = lead + a + silence + b + tail
        with wave.open(str(path), "wb") as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
            w.writeframes(pcm)
        meta_path.write_text(json.dumps({"pause_at": (len(lead) + len(a)) / 2 / RATE}), encoding="utf-8")
    sc["pause_at"] = json.loads(meta_path.read_text(encoding="utf-8"))["pause_at"]
    with wave.open(str(path)) as w:
        pcm = w.readframes(w.getnframes())
    sc["duration"] = len(pcm) / 2 / RATE
    return {"pcm": pcm, **sc}


async def stream_clip(session: aiohttp.ClientSession, clip: dict) -> list[dict]:
    """Realtime-paced stream through Saaras v4; returns timestamped events."""
    params = {
        "language-code": "hi-IN", "model": "saaras:v4", "mode": "codemix",
        "vad_signals": "true", "sample_rate": str(RATE),
        "high_vad_sensitivity": "true", "flush_signal": "true",
    }
    url = f"wss://api.sarvam.ai/speech-to-text/ws?{urlencode(params)}"
    events: list[dict] = []
    chunk = int(RATE * 0.05) * 2  # 50ms
    async with session.ws_connect(url, headers={"api-subscription-key": config.SARVAM_API_KEY}) as ws:
        t0 = time.monotonic()

        async def sender() -> None:
            pcm = clip["pcm"]
            for i in range(0, len(pcm), chunk):
                b64 = base64.b64encode(pcm[i:i + chunk]).decode()
                await ws.send_str(json.dumps({"audio": {
                    "data": b64, "encoding": "audio/wav", "sample_rate": RATE}}))
                await asyncio.sleep(0.048)
            await ws.send_str(json.dumps({"type": "end_of_stream", "audio": {
                "data": "", "encoding": "audio/wav", "sample_rate": RATE}}))

        send = asyncio.create_task(sender())
        end_by = time.monotonic() + clip["duration"] + 8
        while time.monotonic() < end_by:
            try:
                msg = await ws.receive(timeout=max(0.1, end_by - time.monotonic()))
            except asyncio.TimeoutError:
                break
            if msg.type != aiohttp.WSMsgType.TEXT:
                break
            data = json.loads(msg.data)
            t = time.monotonic() - t0
            kind = data.get("type")
            if kind == "events":
                events.append({"t": t, "e": data["data"].get("signal_type", "")})
            elif kind == "data":
                text = (data.get("data") or {}).get("transcript") or ""
                if text.strip():
                    events.append({"t": t, "e": "FINAL", "text": text})
        send.cancel()
    return events


def simulate(
    events: list[dict],
    clip: dict,
    wait_complete: float,
    wait_incomplete: float | None,
    spec_cancel_s: float | None = None,
) -> dict:
    """Replay the event log through an endpointing policy.

    Decision rule: after each END_SPEECH, respond once the wait elapses with no
    new START_SPEECH. wait_incomplete=None = acoustic-only (always wait_complete).

    spec_cancel_s models layer 3: a fire is CANCELLED (never heard) if speech
    resumes within that window (the brain+TTS first-audio time), and the
    conversation simply continues - the cost is one wasted LLM call, not an
    interruption.
    """
    ordered = sorted(events, key=lambda e: e["t"])
    # In production the ON-DEVICE Silero VAD catches resumption within ~150ms
    # of real speech; Sarvam's server START_SPEECH lags by up to ~1.1s (start
    # detection needs sustained speech), so the cancel check must use the real
    # resumption time, which for these synthetic clips is known ground truth.
    real_resume = clip["pause_at"] + clip["pause"] + 0.15
    # Server STARTs also witness intra-utterance resumptions (they lag actual
    # speech by ~0.5-1.1s; local VAD detects within ~0.15s, so a server START
    # within window+lag means the real resumption fell inside the window).
    server_starts = [e["t"] for e in ordered if e["e"] == "START_SPEECH"]
    transcript = ""
    fired_at = None
    cancels = 0
    pending = None  # (end_speech_t, deadline)
    for ev in ordered:
        while pending and ev["t"] >= pending[1]:
            t_fire = pending[1]
            resumed = spec_cancel_s is not None and (
                t_fire < real_resume <= t_fire + spec_cancel_s
                or any(t_fire < s <= t_fire + spec_cancel_s + 1.0 for s in server_starts)
            )
            if resumed:
                cancels += 1
                pending = None  # she kept talking; the response never played
            else:
                fired_at = t_fire
                break
        if fired_at is not None:
            break
        if ev["e"] == "START_SPEECH":
            pending = None
        elif ev["e"] == "FINAL":
            transcript = (transcript + " " + ev["text"]).strip()
            if pending:  # re-judge the wait with the newly arrived text
                wait = wait_complete if (wait_incomplete is None or looks_complete(transcript)) else wait_incomplete
                pending = (pending[0], pending[0] + wait)
        elif ev["e"] == "END_SPEECH":
            wait = wait_complete if (wait_incomplete is None or looks_complete(transcript)) else wait_incomplete
            pending = (ev["t"], ev["t"] + wait)
    if fired_at is None and pending:
        fired_at = pending[1]

    true_end = clip["duration"] - 0.4  # tail silence excluded
    false_cutoff = fired_at is not None and fired_at < clip["pause_at"] + clip["pause"] - 0.05
    delay = None if fired_at is None else round(max(0.0, fired_at - true_end), 2)
    return {"false_cutoff": bool(false_cutoff), "delay_after_end_s": delay, "cancels": cancels}


async def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    async with aiohttp.ClientSession() as session:
        sem = asyncio.Semaphore(4)

        async def run_one(sc: dict) -> dict:
            async with sem:
                clip = await build_clip(session, dict(sc))
                events = await stream_clip(session, clip)
                return {"clip": {k: clip[k] for k in ("id", "pause_at", "pause", "duration", "a_complete")},
                        "events": events}

        rows = await asyncio.gather(*(run_one(sc) for sc in SCENARIOS))

    # Keep the raw event logs - they are the debugging ground truth.
    (OUT / "s2_events.json").write_text(
        json.dumps(rows, ensure_ascii=False, indent=1), encoding="utf-8")

    policies = {
        "vad300 (acoustic-only)": (0.3, None, None),
        "vad300 + cancel1.5": (0.3, None, 1.5),
        "heur wc=500 wi=1800": (0.5, 1.8, None),
        "heur500/1800 + cancel1.5": (0.5, 1.8, 1.5),
    }
    results: dict = {"clips": len(rows), "policies": {}}
    print(f"\n{'policy':24} {'false cutoffs':>14} {'incomplete-A':>13} {'afterthought':>13} {'avg delay':>10}")
    for name, (wc, wi, cancel) in policies.items():
        outcomes = []
        for row in rows:
            sim = simulate(row["events"], row["clip"], wc, wi, cancel)
            outcomes.append({**sim, "id": row["clip"]["id"], "a_complete": row["clip"]["a_complete"]})
        fc = [o for o in outcomes if o["false_cutoff"]]
        fc_inc = [o for o in fc if not o["a_complete"]]
        fc_aft = [o for o in fc if o["a_complete"]]
        delays = [o["delay_after_end_s"] for o in outcomes if o["delay_after_end_s"] is not None]
        avg_delay = round(sum(delays) / len(delays), 2) if delays else None
        results["policies"][name] = {
            "false_cutoffs": len(fc), "on_incomplete_A": len(fc_inc),
            "on_afterthought_A": len(fc_aft), "avg_delay_after_true_end_s": avg_delay,
            "outcomes": outcomes,
        }
        print(f"{name:24} {len(fc):>10}/{len(rows):<3} {len(fc_inc):>13} {len(fc_aft):>13} {avg_delay!s:>10}")

    (OUT / "s2_results.json").write_text(json.dumps(results, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"\nsaved -> {OUT / 's2_results.json'}")
    print("PASS BAR: heur rows must show ~0 false cutoffs on incomplete-A clips; "
          "afterthought-A cutoffs are the speculative-cancel budget, not failures.")


if __name__ == "__main__":
    asyncio.run(main())
