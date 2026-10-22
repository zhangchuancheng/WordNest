#!/usr/bin/env python3
"""Prepare a separately licensed, offline English etymology relationship table.

Input: droher/etymology-db 2023-12 etymology.csv.gz (CC BY-SA 3.0).
Output: gzip WNE1 + big-endian uint32 count, followed by five length-prefixed
UTF-8 fields per row: word, relation, target, language, sourceURL.
sourceURL is empty: clients generate https://en.wiktionary.org/wiki/{word}.
No spelling-based roots or cognates are inferred. Nested source context is
retained in relation annotations rather than flattened into direct ancestors.
"""

from __future__ import annotations

import argparse
import collections
import csv
import gzip
import hashlib
import itertools
import json
from pathlib import Path
import struct
import sys

PROJECT = Path(__file__).resolve().parents[1]
WORK = PROJECT.parents[1] / "work" / "wordnest-etymology"
SOURCE_URL = "https://github.com/droher/etymology-db/releases/download/2023-12/etymology.csv.gz"
UPSTREAM = "https://github.com/droher/etymology-db"
LICENSE = "CC-BY-SA-3.0"
GROUP_LABELS = {"group_derived_root": "词源链", "group_affix_root": "构词组"}
ALLOWED = {
    "has_root", "root", "borrowed_from", "derived_from", "learned_borrowing_from",
    "inherited_from", "clipping_of", "has_affix", "abbreviation_of", "compound_of",
    "has_prefix_with_root", "has_suffix_with_root", "has_suffix", "has_confix",
    "calque_of", "blend_of", "has_prefix", "back-formation_from", "semantic_loan_of",
    "unadapted_borrowing_from", "named_after", "orthographic_borrowing_from",
    "initialism_of", "phono-semantic_matching_of", "semi_learned_borrowing_from",
    "doublet_with", "etymologically_related_to",
}
ORDERED_RELATIONS = {"has_affix", "has_confix", "compound_of", "blend_of"}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_exact(source, size: int) -> bytes:
    value = source.read(size)
    if len(value) != size:
        raise ValueError("Truncated dictionary")
    return value


def dictionary_words(path: Path) -> set[str]:
    words = set()
    with gzip.open(path, "rb") as source:
        if read_exact(source, 4) != b"WND1":
            raise ValueError("Expected WND1 dictionary")
        count, = struct.unpack(">I", read_exact(source, 4))
        if count > 2_000_000:
            raise ValueError("Implausible dictionary count")
        for _ in range(count):
            for field in range(6):
                length, = struct.unpack(">I", read_exact(source, 4))
                if length > 1024 * 1024:
                    raise ValueError("Implausible dictionary field")
                value = read_exact(source, length)
                if field == 0:
                    words.add(value.decode("utf-8").strip().lower())
        if source.read(1):
            raise ValueError("Trailing dictionary data")
    return words


def annotation(row: dict, groups: dict[str, dict]) -> tuple[str | None, bool]:
    """Return source-preserving group context; None rejects unrelated/broken groups."""
    path = []
    seen = set()
    current = row
    while current["parent_tag"]:
        tag = current["parent_tag"]
        if tag in seen or tag not in groups:
            return None, False
        seen.add(tag)
        parent = groups[tag]
        label = GROUP_LABELS.get(parent["reltype"])
        if label is None:
            # A general comparison group is not an asserted origin/component chain.
            return None, False
        position = current["parent_position"]
        if not position.isdigit():
            return None, False
        path.append(f"{label}第{int(position) + 1}项")
        current = parent
    path.reverse()
    has_context = bool(path)
    if row["reltype"] in ORDERED_RELATIONS and row["position"].isdigit():
        path.append(f"成分序{int(row['position']) + 1}")
    return " / ".join(path), has_context


def build(source_path: Path, words: set[str]):
    rows = []
    counters = collections.Counter()
    type_counts = collections.Counter()
    seen_groups = set()
    with gzip.open(source_path, "rt", encoding="utf-8", newline="") as source:
        reader = csv.DictReader(source)
        required = {"term_id", "lang", "term", "reltype", "related_lang", "related_term",
                    "position", "group_tag", "parent_tag", "parent_position"}
        if not required.issubset(reader.fieldnames or []):
            raise ValueError("Unexpected source columns")
        for term_id, values in itertools.groupby(reader, key=lambda item: item["term_id"]):
            group = list(values)
            counters["source_rows"] += len(group)
            if group[0]["lang"] != "English":
                continue
            counters["english_rows"] += len(group)
            if term_id in seen_groups:
                raise ValueError("Source term groups are not contiguous; cannot safely resolve nested context")
            seen_groups.add(term_id)
            word = group[0]["term"].strip()
            if not word or word.lower() not in words:
                counters["headwords_not_in_dictionary"] += 1
                continue
            groups = {item["group_tag"]: item for item in group if item["group_tag"]}
            for item in group:
                relation = item["reltype"]
                if relation not in ALLOWED or not item["related_term"].strip():
                    counters["excluded_relation_or_empty_target"] += 1
                    continue
                context, structural = annotation(item, groups)
                if context is None or (relation == "etymologically_related_to" and not structural):
                    counters["excluded_broad_or_unresolved_relation"] += 1
                    continue
                if context:
                    relation += " [" + context + "]"
                rows.append((word, sys.intern(relation), item["related_term"].strip(),
                             sys.intern(item["related_lang"]), ""))
                type_counts[item["reltype"]] += 1
    # Stable source order within headwords retains source component ordering.
    rows.sort(key=lambda item: item[0].lower())
    unique = []
    seen = set()
    for row in rows:
        if row not in seen:
            seen.add(row)
            unique.append(row)
    counters["duplicate_rows_removed"] = len(rows) - len(unique)
    counters["output_records"] = len(unique)
    counters["output_headwords"] = len({item[0].lower() for item in unique})
    return unique, counters, type_counts


def write_binary(path: Path, rows: list[tuple]) -> int:
    path.parent.mkdir(parents=True, exist_ok=True)
    expanded = 8
    with path.open("wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", filename="", mtime=0, compresslevel=9) as output:
        output.write(b"WNE1")
        output.write(struct.pack(">I", len(rows)))
        for row in rows:
            for field in row:
                encoded = field.encode("utf-8")
                if len(encoded) > 1024 * 1024:
                    raise ValueError("Oversized output field")
                output.write(struct.pack(">I", len(encoded)))
                output.write(encoded)
                expanded += 4 + len(encoded)
    return expanded


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=WORK / "etymology-2023-12.csv.gz")
    parser.add_argument("--dictionary", type=Path, default=PROJECT / "src/main/resources/dictionary.bin.gz")
    parser.add_argument("--output", type=Path, default=PROJECT / "src/main/resources/etymology.bin.gz")
    parser.add_argument("--metadata", type=Path, default=PROJECT / "src/main/resources/etymology.metadata.json.gz")
    parser.add_argument("--notice", type=Path, default=PROJECT / "licenses/Etymology-data-NOTICE.md")
    args = parser.parse_args()
    words = dictionary_words(args.dictionary)
    rows, counts, types = build(args.source, words)
    expanded = write_binary(args.output, rows)
    metadata = {
        "format": "WNE1", "fields": ["word", "relation", "target", "language", "sourceURL"],
        "source_project": UPSTREAM, "source_release": "2023-12", "source_url": SOURCE_URL,
        "source_sha256": sha256(args.source), "source_compressed_bytes": args.source.stat().st_size,
        "license": LICENSE, "license_url": "https://creativecommons.org/licenses/by-sa/3.0/",
        "attribution": "Wiktionary contributors; droher and etymology-db contributors",
        "dictionary_sha256": sha256(args.dictionary), "statistics": dict(counts),
        "relation_counts_before_deduplication": dict(types), "expanded_bytes": expanded,
        "output_compressed_bytes": args.output.stat().st_size, "output_sha256": sha256(args.output),
        "modifications": [
            "Keep English headwords present in the bundled ECDICT dictionary, ignoring case.",
            "Keep explicitly extracted ancestry and morphology relations; never infer from spelling.",
            "Exclude cognate_of, general comparison groups, and unstructured related-term pointers.",
            "Preserve nested derivation/affix context as Chinese bracket annotations on original relation types.",
            "Group positions are source order, not independently verified chronology or modern English segmentation.",
            "Deduplicate exact rows and sort by lowercased headword; retain source target spelling.",
            "Encode as WNE1 gzip. Leave repeated sourceURL fields empty; generate a Wiktionary headword link in the client.",
        ],
        "source_url_template": "https://en.wiktionary.org/wiki/{percent_encoded_original_headword}#English",
        "caveats": [
            "A 2023 extracted snapshot, not a claim about the current live page; upstream data is not individually validated.",
            "The source has_prefix_with_root is also used for suffix-derived bases; translate it as a morphological base.",
            "Related targets may be historical forms, affixes or reconstructions and need not be present in ECDICT.",
            "Different senses/etymologies can share a headword; do not combine all rows into one certain origin.",
            "Not every source relation is a direct parent; bracketed context must remain visible.",
            "Absence of an entry is missing source coverage, not proof that a word has no etymology.",
        ],
    }
    args.metadata.parent.mkdir(parents=True, exist_ok=True)
    with args.metadata.open("wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", filename="", mtime=0) as output:
        output.write(json.dumps(metadata, ensure_ascii=False, indent=2).encode("utf-8"))
    notice = f"""# Etymology relationship data attribution and license

The separate resource `etymology.bin.gz` is an adapted subset of **etymology-db**,
created by **droher and etymology-db contributors** from **Wiktionary contributors**.
It is distributed under **Creative Commons Attribution-ShareAlike 3.0 Unported
(CC BY-SA 3.0)**. This license applies to the adapted etymology data, separately
from the application code and other independently licensed datasets.

- Upstream project: {UPSTREAM}
- Original dataset release: 2023-12 (upstream generated date: 2023-12-05)
- Exact downloaded asset: {SOURCE_URL}
- Upstream data-license declaration: https://github.com/droher/etymology-db/blob/master/README.md
- License: https://creativecommons.org/licenses/by-sa/3.0/
- Full license text: `Etymology-CC-BY-SA-3.0.txt`
- The upstream code license (Apache 2.0) is **not** the license for this data.
- Source content remains attributable to Wiktionary contributors. Each headword
  links to its English Wiktionary entry; the page history provides contributor history.

## Changes made for WordNest

""" + "\n".join("- " + change for change in metadata["modifications"]) + f"""

No LLM-generated full-dictionary roots, spelling-match cognates or invented
historical paths were added. Chinese relation/context labels are original
editorial translations of the extracted structure, not quotations from Wiktionary.
This is a 2023 snapshot. Current Wiktionary pages may have changed. The upstream
author explicitly says that the extraction results have not been individually
validated. The data and the adaptation are provided without a warranty of accuracy.

## Format and measurements

- Format: gzip containing `WNE1`, big-endian 32-bit count, then five UTF-8 fields
  per row, each preceded by its big-endian 32-bit byte length.
- Fields: word, relation, target, language, sourceURL.
- sourceURL is empty to avoid duplicated storage; derive it as
  `https://en.wiktionary.org/wiki/{{percent_encoded_original_headword}}#English`.
- Rows: {len(rows):,}; unique lookup headwords: {counts['output_headwords']:,}.
- Compressed bytes: {args.output.stat().st_size:,}; expanded bytes: {expanded:,}.
- Source SHA-256: `{metadata['source_sha256']}`
- Bundled dictionary SHA-256: `{metadata['dictionary_sha256']}`
- Output SHA-256: `{metadata['output_sha256']}`

For detailed type counts and limitations, read `etymology.metadata.json.gz`.
To reproduce, use `scripts/prepare_etymology.py` with the exact source asset above
and the bundled `dictionary.bin.gz`. The script never downloads automatically.
"""
    args.notice.parent.mkdir(parents=True, exist_ok=True)
    args.notice.write_text(notice, encoding="utf-8")
    print(json.dumps(metadata, ensure_ascii=False, indent=2))
    for sample in ("relevant", "integrate", "relieve", "elevator", "alleviate", "levitate", "unhappy"):
        print(sample, json.dumps([row for row in rows if row[0].lower() == sample], ensure_ascii=False))


if __name__ == "__main__":
    main()
