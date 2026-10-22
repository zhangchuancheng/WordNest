"""Project fixed offline dictionaries onto the selected expansion words for editorial checks."""
import csv
import gzip
import json
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read_binary(path, fields):
    data = gzip.decompress(path.read_bytes())
    count = struct.unpack_from(">I", data, 4)[0]
    offset = 8
    for _ in range(count):
        row = []
        for _ in range(fields):
            size = struct.unpack_from(">I", data, offset)[0]
            offset += 4
            row.append(data[offset:offset + size].decode("utf-8"))
            offset += size
        yield row
    if offset != len(data):
        raise ValueError("Trailing dictionary data")


def main():
    manifest = json.loads((ROOT / "data/learning/priority-v03.json").read_text(encoding="utf-8"))
    words = {w for group in manifest["groups"].values() for w in group}
    entries = {w: {"word": w, "lexical": [], "origins": []} for w in words}
    with Path(sys.argv[1]).open(encoding="utf-8-sig", newline="") as f:
        for row in csv.DictReader(f):
            key = row["word"].lower()
            if key in entries:
                entries[key]["dictionary"] = {k: row[k] for k in ("translation", "definition", "tag", "bnc", "frq", "exchange")}
    for row in read_binary(ROOT / "src/main/resources/lexical.bin.gz", 7):
        if row[0] in entries:
            entries[row[0]]["lexical"].append(dict(zip(("id", "pos", "definition", "examples", "synonyms", "derivations"), row[1:])))
    for row in read_binary(ROOT / "src/main/resources/etymology.bin.gz", 5):
        if row[0] in entries:
            entries[row[0]]["origins"].append(row[1:])
    output = Path(sys.argv[2])
    output.mkdir(parents=True, exist_ok=True)
    for group, group_words in manifest["groups"].items():
        (output / f"reference-{group}.json").write_text(json.dumps([entries[w] for w in group_words], ensure_ascii=False, indent=2), encoding="utf-8")
    priority = [{"word": w, **{k: entries[w].get("dictionary", {}).get(k, "") for k in ("tag", "bnc", "frq")}} for w in sorted(words)]
    (ROOT / "data/learning/priority-evidence.json").write_text(json.dumps(priority, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"selected": len(words), "missing": sorted(w for w in words if "dictionary" not in entries[w]),
                      "ielts_tagged": sum("ielts" in entries[w].get("dictionary", {}).get("tag", "").split() for w in words)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
