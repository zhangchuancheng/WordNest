#!/usr/bin/env python3
"""Build WordNest's deterministic offline dictionary using only Python's standard library.

Python 3.9+ usage (run from the project directory):
    python scripts/prepare_dictionary.py --download-dir <temporary-data-folder>
    python scripts/prepare_dictionary.py --source <path-to-ecdict.csv>

The source is pinned to an ECDICT commit and verified by SHA-256. Files are
written only after input validation; no packages or services are required.
"""

import argparse
import csv
import gzip
import hashlib
import json
import os
from pathlib import Path
import struct
import urllib.request

COMMIT = "bc015ed2e24a7abef49fc6dbbb7fe32c1dadaf8b"
REPOSITORY = "https://github.com/skywind3000/ECDICT"
SOURCE_URL = f"https://raw.githubusercontent.com/skywind3000/ECDICT/{COMMIT}/ecdict.csv"
LICENSE_URL = f"https://raw.githubusercontent.com/skywind3000/ECDICT/{COMMIT}/LICENSE"
SOURCE_SHA256 = "1a6947e04785db63613a92e14903cdae7954f7e84860b10e68e5c7cbb3f9c3cf"
FIELDS = ("word", "phonetic", "translation", "definition", "tag", "exchange")
CHECK_WORDS = (
    "integrate", "preserve", "preserved", "classify", "clarify", "serendipity",
    "algorithm", "hello", "perceive", "gave", "teeth", "undo",
)
PROJECT = Path(__file__).resolve().parents[1]


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download(url, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_name(path.name + ".download")
    request = urllib.request.Request(url, headers={"User-Agent": "WordNest-dictionary-builder/1.0"})
    try:
        with urllib.request.urlopen(request, timeout=60) as response, temp.open("wb") as handle:
            for chunk in iter(lambda: response.read(1024 * 1024), b""):
                handle.write(chunk)
        os.replace(temp, path)
    finally:
        if temp.exists():
            temp.unlink()


def normalized(value):
    # ECDICT stores meaning breaks as two literal characters (backslash + n).
    return (value or "").replace("\\n", "\n").strip()


def information_score(entry):
    values = entry[1:]
    # Prefer the record with more populated fields, then more available detail.
    return sum(bool(value) for value in values), sum(len(value) for value in values)


def load_entries(source):
    entries = {}
    source_rows = 0
    skipped_empty_words = 0
    duplicates = 0
    with source.open("r", encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle)
        missing = set(FIELDS) - set(reader.fieldnames or ())
        if missing:
            raise ValueError(f"Missing source columns: {sorted(missing)}")
        for row in reader:
            source_rows += 1
            if None in row:
                raise ValueError(f"Malformed CSV record {source_rows + 1}: extra columns")
            entry = tuple(normalized(row.get(field)) for field in FIELDS)
            key = entry[0].lower()
            if not key:
                skipped_empty_words += 1
                continue
            previous = entries.get(key)
            if previous is not None:
                duplicates += 1
                if information_score(entry) <= information_score(previous):
                    continue
            entries[key] = entry
    return entries, source_rows, skipped_empty_words, duplicates


def write_dictionary(entries, target):
    target.parent.mkdir(parents=True, exist_ok=True)
    temp = target.with_name(target.name + ".tmp")
    try:
        with temp.open("wb") as raw:
            # Suppress file name and timestamps so repeated builds are byte-identical.
            with gzip.GzipFile(filename="", mode="wb", fileobj=raw, compresslevel=9, mtime=0) as output:
                output.write(b"WND1")
                output.write(struct.pack(">i", len(entries)))
                for key in sorted(entries):
                    for field in entries[key]:
                        data = field.encode("utf-8")
                        output.write(struct.pack(">i", len(data)))
                        output.write(data)
        os.replace(temp, target)
    finally:
        if temp.exists():
            temp.unlink()


def verify_dictionary(target, entries):
    with gzip.open(target, "rb") as handle:
        if handle.read(4) != b"WND1":
            raise ValueError("Dictionary magic mismatch")
        count = struct.unpack(">i", handle.read(4))[0]
        if count != len(entries):
            raise ValueError("Dictionary count mismatch")
        for key in sorted(entries):
            actual = []
            for field in FIELDS:
                size = struct.unpack(">i", handle.read(4))[0]
                if size < 0:
                    raise ValueError("Negative field size")
                data = handle.read(size)
                if len(data) != size:
                    raise ValueError("Truncated field")
                actual.append(data.decode("utf-8"))
            if tuple(actual) != entries[key]:
                raise ValueError(f"Round-trip mismatch for {key}")
        if handle.read(1):
            raise ValueError("Unexpected trailing dictionary bytes")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    inputs = parser.add_mutually_exclusive_group(required=True)
    inputs.add_argument("--source", type=Path, help="Existing pinned ECDICT CSV")
    inputs.add_argument("--download-dir", type=Path, help="Explicit directory for original downloaded CSV")
    parser.add_argument("--output", type=Path, default=PROJECT / "src/main/resources/dictionary.bin.gz")
    parser.add_argument("--metadata", type=Path, default=PROJECT / "licenses/dictionary-source.json")
    args = parser.parse_args()

    source = args.source
    if args.download_dir is not None:
        source = args.download_dir / "ecdict.csv"
        if not source.is_file() or sha256(source) != SOURCE_SHA256:
            download(SOURCE_URL, source)
    if not source.is_file():
        parser.error(f"Source file does not exist: {source}")
    actual_hash = sha256(source)
    if actual_hash != SOURCE_SHA256:
        raise ValueError(f"Input SHA-256 mismatch: expected {SOURCE_SHA256}, got {actual_hash}")

    entries, source_rows, empty_words, duplicates = load_entries(source)
    missing = [word for word in CHECK_WORDS if word not in entries]
    if missing:
        raise ValueError(f"Expected validation entries absent: {missing}")
    write_dictionary(entries, args.output)
    verify_dictionary(args.output, entries)

    metadata = {
        "name": "ECDICT",
        "repository": REPOSITORY,
        "commit": COMMIT,
        "source_url": SOURCE_URL,
        "source_filename": "ecdict.csv",
        "source_sha256": actual_hash,
        "source_bytes": source.stat().st_size,
        "original_data_rows": source_rows,
        "exported_entries": len(entries),
        "discarded_empty_headwords": empty_words,
        "duplicate_case_insensitive_rows": duplicates,
        "license": "MIT",
        "license_url": LICENSE_URL,
        "license_file": "ECDICT-LICENSE.txt",
        "copyright": "Copyright (c) 2025 Linwei",
        "format": {
            "container": "gzip, deterministic mtime=0 and no original filename",
            "magic": "WND1",
            "record_count": "signed int32 big-endian",
            "fields_in_order": list(FIELDS),
            "string_encoding": "int32 big-endian byte length followed by UTF-8 bytes",
        },
        "transformations": [
            "Parse the original UTF-8 CSV with Python csv.DictReader.",
            "Expand literal backslash-n in each retained field and strip leading/trailing whitespace.",
            "Retain original headword spelling; use its lowercase form for deduplication and sort order.",
            "On duplicate keys prefer more populated fields, then longer content, then the first row.",
            "Retain phrases and inflected forms. No translations, pronunciations or definitions are generated.",
            "Serialize six original fields; leave missing source fields empty.",
        ],
        "coverage": {
            "entries_with_chinese_translation": sum(bool(e[2]) for e in entries.values()),
            "entries_with_phonetic": sum(bool(e[1]) for e in entries.values()),
            "entries_with_english_definition": sum(bool(e[3]) for e in entries.values()),
        },
        "validation": {
            "round_trip_all_records": True,
            "sample_words_present": list(CHECK_WORDS),
        },
        "output_bytes": args.output.stat().st_size,
        "output_sha256": sha256(args.output),
    }
    args.metadata.parent.mkdir(parents=True, exist_ok=True)
    args.metadata.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: metadata[key] for key in (
        "original_data_rows", "exported_entries", "duplicate_case_insensitive_rows",
        "coverage", "output_bytes", "output_sha256")}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
