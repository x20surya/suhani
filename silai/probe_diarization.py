"""Probe: does Saaras v4 expose speaker labels (Multi-Speaker) via API?

Builds a 2-speaker clip (Bulbul suhani + a male voice), then tries candidate
diarization params on REST and the streaming WS, printing raw responses.

Run: python -m silai.probe_diarization
"""

import asyncio
import base64
import json
import wave
from io import BytesIO
from urllib.parse import urlencode

import aiohttp

from . import config

RATE = 16000
KEY = config.SARVAM_API_KEY
H = {"api-subscription-key": KEY}


async def synth(session, text, speakers):
    """Try speaker candidates in order until one works on bulbul:v3."""
    for speaker in speakers:
        body = {"text": text, "model": "bulbul:v3", "speaker": speaker,
                "target_language_code": "hi-IN", "speech_sample_rate": RATE}
        async with session.post("https://api.sarvam.ai/text-to-speech", json=body, headers=H) as r:
            data = await r.json()
        if "audios" in data:
            print(f"  synth ok: {speaker}")
            wav = base64.b64decode(data["audios"][0])
            with wave.open(BytesIO(wav)) as w:
                return w.readframes(w.getnframes())
        print(f"  synth {speaker} failed: {json.dumps(data)[:120]}")
    raise SystemExit("no working speaker")


async def main():
    async with aiohttp.ClientSession() as s:
        a = await synth(s, "सुनीता का ब्लाउज़ शुक्रवार तक लिख दो, पाँच सौ का।", ["suhani"])
        b = await synth(s, "अरे नहीं नहीं, वो तो सोमवार को चाहिए!", ["abhilash", "rahul", "amit", "karun", "pooja"])
        gap = b"\x00" * RATE  # 0.5s
        pcm = a + gap + b + gap + a[: RATE * 2]
        wav_buf = BytesIO()
        with wave.open(wav_buf, "wb") as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
            w.writeframes(pcm)
        wav_bytes = wav_buf.getvalue()
        print(f"clip: {len(pcm)//2//RATE}s, two speakers")

        # --- REST candidates ---
        for extra in [{"model": "saaras:v4-multispk", "language_code": "hi-IN"},
                      {"model": "saaras:v4-multispk", "language_code": "hi-IN", "num_speakers": "2"}]:
            form = aiohttp.FormData()
            form.add_field("file", wav_bytes, filename="a.wav", content_type="audio/wav")
            form.add_field("model", extra.pop("model", "saaras:v4"))
            for k, v in extra.items():
                form.add_field(k, v)
            async with s.post("https://api.sarvam.ai/speech-to-text", data=form, headers=H) as r:
                txt = await r.text()
                print(f"\nREST {extra}: http {r.status}\n  {txt[:300]}")

        # --- WS candidates ---
        for params in [{}, {"mode": "codemix"}]:
            q = {"language-code": "hi-IN", "model": "saaras:v4-multispk", "sample_rate": str(RATE),
                 "vad_signals": "true", **params}
            url = f"wss://api.sarvam.ai/speech-to-text/ws?{urlencode(q)}"
            try:
                async with s.ws_connect(url, headers=H) as ws:
                    chunk = RATE // 10 * 2
                    for i in range(0, len(pcm), chunk):
                        await ws.send_str(json.dumps({"audio": {
                            "data": base64.b64encode(pcm[i:i+chunk]).decode(),
                            "encoding": "audio/wav", "sample_rate": RATE}}))
                        await asyncio.sleep(0.02)
                    await ws.send_str(json.dumps({"type": "end_of_stream", "audio": {
                        "data": "", "encoding": "audio/wav", "sample_rate": RATE}}))
                    msgs = []
                    end = asyncio.get_event_loop().time() + 12
                    while asyncio.get_event_loop().time() < end:
                        try:
                            m = await ws.receive(timeout=2)
                        except asyncio.TimeoutError:
                            break
                        if m.type != aiohttp.WSMsgType.TEXT:
                            msgs.append(f"closed {m.data}")
                            break
                        msgs.append(m.data[:600])
                    print(f"\nWS {params}: {len(msgs)} msgs")
                    for m in msgs[:6]:
                        print("  ", m)
            except Exception as e:
                print(f"\nWS {params}: ERROR {e}")


asyncio.run(main())
