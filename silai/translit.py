"""Rough Devanagari-to-Latin normalization for fuzzy client-name matching.

STT will spell the same client three ways across days ("सुनीता", "Sunita",
"सुनिता"). We never trust raw transcripts: both stored names and queries are
reduced to a coarse phonetic Latin key, and matching happens on those keys.
This is deliberately lossy - it only needs to make same-name variants collide,
not to be a correct transliteration.
"""

import re

_CONSONANTS = {
    "क": "k", "ख": "kh", "ग": "g", "घ": "gh", "ङ": "n",
    "च": "ch", "छ": "ch", "ज": "j", "झ": "jh", "ञ": "n",
    "ट": "t", "ठ": "th", "ड": "d", "ढ": "dh", "ण": "n",
    "त": "t", "थ": "th", "द": "d", "ध": "dh", "न": "n",
    "प": "p", "फ": "ph", "ब": "b", "भ": "bh", "म": "m",
    "य": "y", "र": "r", "ल": "l", "व": "v", "श": "sh",
    "ष": "sh", "स": "s", "ह": "h",
    "क़": "k", "ख़": "kh", "ग़": "g", "ज़": "z", "ड़": "r",
    "ढ़": "rh", "फ़": "f", "य़": "y",
}

_VOWELS = {
    "अ": "a", "आ": "a", "इ": "i", "ई": "i", "उ": "u", "ऊ": "u",
    "ऋ": "ri", "ए": "e", "ऐ": "ai", "ओ": "o", "औ": "au",
    "ा": "a", "ि": "i", "ी": "i", "ु": "u", "ू": "u",
    "ृ": "ri", "े": "e", "ै": "ai", "ो": "o", "ौ": "au",
    "ं": "n", "ँ": "n", "ः": "",
}

_VIRAMA = "्"
_NUKTA = "़"


def _devanagari_to_latin(text: str) -> str:
    out: list[str] = []
    chars = list(text)
    for i, ch in enumerate(chars):
        if ch == _VIRAMA or ch == _NUKTA:
            continue
        if ch in _CONSONANTS:
            out.append(_CONSONANTS[ch])
            nxt = chars[i + 1] if i + 1 < len(chars) else ""
            # Implicit 'a' unless followed by a vowel sign or virama.
            if nxt not in _VOWELS and nxt != _VIRAMA:
                out.append("a")
        elif ch in _VOWELS:
            out.append(_VOWELS[ch])
        else:
            out.append(ch)
    return "".join(out)


def normalize(text: str) -> str:
    """Coarse phonetic key: transliterate, lowercase, strip, collapse."""
    latin = _devanagari_to_latin(text.strip())
    latin = latin.lower()
    latin = re.sub(r"[^a-z]", "", latin)
    # Collapse aspiration so sunita/sunitha collide.
    latin = latin.replace("h", "")
    # Common vowel confusions in romanized Hindi - must run BEFORE collapsing
    # doubles, otherwise "meena" becomes "mena" instead of "mina".
    latin = re.sub(r"ee|ea", "i", latin)
    latin = re.sub(r"oo", "u", latin)
    latin = re.sub(r"(.)\1+", r"\1", latin)
    # Drop trailing 'a' (Sunita vs Sunit reads, ji-suffix noise handled upstream).
    latin = re.sub(r"a$", "", latin)
    return latin


def similarity(a: str, b: str) -> float:
    """Similarity of two already-normalized keys, 0..1."""
    from difflib import SequenceMatcher

    if not a or not b:
        return 0.0
    if a == b:
        return 1.0
    return SequenceMatcher(None, a, b).ratio()
