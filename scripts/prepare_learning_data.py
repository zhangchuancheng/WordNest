#!/usr/bin/env python3
"""Prepare the WNL1 learning resource from the official Open English WordNet.

Python 3.9+; no third-party dependencies. From the project directory:
  python scripts/prepare_learning_data.py --download-dir <temporary-folder>
  python scripts/prepare_learning_data.py --source <english-wordnet-2025.xml.gz>

WNL1: gzip containing ASCII WNL1, int32-BE record count, then seven UTF-8
strings per sense, each prefixed by its int32-BE byte length. Field order:
word, senseId, pos, definition, examples, synonyms, derivations.
List items are joined by LF; internal whitespace is flattened first.
Each example item contains text + TAB + original source/author attribution.
"""

import argparse
from collections import Counter
import gzip
import hashlib
import json
import os
from pathlib import Path
import struct
import urllib.request
import xml.etree.ElementTree as ET

PROJECT = Path(__file__).resolve().parents[1]
REPOSITORY = "https://github.com/globalwordnet/english-wordnet"
COMMIT = "dc343f2683279ecbb13fab4e2fd778d7b162d287"
VERSION = "2025"
SOURCE_URL = "https://en-word.net/static/english-wordnet-2025.xml.gz"
SOURCE_MIRROR = REPOSITORY + "/releases/download/2025-edition/english-wordnet-2025.xml.gz"
SOURCE_SHA256 = "9ca6d1dcb75f822fdd66617f7d9da48142ace38dd544d6ad5e2feca1674ad3fe"
FIELDS = ("word", "senseId", "pos", "definition", "examples", "synonyms", "derivations")
SAMPLES = ("relevant", "research", "analysis", "derive", "classify", "clarify", "preserve", "serendipity")
DC = "{https://globalwordnet.github.io/schemas/dc/}"


def sha256(path):
    value = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def download(url, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    temp = destination.with_name(destination.name + ".download")
    request = urllib.request.Request(url, headers={"User-Agent": "WordNest-learning-data-builder/1.0"})
    try:
        with urllib.request.urlopen(request, timeout=60) as response, temp.open("wb") as output:
            for block in iter(lambda: response.read(1024 * 1024), b""):
                output.write(block)
        os.replace(temp, destination)
    finally:
        if temp.exists():
            temp.unlink()


def text(element):
    return "".join(element.itertext()).strip() if element is not None else ""


def item(value):
    """A multi-value item's own whitespace cannot create a new list item."""
    return " ".join(value.split())


def unique(values):
    return list(dict.fromkeys(value for value in values if value))


def example_value(element):
    content = item(text(element))
    if not content:
        return ""
    attribution = element.get(DC + "source", element.get("source", ""))
    attribution = item(attribution) or "Open English WordNet / Princeton WordNet"
    return content + "\t" + attribution


def parse(source):
    # ElementTree does not fetch the external DTD referenced in this official XML.
    with gzip.open(source, "rb") as compressed:
        root = ET.parse(compressed).getroot()
    lexicon = root.find("Lexicon")
    if lexicon is None or lexicon.get("version") != VERSION:
        raise ValueError("Unexpected WordNet lexicon/version")
    entries = lexicon.findall("LexicalEntry")
    synsets = {synset.get("id"): synset for synset in lexicon.findall("Synset")}
    entry_heads = {}
    sense_heads = {}
    for entry in entries:
        lemma = entry.find("Lemma")
        if lemma is None:
            raise ValueError("Lexical entry without lemma")
        head = item(lemma.get("writtenForm", ""))
        pos = lemma.get("partOfSpeech", "")
        entry_heads[entry.get("id")] = (head, pos)
        for sense in entry.findall("Sense"):
            sense_heads[sense.get("id")] = (head, pos)

    rows = []
    citations = []
    source_example_count = 0
    source_derivation_count = 0
    source_synset_example_count = 0
    missing_targets = []
    for synset_id, synset in synsets.items():
        examples = synset.findall("Example")
        source_synset_example_count += len(examples)
        for example in examples:
            citation = example.get(DC + "source", example.get("source", ""))
            if citation:
                citations.append({"synset_id": synset_id, "example": item(text(example)), "source": citation})

    for entry in entries:
        head, pos = entry_heads[entry.get("id")]
        for sense in entry.findall("Sense"):
            sense_id = sense.get("id")
            synset_id = sense.get("synset")
            synset = synsets.get(synset_id)
            if synset is None:
                raise ValueError(f"Missing synset: {synset_id}")
            # Keep every supplied definition verbatim except surrounding XML whitespace.
            definition = "\n".join(text(node) for node in synset.findall("Definition"))
            examples = unique(example_value(node) for node in synset.findall("Example") + sense.findall("Example"))
            source_example_count += len(sense.findall("Example"))
            for example in sense.findall("Example"):
                citation = example.get(DC + "source", example.get("source", ""))
                if citation:
                    citations.append({"sense_id": sense_id, "example": item(text(example)), "source": citation})
            synonyms = []
            for entry_id in synset.get("members", "").split():
                target = entry_heads.get(entry_id)
                if target is None:
                    missing_targets.append((sense_id, "synset_member", entry_id))
                elif target[0].lower() != head.lower():
                    synonyms.append(target[0])
            derivations = []
            for relation in sense.findall("SenseRelation"):
                if relation.get("relType") != "derivation":
                    continue
                source_derivation_count += 1
                target_id = relation.get("target")
                target = sense_heads.get(target_id)
                if target is None:
                    missing_targets.append((sense_id, "derivation", target_id))
                else:
                    # Preserve cross-POS derivation even if its spelling is identical.
                    derivations.append(target[0])
            rows.append((head, sense_id, pos, definition, "\n".join(examples),
                         "\n".join(sorted(unique(synonyms), key=lambda value: (value.lower(), value))),
                         "\n".join(sorted(unique(derivations), key=lambda value: (value.lower(), value)))))

    if missing_targets:
        raise ValueError(f"Unresolved relation targets: {missing_targets[:10]}")
    rows.sort(key=lambda row: (row[0].lower(), row[1]))
    if len({row[1] for row in rows}) != len(rows):
        raise ValueError("Duplicate sense IDs")
    coverage = {
        "source_lexical_entries": len(entries),
        "source_synsets": len(synsets),
        "source_sense_records": len(rows),
        "source_example_texts": source_synset_example_count + source_example_count,
        "source_example_texts_with_citation": len(citations),
        "source_derivation_links": source_derivation_count,
        "unique_case_insensitive_headwords": len({row[0].lower() for row in rows}),
        "headwords_with_examples": len({row[0].lower() for row in rows if row[4]}),
        "headwords_with_synonyms": len({row[0].lower() for row in rows if row[5]}),
        "headwords_with_derivations": len({row[0].lower() for row in rows if row[6]}),
        "sense_records_with_examples": sum(bool(row[4]) for row in rows),
        "sense_records_with_synonyms": sum(bool(row[5]) for row in rows),
        "sense_records_with_derivations": sum(bool(row[6]) for row in rows),
        "unresolved_relation_targets": len(missing_targets),
        "parts_of_speech": dict(sorted(Counter(row[2] for row in rows).items())),
    }
    return rows, coverage, citations


def write_resource(rows, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    temp = destination.with_name(destination.name + ".tmp")
    try:
        with temp.open("wb") as raw:
            with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0, compresslevel=9) as compressed:
                compressed.write(b"WNL1")
                compressed.write(struct.pack(">i", len(rows)))
                for row in rows:
                    for field in row:
                        encoded = field.encode("utf-8")
                        compressed.write(struct.pack(">i", len(encoded)))
                        compressed.write(encoded)
        os.replace(temp, destination)
    finally:
        if temp.exists():
            temp.unlink()


def validate_resource(rows, destination):
    with gzip.open(destination, "rb") as compressed:
        if compressed.read(4) != b"WNL1":
            raise ValueError("WNL1 magic mismatch")
        if struct.unpack(">i", compressed.read(4))[0] != len(rows):
            raise ValueError("WNL1 record count mismatch")
        for expected in rows:
            decoded = []
            for _ in FIELDS:
                length = struct.unpack(">i", compressed.read(4))[0]
                if length < 0:
                    raise ValueError("Negative field length")
                encoded = compressed.read(length)
                if len(encoded) != length:
                    raise ValueError("Truncated field")
                decoded.append(encoded.decode("utf-8"))
            if tuple(decoded) != expected:
                raise ValueError(f"Round-trip error: {expected[1]}")
        if compressed.read(1):
            raise ValueError("Trailing data after WNL1 records")


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    inputs = parser.add_mutually_exclusive_group(required=True)
    inputs.add_argument("--source", type=Path)
    inputs.add_argument("--download-dir", type=Path)
    parser.add_argument("--output", type=Path, default=PROJECT / "src/main/resources/lexical.bin.gz")
    parser.add_argument("--metadata", type=Path, default=PROJECT / "licenses/lexical-source.json")
    args = parser.parse_args()
    source = args.source
    if args.download_dir is not None:
        source = args.download_dir / "english-wordnet-2025.xml.gz"
        if not source.exists() or sha256(source) != SOURCE_SHA256:
            download(SOURCE_URL, source)
    if not source.is_file() or sha256(source) != SOURCE_SHA256:
        raise ValueError("Source missing or SHA-256 differs from the official 2025 release")

    rows, coverage, citations = parse(source)
    sample_rows = [dict(zip(FIELDS, row)) for row in rows if row[0].lower() in SAMPLES]
    if not all(any(row["word"].lower() == sample for row in sample_rows) for sample in SAMPLES):
        raise ValueError("Missing required validation words")
    write_resource(rows, args.output)
    validate_resource(rows, args.output)
    citation_path = args.metadata.with_name("lexical-example-sources.json")
    write_json(citation_path, {"source": "Open English WordNet 2025", "citations": citations})
    metadata = {
        "name": "Open English WordNet",
        "version": VERSION,
        "repository": REPOSITORY,
        "release_url": REPOSITORY + "/releases/tag/2025-edition",
        "tag_commit": COMMIT,
        "source_url": SOURCE_URL,
        "source_mirror_url": SOURCE_MIRROR,
        "source_sha256": SOURCE_SHA256,
        "source_bytes": source.stat().st_size,
        "license": "CC BY 4.0; Princeton WordNet attribution for underlying material",
        "license_url": "https://creativecommons.org/licenses/by/4.0/",
        "license_files": ["OEWN-LICENSE.txt", "OEWN-WNDB-LICENSE.txt"],
        "attribution": "Derived from Open English WordNet 2025 by the Open English WordNet Team, incorporating Princeton University WordNet. Reformatted and selected for WordNest; not endorsed by the source projects.",
        "source_license_urls": [f"{REPOSITORY}/blob/{COMMIT}/LICENSE.md", f"{REPOSITORY}/blob/{COMMIT}/WNDB_License.txt"],
        "canonical_citation": "John P. McCrae, Alexandre Rademaker, Francis Bond, Ewa Rudnicka and Christiane Fellbaum (2019). English WordNet 2019 - An Open-Source WordNet for English. Proceedings of GWC 2019.",
        "canonical_citation_url": "https://aclanthology.org/2019.gwc-1.31/",
        "example_source_attributions": citation_path.name,
        "format": {
            "container": "deterministic gzip, mtime=0, no stored filename",
            "magic": "WNL1",
            "record_count": "int32 big-endian",
            "fields": list(FIELDS),
            "strings": "int32 big-endian UTF-8 byte length followed by bytes",
            "multivalue_fields": "examples, synonyms and derivations use real LF between items",
            "example_items": "text + TAB + original source/author, or Open English WordNet / Princeton WordNet when source has no per-example attribution",
            "sort_order": "word.lowercase then senseId",
        },
        "transformations": [
            "One record per original lexical sense, retaining original lemma and part of speech.",
            "English definitions retained as supplied, trimming surrounding XML whitespace only.",
            "Examples copied from the sense and its synset; flatten internal whitespace and deduplicate exact text/source repetitions.",
            "Synonyms are other members of the same synset; exclude the query spelling case-insensitively.",
            "Derivations come only from explicit SenseRelation relType=derivation targets; retain target lemma, collapse repetitions within a source sense.",
            "Retain same-spelling cross-POS derivation links; these can appear as the original word in the derived list.",
            "No Chinese example translations, etymology, roots, affix segmentation, guessed relations or generated examples are added.",
            "Per-example original author/source references are retained in each example after TAB and in lexical-example-sources.json; unsourced examples carry Open English WordNet / Princeton WordNet attribution.",
        ],
        "usage_notes": [
            "Examples illustrate a shared sense and may contain another synonym instead of the looked-up headword.",
            "A synset groups words for this sense; synonyms are not universally interchangeable.",
            "Derivational links are not etymological roots or automatically determined parent-child direction.",
            "Missing examples or relations remain empty; absence does not prove none exist in English.",
        ],
        "coverage": coverage,
        "validation": {"all_records_round_trip": True, "sample_words": list(SAMPLES), "sample_records": sample_rows},
        "output_bytes": args.output.stat().st_size,
        "output_sha256": sha256(args.output),
    }
    write_json(args.metadata, metadata)
    print(json.dumps({"coverage": coverage, "output_bytes": metadata["output_bytes"], "output_sha256": metadata["output_sha256"]}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
