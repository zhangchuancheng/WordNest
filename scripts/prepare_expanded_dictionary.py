#!/usr/bin/env python3
"""Build the indexed, offline WordNest dictionary from pinned ECDICT sources.

Only the Python standard library is used. Download the upstream release ZIP
explicitly, then run with --archive and --work-dir. No network calls are made.
The original 770,611-entry resource remains the reproducible base source.
"""

import argparse
import array
import gzip
import hashlib
import json
import re
import shutil
import sqlite3
import struct
import sys
import tempfile
import zipfile
from pathlib import Path

PROJECT = Path(__file__).resolve().parents[1]
SOURCE_URL = "https://github.com/skywind3000/ECDICT/releases/download/1.0.28/ecdict-sqlite-28.zip"
ZIP_SHA256 = "ea01f76a3b3351021ce47077e89234465cc9441c8793054495320d06c0c3f3f6"
DB_SHA256 = "2b5b40c2bdba04da0a51c8672e090f166987d5d895f32eb3fbfc5a516455fc75"
BASE_SHA256 = "0eeddb7903a116161a8baef7b448b7673f377910b240003b8486f95e69e5a92a"
FIELDS = ("word", "phonetic", "translation", "definition", "tag", "exchange")
WORD = re.compile(r"[a-z]+(?:['-][a-z]+)*\Z")
PART_BYTES = 40 * 1024 * 1024


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def checked(path, expected):
    actual = sha256(path)
    if actual != expected:
        raise ValueError(f"SHA-256 mismatch for {path.name}: {actual}")


def normalize(value):
    return (value or "").replace("\\n", "\n").strip()


def key_bytes(word):
    # Java String.compareTo compares UTF-16 code units, not Unicode code points.
    return word.lower().encode("utf-16-be")


def pack_record(fields):
    values = [fields[0].lower(), *fields]
    encoded = [value.encode("utf-8") for value in values]
    if any(len(value) > 1_048_576 for value in encoded):
        raise ValueError("Oversized dictionary field")
    return b"".join(struct.pack(">I", len(value)) + value for value in encoded)


def unpack_record(data):
    offset, values = 0, []
    for _ in range(7):
        length = struct.unpack_from(">I", data, offset)[0]
        offset += 4
        values.append(data[offset:offset + length].decode("utf-8"))
        offset += length
    if offset != len(data):
        raise ValueError("Unexpected record bytes")
    return values


def base_records(path):
    with gzip.open(path, "rb") as source:
        if source.read(4) != b"WND1":
            raise ValueError("Base must be the pinned WND1 resource")
        count = struct.unpack(">I", source.read(4))[0]
        for _ in range(count):
            fields = []
            for _ in FIELDS:
                length = struct.unpack(">I", source.read(4))[0]
                fields.append(source.read(length).decode("utf-8"))
            yield tuple(fields)
        if source.read(1):
            raise ValueError("Unexpected base dictionary bytes")


def add_rows(connection, rows, source_name):
    seen = skipped = 0
    batch = []
    before = connection.total_changes
    for raw in rows:
        seen += 1
        fields = tuple(normalize(value) for value in raw)
        if not fields[0]:
            skipped += 1
            continue
        if source_name == "expanded":
            fields = (*fields[:4], (fields[4] + " wn-expanded").strip(), fields[5])
        batch.append((key_bytes(fields[0]), pack_record(fields), source_name,
                      int(bool(fields[1])), int(bool(fields[2])), int(bool(fields[3]))))
        if len(batch) == 10000:
            connection.executemany("INSERT OR IGNORE INTO entries VALUES(?,?,?,?,?,?)", batch)
            batch.clear()
    if batch:
        connection.executemany("INSERT OR IGNORE INTO entries VALUES(?,?,?,?,?,?)", batch)
    connection.commit()
    return {"source_rows": seen, "added": connection.total_changes - before,
            "empty_words_skipped": skipped}


def write_raw(connection, target):
    count = connection.execute("SELECT count(*) FROM entries").fetchone()[0]
    offsets = array.array("I")
    with target.open("wb") as output:
        output.write(b"WND2" + struct.pack(">I", count))
        output.write(b"\0" * ((count + 1) * 4))
        previous = None
        for key, record in connection.execute("SELECT k,record FROM entries ORDER BY k"):
            if previous is not None and key <= previous:
                raise ValueError("Dictionary keys are not strictly sorted")
            previous = key
            offsets.append(output.tell())
            output.write(record)
        offsets.append(output.tell())
        if output.tell() > 2_147_483_647:
            raise ValueError("WND2 exceeds the supported single-map size")
        output.seek(8)
        if sys.byteorder == "little":
            offsets.byteswap()
        offsets.tofile(output)
    return count


def verify_raw(target, connection, expected_count):
    # Compare every serialized record to the normalized, deduplicated source.
    with target.open("rb") as source:
        if source.read(8) != b"WND2" + struct.pack(">I", expected_count):
            raise ValueError("WND2 header mismatch")
        offsets = array.array("I")
        offsets.fromfile(source, expected_count + 1)
        if sys.byteorder == "little":
            offsets.byteswap()
        if offsets[0] != source.tell() or offsets[-1] != target.stat().st_size:
            raise ValueError("WND2 index boundary mismatch")
        for index, (key, record) in enumerate(connection.execute("SELECT k,record FROM entries ORDER BY k")):
            actual = source.read(offsets[index + 1] - offsets[index])
            if actual != record or unpack_record(actual)[0].encode("utf-16-be") != key:
                raise ValueError(f"Round-trip mismatch at record {index}")
        if source.read(1):
            raise ValueError("WND2 trailing bytes")


def write_inflections(connection, output):
    candidates = set()
    for record, in connection.execute("SELECT record FROM entries"):
        values = unpack_record(record)
        headword, exchange = values[0], values[6]
        if not WORD.fullmatch(headword) or len(headword) > 80:
            continue
        for component in exchange.split("/"):
            kind, separator, forms = component.partition(":")
            if not separator or kind not in {"p", "d", "i", "3", "r", "t", "s", "0"}:
                continue
            for form in forms.split(","):
                form = form.strip().lower()
                if not WORD.fullmatch(form) or len(form) > 80 or form == headword:
                    continue
                candidates.add((headword, form) if kind == "0" else (form, headword))
    # Only the destination lemma needs a dictionary entry. Missing inflected
    # headwords are precisely what this index is meant to recover.
    lemmas = {lemma for _, lemma in candidates}
    valid = {lemma for lemma in lemmas if connection.execute(
        "SELECT 1 FROM entries WHERE k=?", (key_bytes(lemma),)).fetchone()}
    pairs = sorted((form, lemma) for form, lemma in candidates if lemma in valid)
    with output.open("wb") as raw:
        with gzip.GzipFile(filename="", fileobj=raw, mode="wb", mtime=0, compresslevel=9) as compressed:
            for form, lemma in pairs:
                compressed.write(f"{form}\t{lemma}\n".encode("utf-8"))
    return {"pairs": len(pairs), "forms": len({form for form, _ in pairs}),
            "lemmas": len(valid), "sha256": sha256(output), "bytes": output.stat().st_size}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", required=True, type=Path)
    parser.add_argument("--work-dir", required=True, type=Path)
    parser.add_argument("--base", type=Path, default=PROJECT / "src/main/resources/dictionary.bin.gz")
    parser.add_argument("--output-dir", type=Path, default=PROJECT / "src/main/resources/dictionary")
    parser.add_argument("--metadata", type=Path, default=PROJECT / "licenses/dictionary-expanded-source.json")
    args = parser.parse_args()
    checked(args.archive, ZIP_SHA256)
    checked(args.base, BASE_SHA256)
    args.work_dir.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix="dictionary-build-", dir=args.work_dir))
    source_path = work / "source.db"
    with zipfile.ZipFile(args.archive) as archive:
        with archive.open("stardict.db") as source, source_path.open("wb") as target:
            shutil.copyfileobj(source, target)
    checked(source_path, DB_SHA256)
    source_db = sqlite3.connect(source_path.resolve().as_uri() + "?mode=ro", uri=True)
    connection = sqlite3.connect(work / "merged.db")
    connection.execute("PRAGMA journal_mode=OFF")
    connection.execute("PRAGMA synchronous=OFF")
    connection.execute("PRAGMA cache_size=-65536")
    connection.execute("CREATE TABLE entries(k BLOB PRIMARY KEY,record BLOB,source TEXT,phonetic INT,translation INT,definition INT) WITHOUT ROWID")
    base_stats = add_rows(connection, base_records(args.base), "base")
    print(json.dumps({"base": base_stats}), flush=True)
    expanded_stats = add_rows(connection, source_db.execute(
        "SELECT " + ",".join(FIELDS) + " FROM stardict ORDER BY id"), "expanded")
    print(json.dumps({"expanded": expanded_stats}), flush=True)
    source_db.close()
    raw_path = work / "dictionary.wnd2"
    count = write_raw(connection, raw_path)
    verify_raw(raw_path, connection, count)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    inflections = write_inflections(connection, args.output_dir / "inflections.tsv.gz")
    coverage = connection.execute("SELECT sum(phonetic),sum(translation),sum(definition) FROM entries").fetchone()
    compressed_path = work / "dictionary.gz"
    with raw_path.open("rb") as source, compressed_path.open("wb") as raw:
        with gzip.GzipFile(filename="", fileobj=raw, mode="wb", compresslevel=9, mtime=0) as compressed:
            shutil.copyfileobj(source, compressed)
    parts = []
    with compressed_path.open("rb") as compressed:
        while chunk := compressed.read(PART_BYTES):
            path = args.output_dir / f"part-{len(parts):03d}.gz"
            path.write_bytes(chunk)
            parts.append({"name": path.name, "bytes": len(chunk), "sha256": sha256(path)})
    raw_hash = sha256(raw_path)
    properties = ["format=WND2", f"entries={count}", f"raw.bytes={raw_path.stat().st_size}",
                  f"raw.sha256={raw_hash}", f"parts={len(parts)}"]
    for index, part in enumerate(parts):
        properties.extend([f"part.{index}={part['name']}", f"part.{index}.sha256={part['sha256']}",
                           f"part.{index}.bytes={part['bytes']}"])
    properties.extend([f"inflections.sha256={inflections['sha256']}", f"inflections.pairs={inflections['pairs']}"])
    (args.output_dir / "manifest.properties").write_text("\n".join(properties) + "\n", encoding="ascii")
    metadata = {
        "name": "WordNest expanded ECDICT dictionary", "release": "0.7.0",
        "source_url": SOURCE_URL, "upstream_tag": "1.0.28", "release_published_at": "2017-09-20",
        "sqlite_asset_uploaded": "2018-05", "archive_sha256": ZIP_SHA256,
        "sqlite_sha256": DB_SHA256, "base_resource_sha256": BASE_SHA256,
        "base_source_metadata": "dictionary-source.json", "base": base_stats, "expanded": expanded_stats,
        "entries": count, "coverage": dict(zip(("phonetic", "chinese_translation", "english_definition"), coverage)),
        "raw_bytes": raw_path.stat().st_size, "raw_sha256": raw_hash, "compressed_bytes": compressed_path.stat().st_size,
        "parts": parts, "inflections": inflections,
        "format": "WND2: int32 magic/count, count+1 absolute int32 offsets, seven length-prefixed UTF-8 fields (key + six original fields)",
        "transformations": [
            "Existing base entries take precedence; add only missing case-insensitive headwords from expanded data.",
            "Expand source literal backslash-n and strip leading/trailing whitespace; preserve original text.",
            "Deduplicate by lowercase spelling and sort keys in Java UTF-16 order.",
            "Append wn-expanded provenance tag only to added expanded records.",
            "Build inflection pairs exclusively from explicit ECDICT exchange p/d/i/3/r/t/s/0 relationships; no stemming or lemma.en.txt.",
            "No automatically generated translations, examples, pronunciation or word origins.",
        ],
        "validation": {"all_records_round_trip": True, "base_precedence": True},
        "licenses": ["ECDICT-LICENSE.txt", "ECDICT-1.0.28-LICENSE.txt"],
        "limitations": "Historical community dictionary includes noisy web glosses, proper names and inflected forms. Entry count does not equal distinct base vocabulary or complete learning-card coverage.",
    }
    args.metadata.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    connection.close()
    print(json.dumps(metadata, ensure_ascii=True, indent=2), flush=True)


if __name__ == "__main__":
    main()
