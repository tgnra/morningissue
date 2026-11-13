"""A Python copy of the app's text embedding: `EmbeddableText`, `WordPieceTokenizer` and
`StaticEmbedder` (core/src/main/kotlin/net/tgnra/morningissue/embedding/).

It reads the very asset the app ships (`assets/embedding/potion-base-8m.bin`), so
the table and the vocabulary are the same bytes. The rules are ported line by line; where the
Kotlin code relies on a JVM detail (ASCII-only `\\s` in regexes, `Char.isWhitespace`, rounding in
`roundToInt`) the detail is copied too, and commented.

Parity with Kotlin is checked, not assumed: `build_catalogue.py parity` writes
`core/src/test/resources/discovery/parity.json`, and core's `CatalogueParityTest` embeds the
same inputs with the real `StaticEmbedder` and fails below cosine 0.999.
"""

import re
import struct
import unicodedata

import numpy as np

MODEL_ID = 1
DIMENSIONS = 256
MAX_PIECES = 512
MAX_CODE_POINTS = MAX_PIECES * 6
MAX_WORD_CODE_POINTS = 100
MAGIC = b"MIEM"
FORMAT_VERSION = 1

# --- EmbeddableText -------------------------------------------------------------------------------

MAX_WORDS = 200
BODY_CHARS = 2000
MAX_NON_LATIN_SHARE = 0.5
SAME_OPENING_CHARS = 60

# Java's `\s` without UNICODE_CHARACTER_CLASS: ASCII whitespace only. Kotlin's `Regex("\\s+")`.
JAVA_SPACES = re.compile(r"[ \t\n\x0b\f\r]+")

# Kotlin's Char.isWhitespace: Character.isWhitespace or Character.isSpaceChar.
KOTLIN_WHITESPACE = (
    "\t\n\x0b\f\r\x1c\x1d\x1e\x1f          "
    "        　"
)
_KOTLIN_WORD = re.compile("[^" + re.escape(KOTLIN_WHITESPACE) + "]+")


def kotlin_trim(text):
    return text.strip(KOTLIN_WHITESPACE)


def squash(text):
    """`text.trim().replace(Regex("\\s+"), " ")` in Kotlin: trim is Unicode, the regex is ASCII."""
    return JAVA_SPACES.sub(" ", kotlin_trim(text))


def first_words(text, count):
    """`EmbeddableText.firstWords`: [text] up to the end of its [count]th whitespace-free run."""
    for n, match in enumerate(_KOTLIN_WORD.finditer(text), start=1):
        if n == count:
            return text[: match.end()] if match.end() < len(text) else text
    return text


def embeddable_text(title, summary, body):
    """`EmbeddableText.of`."""
    heading = squash(title or "")
    standfirst = squash(summary or "")
    opening = squash(body or "")
    probe = standfirst.rstrip("…. ")[:SAME_OPENING_CHARS]
    if not opening:
        parts = [heading, standfirst]
    elif not probe or opening.startswith(probe):
        parts = [heading, opening]
    else:
        parts = [heading, standfirst, opening]
    return first_words("\n".join(p for p in parts if p), MAX_WORDS)


# Unicode script, which Python's unicodedata does not expose: Latin letters by range, and the
# letters Java files under COMMON (so counted neither way). Anything else that is a letter is
# another script. Close to Character.UnicodeScript for every letter that occurs in practice.
_LATIN_RANGES = (
    (0x41, 0x5A), (0x61, 0x7A), (0xAA, 0xAA), (0xBA, 0xBA), (0xC0, 0xD6), (0xD8, 0xF6),
    (0xF8, 0x2B8), (0x2E0, 0x2E4), (0x1D00, 0x1D25), (0x1D2C, 0x1D5C), (0x1D62, 0x1D65),
    (0x1D6B, 0x1D77), (0x1D79, 0x1DBE), (0x1E00, 0x1EFF), (0x2071, 0x2071), (0x207F, 0x207F),
    (0x2090, 0x209C), (0x212A, 0x212B), (0x2132, 0x2132), (0x214E, 0x214E), (0x2184, 0x2184),
    (0x2C60, 0x2C7F), (0xA722, 0xA787), (0xA78B, 0xA7FF), (0xAB30, 0xAB5A), (0xAB5C, 0xAB64),
    (0xAB66, 0xAB69), (0xFB00, 0xFB06), (0xFF21, 0xFF3A), (0xFF41, 0xFF5A),
)
_COMMON_LETTER_RANGES = (
    (0xB5, 0xB5), (0x2B9, 0x2DF), (0x2E5, 0x2E9), (0x2EC, 0x2FF), (0x374, 0x374),
    (0x2100, 0x2125), (0x2127, 0x2129), (0x212C, 0x2131), (0x2133, 0x214D), (0x214F, 0x214F),
    (0x3006, 0x3006), (0x30FC, 0x30FC), (0xA700, 0xA721), (0xA788, 0xA78A), (0xAB5B, 0xAB5B),
    (0xAB6A, 0xAB6B), (0xFF70, 0xFF70), (0x1D400, 0x1D7FF),
)


def _in_ranges(cp, ranges):
    for lo, hi in ranges:
        if lo <= cp <= hi:
            return True
    return False


_script_cache = {}


def _script_class(ch):
    """1 Latin letter, 2 other-script letter, 0 not counted."""
    cached = _script_cache.get(ch)
    if cached is not None:
        return cached
    cp = ord(ch)
    if not unicodedata.category(ch).startswith("L"):
        value = 0
    elif _in_ranges(cp, _LATIN_RANGES):
        value = 1
    elif _in_ranges(cp, _COMMON_LETTER_RANGES):
        value = 0
    else:
        value = 2
    _script_cache[ch] = value
    return value


def non_latin_share(text):
    latin = other = 0
    for ch in text:
        if ch.isascii():
            if ch.isalpha():
                latin += 1
            continue
        kind = _script_class(ch)
        if kind == 1:
            latin += 1
        elif kind == 2:
            other += 1
    letters = latin + other
    return 0.0 if letters == 0 else other / letters


def is_mostly_non_latin(text):
    return non_latin_share(text) > MAX_NON_LATIN_SHARE


# --- WordPieceTokenizer + StaticEmbedder ------------------------------------------------------------

_SPACES = {0x20, 0x09, 0x0A, 0x0D, 0xA0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000}
_REMOVED_CATEGORIES = {"Cc", "Cf", "Co", "Cs", "Mn"}
_PUNCT_CATEGORIES = {"Pc", "Pd", "Ps", "Pe", "Pi", "Pf", "Po"}


def _is_space(cp):
    return cp in _SPACES or 0x2000 <= cp <= 0x200A


def _is_punctuation(cp):
    if cp < 0x80:
        return 33 <= cp <= 47 or 58 <= cp <= 64 or 91 <= cp <= 96 or 123 <= cp <= 126
    return unicodedata.category(chr(cp)) in _PUNCT_CATEGORIES


def _is_cjk(cp):
    return (0x4E00 <= cp <= 0x9FFF or 0x3400 <= cp <= 0x4DBF or 0xF900 <= cp <= 0xFAFF
            or 0x20000 <= cp <= 0x2A6DF or 0x2A700 <= cp <= 0x2B73F or 0x2B740 <= cp <= 0x2B81F
            or 0x2B920 <= cp <= 0x2CEAF or 0x2F800 <= cp <= 0x2FA1F)


def _lower(ch):
    """Java's Character.toLowerCase(int): the simple, one-to-one mapping."""
    low = ch.lower()
    return low if len(low) == 1 else ch


class StaticEmbedder:
    def __init__(self, asset_path):
        with open(asset_path, "rb") as f:
            blob = f.read()
        if blob[:4] != MAGIC:
            raise ValueError("not an embedding asset: " + asset_path)
        version, model_id, rows, dim, vocab_bytes, unk_id, _ = struct.unpack_from("<7I", blob, 4)
        if version != FORMAT_VERSION or model_id != MODEL_ID or dim != DIMENSIONS:
            raise ValueError("embedding asset is format %d model %d dim %d" % (version, model_id, dim))
        if len(blob) != 32 + rows * (4 + dim) + vocab_bytes:
            raise ValueError("embedding asset size does not match its header")
        self.scales = np.frombuffer(blob, dtype="<f4", count=rows, offset=32).astype(np.float32)
        self.table = np.frombuffer(blob, dtype=np.int8, count=rows * dim, offset=32 + 4 * rows).reshape(rows, dim)
        self.unknown_id = unk_id
        self.vocab = {}
        at = 32 + rows * (4 + dim)
        longest = 0
        for piece_id in range(rows):
            length = blob[at]
            piece = blob[at + 1: at + 1 + length].decode("utf-8")
            at += 1 + length
            self.vocab.setdefault(piece, piece_id)
            longest = max(longest, len(piece))
        self.max_piece_length = longest
        self._char_map = {}
        self._word_cache = {}

    # Cleaning, accents, case and splitting reduce to a per-character mapping, done with
    # str.translate: a space becomes " ", a removed character "", punctuation and CJK
    # " c " (a word of its own), anything else its lower case.
    def _map_char(self, ch):
        cp = ord(ch)
        if _is_space(cp):
            return " "
        if cp == 0 or cp == 0xFFFD or unicodedata.category(ch) in _REMOVED_CATEGORIES:
            return ""
        low = _lower(ch)
        lcp = ord(low)
        if _is_punctuation(lcp) or _is_cjk(lcp):
            return " " + low + " "
        return low

    def _word_pieces(self, word):
        cached = self._word_cache.get(word)
        if cached is not None:
            return cached
        if len(word) > MAX_WORD_CODE_POINTS:
            pieces = (self.unknown_id,)
        else:
            out = []
            start = 0
            n_word = len(word)
            while start < n_word:
                prefix = "##" if start > 0 else ""
                longest = min(n_word - start, self.max_piece_length - len(prefix))
                found = -1
                for n in range(longest, 0, -1):
                    piece_id = self.vocab.get(prefix + word[start:start + n])
                    if piece_id is not None:
                        found = piece_id
                        start += n
                        break
                if found < 0:
                    out = [self.unknown_id]
                    break
                out.append(found)
            pieces = tuple(out)
        if len(self._word_cache) > 2_000_000:
            self._word_cache.clear()
        self._word_cache[word] = pieces
        return pieces

    def tokenize(self, text, max_pieces=MAX_PIECES):
        if any(ord(c) >= 0xC0 for c in text):
            text = unicodedata.normalize("NFD", text)
        for c in set(text):
            if ord(c) not in self._char_map:
                self._char_map[ord(c)] = self._map_char(c)
        mapped = text.translate(self._char_map)
        ids = []
        for word in mapped.split(" "):
            if not word:
                continue
            if len(ids) == max_pieces:
                break
            pieces = self._word_pieces(word)
            room = max_pieces - len(ids)
            ids.extend(pieces[:room])
        return ids

    def piece_ids(self, text):
        cut = text[:MAX_CODE_POINTS]
        return [i for i in self.tokenize(cut, MAX_PIECES) if i != self.unknown_id]

    def embed(self, text):
        """The unit float32 vector of [text], or None when it has no known piece."""
        ids = self.piece_ids(text)
        if not ids:
            return None
        rows = self.table[ids].astype(np.float32) * self.scales[ids, None]
        total = rows.sum(axis=0, dtype=np.float32)
        return normalise(total)


def normalise(vector):
    """Unit length, computed in double as `StaticEmbedder.normalise`; None for a zero vector."""
    squares = float(np.dot(vector.astype(np.float64), vector.astype(np.float64)))
    if squares == 0.0:
        return None
    return (vector * np.float32(1.0 / np.sqrt(squares))).astype(np.float32)


def to_bytes(vector):
    """`StaticEmbedder.toBytes`: the largest component to 127, rounded half up (roundToInt)."""
    vector = np.asarray(vector, dtype=np.float32)
    peak = float(np.abs(vector).max())
    if peak == 0.0 or not np.isfinite(peak):
        return np.zeros(DIMENSIONS, dtype=np.int8)
    gain = np.float32(127.0) / np.float32(peak)
    return np.clip(np.floor(vector * gain + np.float32(0.5)), -127, 127).astype(np.int8)


def from_bytes(values):
    """`StaticEmbedder.fromBytes`: the unit vector a stored row stands for."""
    vector = np.asarray(values, dtype=np.float32)
    unit = normalise(vector)
    return unit if unit is not None else vector


def stored_vector(embedder, title, summary, body):
    """What `EmbeddingBackfill.stored` keeps for a post, as the vector the app reads back
    (`StoredEmbedding.vectorOf`), or None for a post the app marks as having no vector."""
    text = embeddable_text(title, summary, body)
    if is_mostly_non_latin(text):
        return None
    vector = embedder.embed(text)
    if vector is None:
        return None
    return from_bytes(to_bytes(vector))
