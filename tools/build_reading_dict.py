#!/usr/bin/env python3
"""Generate the bundled Japanese reading dictionary assets.

Workflow (mirrors tools/encode_prompts.py: raw source stays gitignored,
only the generated artifact is committed):

    python tools/build_reading_dict.py --input tools/raw/JMdict_e.xml

Accepts a plain JMdict XML (JMdict-Eng) or its .gz, so the canonical
EDRDG download (JMdict_e.gz) can be used directly.

Writes:
    app/src/main/assets/reading/words.gz   "surface\treading" per line, sorted
    app/src/main/assets/reading/kanji.gz   single-kanji fallback, same format
    app/src/main/assets/reading/NOTICE.txt CC-BY-SA attribution for the data

Licensing: JMdict is CC-BY-SA 4.0. The generated dictionary is a
derivative and ships the NOTICE file; do not strip it.

Note on the JMdict structure (a naive k_ele/rb reading is wrong here):
a written form lives in k_ele/keb and its reading lives in a *parallel*
r_ele/reb element (r_ele flagged re_nokanji describes a kana-only reading
and is skipped). The kanji-only reading re_dict/rb is not used; the full
reb already carries the okurigana, which derive_kanji strips below.
"""
import argparse
import collections
import gzip
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT_DIR = ROOT / "app" / "src" / "main" / "assets" / "reading"

KANJI = re.compile(r"[㐀-䶿一-鿿豈-﫿]")
WORD_BUDGET_BYTES = 3 * 1024 * 1024

NOTICE = """Japanese reading dictionary (app/src/main/assets/reading/)
=============================================================

Data: JMdict electronic dictionary (JMdict-Eng), maintained by the
Electronic Dictionary Research and Development Group.
License: Creative Commons Attribution-ShareAlike 4.0 Generic (CC-BY-SA 4.0).

Modifications: kept only entries whose written form contains kanji, took the
hiragana reading (r_ele/reb) for each written form, deduplicated by
(surface, reading), truncated to the most frequent entries to fit a 3 MB
budget, and derived a single-kanji fallback table from entries containing
exactly one kanji. Sorted by surface form and gzip compressed.

This derivative is distributed under CC-BY-SA 4.0.
Full license: https://creativecommons.org/licenses/by-sa/4.0/legalcode
"""


def entries_from(xml_path: pathlib.Path):
    """Yield (surface, reading) pairs in file order; later duplicates lose.

    Pairs k_ele/keb (written form) with the matching r_ele/reb (reading),
    zipping over readings that correspond to a kanji form (re_nokanji ones
    are dropped first so the parallel lists stay aligned).
    """
    opener = gzip.open if xml_path.suffix == ".gz" else open
    with opener(xml_path, "rb") as handle:
        context = ET.iterparse(handle, events=("end",))
        for _, element in context:
            if element.tag != "entry":
                continue
            readings = [
                (node.findtext("reb") or "").strip()
                for node in element.findall("r_ele")
                if node.find("re_nokanji") is None
            ]
            readings = [reading for reading in readings if reading]
            for index, kanji_element in enumerate(element.findall("k_ele")):
                surface = (kanji_element.findtext("keb") or "").strip()
                if not surface or index >= len(readings):
                    continue
                reading = readings[index]
                if KANJI.search(surface):
                    yield surface, reading
            element.clear()


def derive_kanji(words):
    """Single-kanji fallback, only from surfaces with exactly one kanji."""
    counts = collections.defaultdict(collections.Counter)
    for surface, reading in words:
        kanjis = [ch for ch in surface if KANJI.search(ch)]
        if len(kanjis) != 1:
            continue
        kanji = kanjis[0]
        tail = surface.split(kanji, 1)[1]
        value = (
            reading[: -len(tail)]
            if tail and len(reading) > len(tail) and reading.endswith(tail)
            else reading
        )
        if value:
            counts[kanji][value] += 1
    return sorted(
        (
            (kanji, max(counter.items(), key=lambda kv: (kv[1], kv[0][0]))[0])
            for kanji, counter in counts.items()
        ),
        key=lambda pair: pair[0],
    )


def write_gzipped(lines, path: pathlib.Path):
    payload = ("\n".join(lines) + "\n").encode("utf-8")
    # GzipFile (not gzip.open) so mtime=0 and an empty stored filename keep
    # the artifact byte-reproducible across runs.
    with open(path, "wb") as raw:
        with gzip.GzipFile(
            filename="", mode="wb", fileobj=raw, compresslevel=9, mtime=0
        ) as handle:
            handle.write(payload)
    return path.stat().st_size


def load_frequency(path: pathlib.Path):
    """Read a `surface<TAB>frequency` file (jmdict_de_freq format). Missing file -> no ordering."""
    table = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        surface, _, value = raw.partition("\t")
        if surface and value.strip().isdigit():
            table.setdefault(surface, int(value.strip()))
    return table


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=pathlib.Path)
    parser.add_argument(
        "--frequency",
        type=pathlib.Path,
        help="optional surface<TAB>freq file; entries are truncated by frequency, so common words survive",
    )
    parser.add_argument("--max-entries", type=int, default=120_000)
    args = parser.parse_args()
    if not args.input.exists():
        sys.exit(f"input not found: {args.input}")

    seen = set()
    words = []
    for surface, reading in entries_from(args.input):
        pair = (surface, reading)
        if pair in seen:
            continue
        seen.add(pair)
        words.append(pair)

    if args.frequency is not None:
        if not args.frequency.exists():
            sys.exit(f"frequency file not found: {args.frequency}")
        table = load_frequency(args.frequency)
        # 稳定排序：词频高在前，没登记的保持原顺序。
        words.sort(key=lambda pair: -table.get(pair[0], 0))
    else:
        print(
            "warning: no --frequency file; truncation keeps whatever order the input has. "
            "Prefer a frequency-ordered dump (jmesin_slim / jmdict_de_freq).",
            file=sys.stderr,
        )
    words = words[: args.max_entries]

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    kanji = derive_kanji(words)  # 只从最终保留的词条派生，保证与 shipped 资产自洽
    words.sort(key=lambda pair: pair[0])

    word_size = write_gzipped([f"{s}\t{r}" for s, r in words], OUT_DIR / "words.gz")
    kanji_size = write_gzipped([f"{k}\t{r}" for k, r in kanji], OUT_DIR / "kanji.gz")
    (OUT_DIR / "NOTICE.txt").write_text(NOTICE, encoding="utf-8")

    print(f"words: {len(words)} entries, {word_size} bytes gzipped")
    print(f"kanji: {len(kanji)} entries, {kanji_size} bytes gzipped")
    if word_size > WORD_BUDGET_BYTES:
        sys.exit(
            f"words.gz is {word_size} bytes, over the {WORD_BUDGET_BYTES} budget; "
            "re-run with a smaller --max-entries"
        )
    lookup = dict(words)
    for probe in ("昨日", "美味しい", "日本", "猫"):
        print(f"probe {probe} -> {lookup.get(probe, 'MISSING')}")


if __name__ == "__main__":
    main()
