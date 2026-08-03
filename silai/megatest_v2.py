"""Run the scenario gauntlet against the CURRENT SHIPPED prompt (the Kotlin
app's Prompts.kt - sectioned structure + Hinglish style), not the frozen
Python baseline. Extracts the raw-string blocks straight from Prompts.kt so
the harness tests exactly what the phone runs.

Run:  python -m silai.megatest_v2   (output/JSONs identical to megatest_sarvam)
"""

import re
import runpy
from pathlib import Path

from . import prompts

_KT = Path(__file__).resolve().parent.parent / "suhani-app/app/src/main/kotlin/app/suhani/core/Prompts.kt"


def _block(name: str) -> str:
    src = _KT.read_text(encoding="utf-8")
    m = re.search(r'val %s = """(.*?)"""' % name, src, re.S)
    if not m:
        raise SystemExit(f"could not extract {name} from Prompts.kt")
    return m.group(1)


prompts.STABLE_SYSTEM = _block("STABLE_SYSTEM")
prompts.SARVAM_GUARDS = _block("SARVAM_GUARDS")
print(f"[megatest_v2] patched prompts from Prompts.kt "
      f"(system {len(prompts.STABLE_SYSTEM)} chars, guards {len(prompts.SARVAM_GUARDS)} chars)")

runpy.run_module("silai.megatest_sarvam", run_name="__main__")
