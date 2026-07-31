"""Voice-shortlist demo for mom: synthesizes the same Hindi line in each candidate
Bulbul v3 voice so she can pick. Run (needs SARVAM_API_KEY):

    python -m silai.voice_demo

Writes data/voice_demo/<speaker>.wav - play them for her, set the winner as
VOICE_SPEAKER in .env.
"""

import base64
import sys
from pathlib import Path

import httpx

from . import config

# All Bulbul v3 female Hindi voices - pick the winner by ear, not by name.
FEMALE_VOICES = [
    "ishita", "kavitha", "kavya", "neha", "pooja", "priya", "ritu",
    "roopa", "rupali", "shruti", "shreya", "simran", "suhani", "tanya",
]
SHORTLIST = FEMALE_VOICES

SAMPLE_LINE = (
    "नमस्ते! मैं सुहानी हूँ, आपकी सिलाई बही। "
    "आज का दिन अच्छा है - सुनीता जी का ब्लाउज़ तैयार है, "
    "और मीना जी के डेढ़ सौ रुपये भी आ गए! और बताइए, क्या लिखूँ?"
)

API_URL = "https://api.sarvam.ai/text-to-speech"


def synthesize(speaker: str, out_dir: Path) -> Path:
    resp = httpx.post(
        API_URL,
        headers={"api-subscription-key": config.SARVAM_API_KEY},
        json={
            "inputs": [SAMPLE_LINE],
            "target_language_code": "hi-IN",
            "model": "bulbul:v3",
            "speaker": speaker,
            "speech_sample_rate": 24000,
            "pace": config.VOICE_PACE,
            "temperature": config.VOICE_TEMPERATURE,
        },
        timeout=60,
    )
    resp.raise_for_status()
    audio_b64 = resp.json()["audios"][0]
    out = out_dir / f"{speaker}.wav"
    out.write_bytes(base64.b64decode(audio_b64))
    return out


def main() -> None:
    if not config.SARVAM_API_KEY or config.SARVAM_API_KEY.startswith("TODO"):
        print("SARVAM_API_KEY missing in .env - add it first, then re-run.")
        sys.exit(1)
    out_dir = config.ROOT / "data" / "voice_demo"
    out_dir.mkdir(parents=True, exist_ok=True)
    speakers = sys.argv[1:] or SHORTLIST
    for speaker in speakers:
        try:
            path = synthesize(speaker, out_dir)
            print(f"OK  {speaker}: {path}")
        except httpx.HTTPStatusError as e:
            print(f"ERR {speaker}: {e.response.status_code} {e.response.text[:200]}")
    print("\nPlay the files, pick the winner, set VOICE_SPEAKER in .env.")


if __name__ == "__main__":
    main()
