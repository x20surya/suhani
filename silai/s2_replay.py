"""Offline replay of the S2 bake-off from saved event logs - zero API cost.

Re-simulates every policy against data/bakeoff/s2_events.json after heuristic
changes in s2_bakeoff.py. Run:  python -m silai.s2_replay [-v]
"""

import json
import sys

from . import config
from .s2_bakeoff import simulate, looks_complete

OUT = config.ROOT / "data" / "bakeoff"


def main() -> None:
    verbose = "-v" in sys.argv
    rows = json.loads((OUT / "s2_events.json").read_text(encoding="utf-8"))
    policies = {
        "vad300 (acoustic-only)": (0.3, None, None),
        "vad300 + cancel1.5": (0.3, None, 1.5),
        "heur wc=500 wi=1800": (0.5, 1.8, None),
        "heur500/1800 + cancel1.5": (0.5, 1.8, 1.5),
        "heur500/1800 + cancel1.2": (0.5, 1.8, 1.2),
    }
    print(f"{'policy':26} {'false cutoffs':>14} {'incomplete-A':>13} {'afterthought':>13} {'cancels':>8} {'avg delay':>10}")
    for name, (wc, wi, cancel) in policies.items():
        fc_inc = fc_aft = cancels = 0
        delays = []
        details = []
        for row in rows:
            sim = simulate(row["events"], row["clip"], wc, wi, cancel)
            cancels += sim["cancels"]
            if sim["false_cutoff"]:
                if row["clip"]["a_complete"]:
                    fc_aft += 1
                else:
                    fc_inc += 1
                details.append(f"    CUT {row['clip']['id']}")
            if sim["delay_after_end_s"] is not None:
                delays.append(sim["delay_after_end_s"])
        avg = round(sum(delays) / len(delays), 2) if delays else None
        print(f"{name:26} {fc_inc + fc_aft:>10}/{len(rows):<3} {fc_inc:>13} {fc_aft:>13} {cancels:>8} {avg!s:>10}")
        if verbose:
            print("\n".join(details))


if __name__ == "__main__":
    main()
