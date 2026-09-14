#!/usr/bin/env python3
"""Profile the paragraphs that exceed MAX_PARAGRAPH_CHARS.

`ParagraphTooLong` rejects an entire book when one paragraph is oversized. Chars
alone do not say what that costs to render: `InteractiveText` builds one span per
sentence and `SentenceSplitter` runs per paragraph, so sentence count is the closer
proxy. This prints both for every outlier, plus what a soft-split at sentence
boundaries would produce.

Usage:
    python tools/epub-corpus/profile_long_paragraphs.py [--dir DIR] [--threshold N]

Same regex approximation as measure_corpus.py -- see its docstring.
"""

from __future__ import annotations

import argparse
import re
import sys
import zipfile
from pathlib import Path
from statistics import mean

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parents[1]
DEFAULT_DIR = REPO_ROOT / "build" / "epub-corpus"

sys.path.insert(0, str(SCRIPT_DIR))
from measure_corpus import (  # noqa: E402
    ATTR_HREF,
    ATTR_ID,
    ATTR_IDREF,
    ITEM,
    ITEMREF,
    LINEAR_NO,
    MAX_IMPORT_PARAGRAPHS,
    OPF_PATH,
    read_entry,
    resolve_href,
    split_paragraphs,
)

# Approximates SentenceSplitter: terminal punctuation, optional closing quote, space.
# ICU does more (and less) than this; treat counts as indicative.
SENTENCE_END = re.compile(r'[.!?]["”’]?\s')


def linear_spine(archive: zipfile.ZipFile) -> list[str]:
    container = read_entry(archive, "META-INF/container.xml")
    if not container:
        return []
    opf_match = OPF_PATH.search(container)
    if not opf_match:
        return []
    opf_path = opf_match.group(1)
    opf = read_entry(archive, opf_path)
    if not opf:
        return []

    manifest: dict[str, str] = {}
    for item in ITEM.finditer(opf):
        item_id = ATTR_ID.search(item.group(0))
        href = ATTR_HREF.search(item.group(0))
        if item_id and href:
            manifest[item_id.group(1)] = href.group(1)

    spine: list[str] = []
    for itemref in ITEMREF.finditer(opf):
        if LINEAR_NO.search(itemref.group(0)):
            continue
        idref = ATTR_IDREF.search(itemref.group(0))
        if idref and idref.group(1) in manifest:
            spine.append(resolve_href(opf_path, manifest[idref.group(1)]))
    return spine


def sentence_count(text: str) -> int:
    return len(SENTENCE_END.findall(text)) + 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--dir", default=str(DEFAULT_DIR))
    parser.add_argument("--threshold", type=int, default=8_000, help="MAX_PARAGRAPH_CHARS (default 8000)")
    args = parser.parse_args()

    corpus_dir = Path(args.dir)
    books = sorted(corpus_dir.glob("*.epub"))
    if not books:
        print(f"error: no .epub files in {corpus_dir}", file=sys.stderr)
        return 2

    outliers: list[tuple[str, str, int, int]] = []
    affected_books: set[str] = set()
    # Split the outliers by whether a sentence-boundary soft split would help at all.
    splittable_pieces: list[int] = []
    unsplittable: list[tuple[str, int]] = []
    # Splitting inflates a chapter's paragraph count, which could hit
    # MAX_IMPORT_PARAGRAPHS instead. Track before/after per affected chapter.
    chapter_growth: list[tuple[str, str, int, int]] = []

    for path in books:
        try:
            with zipfile.ZipFile(path) as archive:
                for entry_path in linear_spine(archive):
                    markup = read_entry(archive, entry_path)
                    if markup is None:
                        continue
                    paragraphs = split_paragraphs(markup)
                    if not paragraphs:
                        continue
                    chapter_has_outlier = False
                    after_count = 0
                    for paragraph in paragraphs:
                        if len(paragraph) <= args.threshold:
                            after_count += 1
                            continue
                        chapter_has_outlier = True
                        sents = sentence_count(paragraph)
                        outliers.append(
                            (path.stem, entry_path.rsplit("/", 1)[-1], len(paragraph), sents)
                        )
                        affected_books.add(path.stem)
                        pieces = [p for p in SENTENCE_END.split(paragraph) if p.strip()]
                        largest = max((len(p) for p in pieces), default=len(paragraph))
                        if largest > args.threshold:
                            # No internal sentence boundary to split on: the whole
                            # paragraph is one "sentence".
                            unsplittable.append((path.stem, len(paragraph)))
                            after_count += 1
                        else:
                            splittable_pieces.extend(len(p) for p in pieces)
                            after_count += len(pieces)
                    if chapter_has_outlier:
                        chapter_growth.append(
                            (path.stem, entry_path.rsplit("/", 1)[-1], len(paragraphs), after_count)
                        )
        except (zipfile.BadZipFile, OSError) as error:
            print(f"  {path.stem}: {error}", file=sys.stderr)

    if not outliers:
        print(f"no paragraph exceeds {args.threshold:,} chars in {len(books)} books")
        return 0

    print(f"=== paragraphs over {args.threshold:,} chars ===")
    print(f"{'book':<26} {'resource':<26} {'chars':>7} {'sents':>6} {'chars/sent':>11}")
    for book, resource, chars, sents in sorted(outliers, key=lambda r: -r[2]):
        print(f"{book:<26} {resource[:26]:<26} {chars:>7,} {sents:>6} {chars // max(sents, 1):>11,}")

    print(f"\n{len(outliers)} outlier paragraph(s) in {len(affected_books)} of {len(books)} books")
    print(f"books rejected by this budget alone: {sorted(affected_books)}")

    if splittable_pieces:
        ordered = sorted(splittable_pieces)
        print(f"\n--- if soft-split at sentence boundaries (n={len(ordered):,} pieces) ---")
        print(
            f"  median={ordered[len(ordered) // 2]:,}  mean={round(mean(ordered)):,}  "
            f"p95={ordered[int(len(ordered) * 0.95)]:,}  max={ordered[-1]:,}"
        )

    if unsplittable:
        print(f"\n--- NOT splittable at sentence boundaries ({len(unsplittable)}) ---")
        print("  No internal terminal punctuation: the paragraph is one sentence.")
        for book, chars in sorted(unsplittable, key=lambda r: -r[1]):
            print(f"  {book:<26} {chars:>7,}")
        affected = sorted({book for book, _ in unsplittable})
        print(f"  books a sentence-boundary split would NOT rescue: {affected}")

    if chapter_growth:
        print(f"\n--- paragraph-count growth in affected chapters (cap {MAX_IMPORT_PARAGRAPHS:,}) ---")
        print(f"{'book':<26} {'resource':<26} {'before':>7} {'after':>7} {'headroom':>9}")
        worst = 0
        for book, resource, before, after in sorted(chapter_growth, key=lambda r: -r[3]):
            worst = max(worst, after)
            flag = "  OVER CAP" if after > MAX_IMPORT_PARAGRAPHS else ""
            print(
                f"{book:<26} {resource[:26]:<26} {before:>7,} {after:>7,} "
                f"{MAX_IMPORT_PARAGRAPHS - after:>9,}{flag}"
            )
        if worst <= MAX_IMPORT_PARAGRAPHS:
            print(
                f"  Worst case {worst:,} stays under {MAX_IMPORT_PARAGRAPHS:,}, so splitting does not\n"
                f"  trade ParagraphTooLong for TooManyParagraphs."
            )

    return 0


if __name__ == "__main__":
    sys.exit(main())
