#!/usr/bin/env python3
"""Writes the reference the Kotlin tests compare against, and measures quantisation loss.

Usage, after build_asset.py has run with the same download directory:

    nix-shell -p 'python3.withPackages (p: [ p.numpy p.tokenizers ])' --run \\
      'python3 -I tools/embedding/make_reference.py --download-dir /some/dir'

Tokenization here is done by Hugging Face's `tokenizers` library reading the model's own
`tokenizer.json`, which is what model2vec uses, so the piece IDs written out are the reference's
and not a second copy of the Kotlin logic. The embedding steps are model2vec's `StaticModel.encode`
with its defaults (model2vec commit 253d598, `model2vec/model.py`), written out by hand because
the package itself is not needed for them:

    text = text[:max_length * median_piece_length]      # 512 * 6 characters
    ids = tokenizer.encode(text, add_special_tokens=False), truncated to max_length = 512
    ids = [i for i in ids if i != unk_id]               # unknown words are dropped
    vector = table[ids].mean(axis=0)                    # plain mean, no weights in this model
    vector /= norm(vector)                              # config.json: "normalize": true

The vectors written here were checked once against the package itself (model2vec 0.10.0 from
PyPI, `StaticModel.from_pretrained(download_dir).encode(texts)`): the largest difference in any
component was 5e-7, which is the six-decimal rounding of the file.

Outputs:

- `app/src/test/resources/embedding/reference.json`: for each sentence under "reference" in
  cases.json its piece IDs and float32 reference vector; for each under "tokenizer" (plus two
  generated over-length texts) its piece IDs.
- On stdout, how far the app's int8 table moves a text's vector from the float one: cosine
  between the two over every text in cases.json and every paragraph of the repo's own
  docs/how-it-works.md and todo.md. Those numbers are a report, not a test, and shift a little
  when the docs change.
"""

import argparse
import json
import os
import struct
import sys

import numpy as np
from tokenizers import Tokenizer

sys.dont_write_bytecode = True  # no __pycache__ in the repo
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_asset  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, "..", ".."))
DEFAULT_OUT = os.path.join(REPO, "app", "src", "test", "resources", "embedding", "reference.json")

MAX_PIECES = 512


def read_asset(path):
    """Reads the built asset back: (scales, int8 table)."""
    with open(path, "rb") as f:
        blob = f.read()
    if blob[:4] != build_asset.MAGIC:
        sys.exit("not an embedding asset: " + path)
    _, _, rows, dim, _, _, _ = struct.unpack_from("<7I", blob, 4)
    scales = np.frombuffer(blob, dtype="<f4", count=rows, offset=32)
    values = np.frombuffer(blob, dtype=np.int8, count=rows * dim, offset=32 + 4 * rows)
    return scales, values.reshape(rows, dim)


def unit(vector):
    return vector / (np.linalg.norm(vector) + 1e-32)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--download-dir", required=True, help="the directory build_asset.py used")
    parser.add_argument("--asset", default=build_asset.DEFAULT_OUT)
    parser.add_argument("--out", default=DEFAULT_OUT)
    args = parser.parse_args()

    build_asset.fetch_sources(args.download_dir)
    table = build_asset.read_safetensors_embeddings(os.path.join(args.download_dir, "model.safetensors"))
    pieces, unk_id = build_asset.read_tokenizer(os.path.join(args.download_dir, "tokenizer.json"))
    tokenizer = Tokenizer.from_file(os.path.join(args.download_dir, "tokenizer.json"))
    tokenizer.no_padding()
    tokenizer.enable_truncation(MAX_PIECES)
    median_piece_length = int(np.median([len(p) for p in pieces]))
    max_chars = MAX_PIECES * median_piece_length

    def piece_ids(text):
        ids = tokenizer.encode(text[:max_chars], add_special_tokens=False).ids
        return [i for i in ids if i != unk_id]

    scales, values = read_asset(args.asset)
    quantised = values.astype(np.float32) * scales[:, None]
    global_scale = np.abs(table).max() / 127.0
    global_table = np.rint(table / global_scale).astype(np.float32)

    with open(os.path.join(HERE, "cases.json"), encoding="utf-8") as f:
        cases = json.load(f)

    # Two texts past the limits: one cut by the character limit (long words, few pieces), one by
    # the piece limit (single letters and punctuation make a piece per two characters).
    long_prose = " ".join("international negotiations continued throughout september" for _ in range(100))
    long_pieces = " ".join("a, b. c; d: e!" for _ in range(200))
    tokenizer_cases = cases["tokenizer"] + [long_prose, long_pieces]

    reference = {
        "model": build_asset.MODEL_REPO,
        "revision": build_asset.MODEL_REVISION,
        "modelId": build_asset.MODEL_ID,
        "maxPieces": MAX_PIECES,
        "maxChars": max_chars,
        "embeddings": [],
        "tokenizer": [{"text": t, "ids": piece_ids(t)} for t in tokenizer_cases],
    }
    for text in cases["reference"]:
        ids = piece_ids(text)
        vector = unit(table[ids].mean(axis=0))
        reference["embeddings"].append({
            "text": text,
            "ids": ids,
            "vector": [round(float(v), 6) for v in vector],
        })
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(reference, f, ensure_ascii=True, separators=(",", ":"))
        f.write("\n")
    print("reference   %s (%d embeddings, %d tokenizer cases, %d bytes)" % (
        os.path.relpath(args.out, REPO), len(reference["embeddings"]),
        len(reference["tokenizer"]), os.path.getsize(args.out)))
    print("limits      %d pieces, %d characters (median piece length %d)" % (
        MAX_PIECES, max_chars, median_piece_length))

    corpus = list(cases["reference"]) + list(cases["corpus"]) + list(cases["tokenizer"])
    for name in ("docs/how-it-works.md", "todo.md"):
        with open(os.path.join(REPO, name), encoding="utf-8") as f:
            corpus += [p.strip() for p in f.read().split("\n\n") if len(p.strip()) > 20]

    per_row, one_scale, stored, lengths = [], [], [], []
    for text in corpus:
        ids = piece_ids(text)
        if not ids:
            continue
        exact = unit(table[ids].mean(axis=0))
        approx = unit(quantised[ids].mean(axis=0))
        per_row.append(float(exact @ approx))
        one_scale.append(float(exact @ unit(global_table[ids].mean(axis=0))))
        # What the app stores per post: the int8-table vector, itself cut to int8 (toBytes).
        compact = np.rint(approx * (127.0 / np.abs(approx).max()))
        stored.append(float(exact @ unit(compact)))
        lengths.append(len(ids))

    def line(label, cosines):
        c = np.array(cosines)
        print("%s min %.6f  p1 %.6f  median %.6f  mean %.6f" % (
            label, c.min(), np.percentile(c, 1), np.median(c), c.mean()))

    print("corpus      %d texts with a known piece (of %d); pieces per text: min %d, median %d, max %d" % (
        len(per_row), len(corpus), min(lengths), int(np.median(lengths)), max(lengths)))
    line("cosine, float table vs int8 table (per-row scale, the asset):", per_row)
    line("cosine, float table vs int8 table (one global scale, rejected):", one_scale)
    line("cosine, float table vs stored form (int8 table, then toBytes): ", stored)

    # Pairwise similarities are what ranking uses: how far do they move?
    rng = np.random.default_rng(0)
    exact_all, approx_all = [], []
    for text in corpus:
        ids = piece_ids(text)
        if ids:
            exact_all.append(unit(table[ids].mean(axis=0)))
            approx = unit(quantised[ids].mean(axis=0))
            approx_all.append(unit(np.rint(approx * (127.0 / np.abs(approx).max()))))
    exact_all, approx_all = np.array(exact_all), np.array(approx_all)
    a = rng.integers(0, len(exact_all), 20000)
    b = rng.integers(0, len(exact_all), 20000)
    keep = a != b
    diff = np.abs((exact_all[a] * exact_all[b]).sum(axis=1) - (approx_all[a] * approx_all[b]).sum(axis=1))[keep]
    print("pairwise similarity, float vs stored form, %d random pairs: mean |error| %.5f, max %.5f" % (
        keep.sum(), diff.mean(), diff.max()))


if __name__ == "__main__":
    main()
