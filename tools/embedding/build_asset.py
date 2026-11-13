#!/usr/bin/env python3
"""Builds the static-embedding asset the app reads, from a pinned model2vec model.

Usage (NixOS; anywhere else any Python 3.10+ with numpy will do):

    nix-shell -p 'python3.withPackages (p: [ p.numpy ])' --run \\
      'python3 -I tools/embedding/build_asset.py --download-dir /some/empty/dir'

The source is https://huggingface.co/minishlab/potion-base-8M (MIT licence), a table of one
256-dimensional float vector per BERT WordPiece. Only `model.safetensors` and `tokenizer.json` are
read, both as plain data: the safetensors header is parsed here by hand and nothing from the
download is imported or executed. Each file is checked against the SHA-256 pinned below, so a
changed upstream file stops the build instead of silently producing a different asset.

The output is deterministic: the same inputs give the same bytes. Its SHA-256 is pinned in
`StaticEmbedderAssetTest`, so after any change here that alters the output, bump `MODEL_ID` in
this file and in `StaticEmbedder.kt` (stored vectors are keyed by it) and update that hash.

Asset layout, all little-endian:

    offset  size          field
    0       4             magic "MIEM"
    4       4             format version (1)
    8       4             model id (must equal StaticEmbedder.MODEL_ID)
    12      4             rows (word pieces)
    16      4             dim
    20      4             length of the vocabulary block in bytes
    24      4             id of the unknown token
    28      4             reserved, 0
    32      4 * rows      float32 scale per row
    ...     rows * dim    int8 vectors, row-major; row r dequantises to scale[r] * int8
    ...     vocab bytes   per row, in id order: one length byte, then that many UTF-8 bytes

Quantisation is per row: scale = max(|row|) / 127, value = round(row / scale). Rows differ in
norm by more than an order of magnitude (frequent pieces are deliberately small, that is the
model's built-in weighting), so one global scale would leave the small rows only a few levels.
"""

import argparse
import hashlib
import json
import os
import struct
import sys
import urllib.request

import numpy as np

MODEL_REPO = "minishlab/potion-base-8M"
MODEL_REVISION = "bf8b056651a2c21b8d2565580b8569da283cab23"
SOURCE_URL = "https://huggingface.co/" + MODEL_REPO + "/resolve/" + MODEL_REVISION + "/"

# SHA-256 of each source file at MODEL_REVISION.
SOURCE_FILES = {
    "model.safetensors": "f65d0f325faadc1e121c319e2faa41170d3fa07d8c89abd48ca5358d9a223de2",
    "tokenizer.json": "e67e803f624fb4d67dea1c730d06e1067e1b14d830e2c2202569e3ef0f70bb50",
    # Not read by the build; pinned because it states how the reference normalises its output.
    "config.json": "2a6ac0e9aaa356a68a5688070db78fc3a464fefe85d2f06a1905ce3718687553",
}

MAGIC = b"MIEM"
FORMAT_VERSION = 1
# Keep equal to StaticEmbedder.MODEL_ID. Bump both whenever the asset bytes or the tokenizer change.
MODEL_ID = 1

EXPECTED_ROWS = 29528
EXPECTED_DIM = 256

DEFAULT_OUT = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..", "..", "assets", "embedding", "potion-base-8m.bin",
)


def sha256_of(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def fetch_sources(download_dir):
    """Downloads any missing source file, then verifies every one against its pinned hash."""
    os.makedirs(download_dir, exist_ok=True)
    for name, expected in SOURCE_FILES.items():
        path = os.path.join(download_dir, name)
        if not os.path.exists(path):
            print("fetching", SOURCE_URL + name)
            urllib.request.urlretrieve(SOURCE_URL + name, path)
        actual = sha256_of(path)
        if actual != expected:
            sys.exit("%s: SHA-256 is %s, expected %s" % (path, actual, expected))


def read_safetensors_embeddings(path):
    """Reads the single float32 tensor `embeddings` from a safetensors file.

    The format is an 8-byte little-endian header length, a JSON header, then raw tensor bytes.
    """
    with open(path, "rb") as f:
        (header_len,) = struct.unpack("<Q", f.read(8))
        if header_len > 1 << 20:
            sys.exit("safetensors header is implausibly large")
        header = json.loads(f.read(header_len).decode("utf-8"))
        info = header["embeddings"]
        if info["dtype"] != "F32":
            sys.exit("expected float32 embeddings, found " + info["dtype"])
        rows, dim = info["shape"]
        start, end = info["data_offsets"]
        if end - start != rows * dim * 4:
            sys.exit("tensor size does not match its shape")
        f.seek(8 + header_len + start)
        data = np.frombuffer(f.read(end - start), dtype="<f4")
    return data.reshape(rows, dim)


def read_tokenizer(path):
    """Returns (pieces in id order, unknown-token id), after checking what the Kotlin side assumes."""
    with open(path, encoding="utf-8") as f:
        tok = json.load(f)
    normalizer = tok["normalizer"]
    model = tok["model"]
    assumptions = [
        normalizer["type"] == "BertNormalizer",
        normalizer["clean_text"] is True,
        normalizer["handle_chinese_chars"] is True,
        normalizer["lowercase"] is True,
        # null means "follow lowercase", so accents are stripped.
        normalizer["strip_accents"] in (None, True),
        tok["pre_tokenizer"] == {"type": "BertPreTokenizer"},
        model["type"] == "WordPiece",
        model["continuing_subword_prefix"] == "##",
        model["max_input_chars_per_word"] == 100,
        model["unk_token"] == "[UNK]",
    ]
    if not all(assumptions):
        sys.exit("tokenizer.json is not the BERT uncased WordPiece set-up StaticEmbedder implements")
    vocab = model["vocab"]
    pieces = [None] * len(vocab)
    for piece, index in vocab.items():
        pieces[index] = piece
    if any(p is None for p in pieces):
        sys.exit("vocabulary ids are not 0..n-1")
    return pieces, vocab["[UNK]"]


def quantise(table):
    """Per-row int8 quantisation; returns (scales float32[rows], values int8[rows, dim])."""
    peak = np.abs(table).max(axis=1)
    scales = (peak / 127.0).astype("<f4")
    safe = np.where(scales > 0, scales, 1.0).astype(np.float32)
    values = np.rint(table / safe[:, None]).clip(-127, 127).astype(np.int8)
    return scales, values


def build(download_dir, out_path):
    fetch_sources(download_dir)
    table = read_safetensors_embeddings(os.path.join(download_dir, "model.safetensors"))
    pieces, unk_id = read_tokenizer(os.path.join(download_dir, "tokenizer.json"))
    rows, dim = table.shape
    if (rows, dim) != (EXPECTED_ROWS, EXPECTED_DIM) or len(pieces) != rows:
        sys.exit("unexpected shape: %d x %d vectors, %d pieces" % (rows, dim, len(pieces)))

    scales, values = quantise(table)

    vocab = bytearray()
    for piece in pieces:
        encoded = piece.encode("utf-8")
        if not 0 < len(encoded) < 256:
            sys.exit("piece does not fit a one-byte length: %r" % piece)
        vocab.append(len(encoded))
        vocab += encoded

    header = MAGIC + struct.pack("<7I", FORMAT_VERSION, MODEL_ID, rows, dim, len(vocab), unk_id, 0)
    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    with open(out_path, "wb") as f:
        f.write(header)
        f.write(scales.tobytes())
        f.write(values.tobytes())
        f.write(bytes(vocab))

    row_norm = np.linalg.norm(table, axis=1)
    restored = values.astype(np.float32) * scales[:, None]
    nonzero = row_norm > 0
    row_cos = (table[nonzero] * restored[nonzero]).sum(axis=1) / (
        row_norm[nonzero] * np.linalg.norm(restored[nonzero], axis=1)
    )
    print("source      %s @ %s" % (MODEL_REPO, MODEL_REVISION))
    print("table       %d rows x %d dims, float32" % (rows, dim))
    print("row norms   min %.4f  median %.4f  max %.4f" % (
        row_norm[nonzero].min(), np.median(row_norm[nonzero]), row_norm.max()))
    print("row cosine  float vs int8: min %.6f  mean %.6f" % (row_cos.min(), row_cos.mean()))
    print("asset       %s" % os.path.normpath(out_path))
    print("size        %d bytes (header 32, scales %d, vectors %d, vocabulary %d)" % (
        os.path.getsize(out_path), scales.nbytes, values.nbytes, len(vocab)))
    print("sha256      %s" % sha256_of(out_path))
    print("model id    %d" % MODEL_ID)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--download-dir", required=True,
                        help="where the source files are, or are to be fetched; keep it outside the repo")
    parser.add_argument("--out", default=DEFAULT_OUT, help="asset to write")
    args = parser.parse_args()
    build(args.download_dir, args.out)


if __name__ == "__main__":
    main()
