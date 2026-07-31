const KEY = "suhani.gain";

/** 1 = normal. Above ~3 Bulbul clips on a phone speaker, which is worse than quiet. */
export const MIN_GAIN = 1;
export const MAX_GAIN = 3;
export const DEFAULT_GAIN = 2;

export function loadGain(): number {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return DEFAULT_GAIN;
    const n = Number(raw);
    if (!Number.isFinite(n)) return DEFAULT_GAIN;
    return Math.min(MAX_GAIN, Math.max(MIN_GAIN, n));
  } catch {
    return DEFAULT_GAIN;
  }
}

export function saveGain(value: number): void {
  try {
    localStorage.setItem(KEY, String(value));
  } catch {
    /* private mode - the in-memory value still applies for this session */
  }
}
