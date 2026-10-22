"""Validate and assemble editorial learning batches. Standard library only; no network."""
import argparse
import collections
import hashlib
import json
import re
from pathlib import Path
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "data/learning"
RESOURCE = ROOT / "src/main/resources/learning-curated.tsv"
KINDS = {"example", "collocation", "derived", "synonym", "etymology", "morphology", "root_related", "source"}
CJK = re.compile(r"[\u3400-\u9fff]")
WORD = re.compile(r"^[a-z]+$")
# Reviewed normal inflections in the retained 0.2 examples. New unmatched forms
# must be reviewed explicitly instead of passing a broad prefix/stemming guess.
APPROVED_FORMS = {
    "algorithm": ("algorithms",), "classify": ("classifies", "classified"),
    "derive": ("derives",), "integrate": ("integrated",),
    "preserve": ("preserves",), "research": ("researched",),
}


def read_rows(path):
    rows = []
    for number, line in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
        if not line or line.startswith("#"):
            continue
        fields = line.split("\t")
        if len(fields) != 5 or any(not f.strip() for f in fields):
            raise ValueError(f"{path.name}:{number}: expected five nonempty fields")
        if any(f != f.strip() for f in fields):
            raise ValueError(f"{path.name}:{number}: unexpected field whitespace")
        kind, word, first, second, third = fields
        if kind not in KINDS or not WORD.fullmatch(word):
            raise ValueError(f"{path.name}:{number}: invalid kind or normalized word")
        if kind in {"example", "collocation", "derived", "etymology", "morphology", "root_related"} and not CJK.search(second):
            raise ValueError(f"{word}: missing Chinese {kind} content")
        if kind in {"synonym", "derived", "collocation", "source"} and not CJK.search(third):
            raise ValueError(f"{word}: missing Chinese explanation for {kind}")
        if kind in {"source", "etymology", "morphology"}:
            url = second if kind == "source" else third
            parsed = urlparse(url)
            if parsed.scheme != "https" or not parsed.netloc or parsed.username:
                raise ValueError(f"{word}: invalid source URL {url!r}")
        if kind == "example" and len(re.findall(r"[A-Za-z]+", first)) < 6:
            raise ValueError(f"{word}: example is too short")
        if kind in {"derived", "synonym", "root_related"} and first.casefold() == word:
            raise ValueError(f"{word}: self relation")
        rows.append(fields)
    if len({tuple(r) for r in rows}) != len(rows):
        raise ValueError(f"{path.name}: duplicate rows")
    return rows


def inspect(rows, minimums):
    by_word = collections.defaultdict(list)
    for row in rows:
        by_word[row[1]].append(row)
    for word, items in by_word.items():
        counts = collections.Counter(row[0] for row in items)
        for kind, minimum in minimums.items():
            if counts[kind] < minimum:
                raise ValueError(f"{word}: needs {minimum} {kind}; found {counts[kind]}")
        if counts["morphology"] + counts["etymology"] < 1:
            raise ValueError(f"{word}: no root / morphology explanation")
        for kind in ("example", "collocation", "derived", "synonym"):
            contents = [row[2].casefold() for row in items if row[0] == kind]
            if len(contents) != len(set(contents)):
                raise ValueError(f"{word}: repeated {kind}")
        source_urls = {row[3] for row in items if row[0] == "source"}
        for row in items:
            if row[0] in {"morphology", "etymology"} and row[4] not in source_urls:
                raise ValueError(f"{word}: root reference absent from source rows: {row[4]}")
    return by_word


def positive_rank(value, limit):
    try:
        return 0 < int(value) <= limit
    except (TypeError, ValueError):
        return False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Verify generated outputs without changing them")
    args = parser.parse_args()
    manifest = json.loads((DATA / "priority-v03.json").read_text(encoding="utf-8"))
    rows = read_rows(DATA / "base-v02.tsv")
    base = inspect(rows, {"example": 2, "collocation": 2, "derived": 1, "synonym": 2, "source": 1})
    seen = set(base)
    group_counts = {}
    for group, wanted in manifest["groups"].items():
        batch = read_rows(DATA / "batches" / f"{group}.tsv")
        current = inspect(batch, {"example": 2, "collocation": 2, "derived": 2, "synonym": 2, "source": 1})
        if set(current) != set(wanted):
            raise ValueError(f"{group}: missing={set(wanted) - set(current)}, unexpected={set(current) - set(wanted)}")
        if seen & set(current):
            raise ValueError(f"{group}: duplicate word across batches")
        seen.update(current)
        group_counts[group] = len(current)
        rows.extend(batch)
    # Stable sort keeps the editorial order inside each word and is reproducible.
    rows.sort(key=lambda row: row[1])
    by_word = inspect(rows, {"example": 2, "collocation": 2, "derived": 1, "synonym": 2, "source": 1})
    payload = ("\n".join("\t".join(row) for row in rows) + "\n").encode("utf-8")
    counts = dict(sorted(collections.Counter(row[0] for row in rows).items()))
    evidence = json.loads((DATA / "priority-evidence.json").read_text(encoding="utf-8"))
    examples = [row[2] for row in rows if row[0] == "example"]
    repeated = sorted(s for s, n in collections.Counter(examples).items() if n > 1)
    if repeated:
        raise ValueError(f"Repeated full example sentences across words: {repeated}")
    # A review flag, not a spelling heuristic masquerading as linguistic proof.
    target_review = [{"word": row[1], "example": row[2]} for row in rows if row[0] == "example"
                     and not re.search(r"\b" + re.escape(row[1]) + r"\b", row[2], re.I)]
    for item in target_review:
        forms = [form for form in APPROVED_FORMS.get(item["word"], ())
                 if re.search(r"\b" + re.escape(form) + r"\b", item["example"], re.I)]
        if not forms:
            raise ValueError(f"Example needs word-form review: {item}")
        item["matched_forms"] = forms
        item["review_status"] = "已核对为正常词形变化"
    report = {
        "version": manifest["version"], "curated_words": len(by_word),
        "base_words": len(base), "added_words": sum(group_counts.values()), "groups": group_counts,
        "records": len(rows), "by_kind": counts,
        "four_sections_words": len(by_word),
        "selection": manifest["selection"],
        "added_ielts_tagged": sum("ielts" in r["tag"].split() for r in evidence),
        "added_bnc_or_frq_top5000": sum(positive_rank(r["bnc"], 5000) or positive_rank(r["frq"], 5000) for r in evidence),
        "sha256": hashlib.sha256(payload).hexdigest(),
        "example_inflection_review": target_review,
        "quality_boundary": "结构、中文存在、重复、词表和资源完整性可自动检查；语义说明为编写后的学习整理，不声称经过专业词典编辑逐条审定。",
        "source_boundary": "新增词的词义与词族参考随包固定版 ECDICT、OEWN 2025 和 etymology-db 2023-12；来源行说明实际依据，网页链接不表示逐页实时核验。",
    }
    report_bytes = (json.dumps(report, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    word_lines = "\n".join(sorted(by_word)) + "\n"
    coverage = [
        "# 中文学习卡覆盖",
        "",
        f"版本 {manifest['version']}：{len(by_word)} 个独立词头，每个均有双语例句、搭配、派生/同词族、构词或词源提示、中文近义辨析。",
        f"共 {counts['example']} 条原创双语例句、{counts['collocation']} 条搭配、{counts['derived']} 条词族说明、{counts['synonym']} 条近义辨析。",
        "",
        f"新增 {sum(group_counts.values())} 个词头，其中 {report['added_ielts_tagged']} 个带 ECDICT 的 ielts 标签，{report['added_bnc_or_frq_top5000']} 个在其 BNC 或 FRQ 频率前 5000 内；两组有重叠。",
        "这些是学习选词依据，不代表 IELTS 官方指定词表或全部常用词。不同拼写、同一词族的词可能分别成卡。",
        "例句是原创学习示例，中文说明是学习整理；原 WordNet 英文例句不在此中文数量内。没有向全部主词库条目推广这些覆盖数。",
        "",
        "来源和结构报告：licenses/curated-source.json。新增词的原始频率与标签：data/learning/priority-evidence.json（源码包）。",
        "",
        "## 原有学习词",
        "",
        "、".join(sorted(base)),
    ]
    names = {"a": "常用动作与学习表达", "b": "学术表达与论证", "c": "环境、科技与社会话题"}
    for group, selected in manifest["groups"].items():
        coverage.extend(["", f"## {names[group]}（{len(selected)}）", "", "、".join(sorted(selected))])
    outputs = {
        RESOURCE: payload,
        ROOT / "src/main/resources/curated-words.txt": word_lines.encode("utf-8"),
        ROOT / "licenses/curated-source.json": report_bytes,
        ROOT / "CURATED_COVERAGE.md": ("\n".join(coverage) + "\n").encode("utf-8"),
    }
    for path, content in outputs.items():
        if args.check:
            if not path.exists() or path.read_bytes() != content:
                raise ValueError(f"Generated output is stale: {path.relative_to(ROOT)}")
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
