"""Threshold sanity-check for the speaker gate, host-side, with the EXACT model
the app ships. Synthesizes distinct Bulbul voices, scores same-voice vs
different-voice cosine similarities. Run: python -m silai.calibrate_speaker
"""

import asyncio
import base64
import wave
from io import BytesIO

import aiohttp
import numpy as np
import sherpa_onnx

from . import config

RATE = 16000
MODEL = "suhani-app/app/src/main/assets/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx"
H = {"api-subscription-key": config.SARVAM_API_KEY}

TEXTS = [
    "सुनीता का ब्लाउज़ शुक्रवार तक लिख दो, पाँच सौ का।",
    "आज क्या-क्या देना है ज़रा बताओ।",
]
VOICES = ["suhani", "rahul", "pooja"]  # female, male, female


async def synth(session, text, speaker):
    body = {"text": text, "model": "bulbul:v3", "speaker": speaker,
            "target_language_code": "hi-IN", "speech_sample_rate": RATE}
    async with session.post("https://api.sarvam.ai/text-to-speech", json=body, headers=H) as r:
        wav = base64.b64decode((await r.json())["audios"][0])
    with wave.open(BytesIO(wav)) as w:
        pcm = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16)
    return pcm.astype(np.float32) / 32768.0


def main() -> None:
    ext = sherpa_onnx.SpeakerEmbeddingExtractor(
        sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=str(config.ROOT / MODEL), num_threads=2),
    )

    async def gather():
        async with aiohttp.ClientSession() as s:
            return {v: [await synth(s, t, v) for t in TEXTS] for v in VOICES}

    clips = asyncio.run(gather())

    def embed(samples):
        st = ext.create_stream()
        st.accept_waveform(RATE, samples)
        st.input_finished()
        return np.array(ext.compute(st))

    embs = {v: [embed(c) for c in cs] for v, cs in clips.items()}

    def cos(a, b):
        return float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b)))

    print("same-voice (should be HIGH, above 0.45):")
    for v in VOICES:
        print(f"  {v} vs {v}: {cos(embs[v][0], embs[v][1]):.3f}")
    print("different-voice (should be LOW, below 0.45):")
    for i, a in enumerate(VOICES):
        for b in VOICES[i + 1:]:
            print(f"  {a} vs {b}: {cos(embs[a][0], embs[b][0]):.3f}")


main()
