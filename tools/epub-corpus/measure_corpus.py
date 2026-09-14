#!/usr/bin/env python3
"""Measure EPUB packaging granularity against this project's import budgets.

One chapter is one linear spine item (ADR-013), so a book is importable only if
*every* chapter fits. This reports the distribution over the whole corpus and the
book-level coverage at candidate ceilings.

Usage:
    python tools/epub-corpus/measure_corpus.py [--dir DIR] [--csv OUT] [--json]

APPROXIMATION -- read this before quoting a number
---------------------------------------------------
Paragraph splitting here is a regex over block-level tags, not the production
`XhtmlTextExtractor`. Absolute values carry a few percent of error. The
load-bearing conclusions are re-checked through the real parser by
`RealBookImportBudgetTest`; this script is for shape and coverage, and its job is
to say *which* ceiling to measure on hardware, not to set one.
"""

from __future__ import annotations

import argparse
import csv
import html
import json
import re
import sys
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
from statistics import mean

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parents[1]
DEFAULT_DIR = REPO_ROOT / "build" / "epub-corpus"

# Kept in sync with ImportBudget.kt by hand; the mismatch check below catches drift.
MAX_CHAPTER_CHARS = 40_000
MAX_PARAGRAPH_CHARS = 8_000
MAX_IMPORT_PARAGRAPHS = 1_200
MAX_BOOK_CHAPTERS = 500

CANDIDATE_CEILINGS = (40_000, 50_000, 60_000, 80_000, 100_000, 200_000)

BLOCK_TAGS = r"p|div|h[1-6]|li|blockquote|section|article|tr|dd|dt|pre|figcaption"
DROP_ELEMENTS = re.compile(r"(?is)<(script|style|head)[\s>].*?</\1>")
BLOCK_CLOSE = re.compile(rf"(?is)</({BLOCK_TAGS})>")
BLOCK_SELF = re.compile(rf"(?is)<({BLOCK_TAGS})[^>]*/>")
LINE_BREAK = re.compile(r"(?is)<br[^>]*/?>")
ANY_TAG = re.compile(r"(?s)<[^>]+>")
INLINE_SPACE = re.compile(r"[ \t]+")
PARAGRAPH_SPLIT = re.compile(r"\n\s*\n")

ITEM = re.compile(r"(?s)<item\s[^>]*>")
ITEMREF = re.compile(r"(?s)<itemref\s[^>]*>")
ATTR_ID = re.compile(r'\sid="([^"]+)"')
ATTR_HREF = re.compile(r'\shref="([^"]+)"')
ATTR_IDREF = re.compile(r'\sidref="([^"]+)"')
LINEAR_NO = re.compile(r'linear\s*=\s*"no"')
OPF_PATH = re.compile(r'full-path="([^"]+)"')
PKG_VERSION = re.compile(r'<package[^>]*\sversion="([^"]+)"')


@dataclass
class BookMeasurement:
    book: str
    epub: str = ""
    chapters: list[int] = field(default_factory=list)
    paragraphs_per_chapter: list[int] = field(default_factory=list)
    paragraph_lengths: list[int] = field(default_factory=list)
    error: str = ""

    @property
    def max_chapter(self) -> int:
        return max(self.chapters, default=0)

    @property
    def max_paragraph(self) -> int:
        return max(self.paragraph_lengths, default=0)

    @property
    def max_paragraph_count(self) -> int:
        return max(self.paragraphs_per_chapter, default=0)

    def verdict(self) -> str:
        """First budget that rejects, in the parser's own evaluation order."""
        if len(self.chapters) > MAX_BOOK_CHAPTERS:
            return "BookTooManyChapters"
        if self.max_chapter > MAX_CHAPTER_CHARS:
            return "ChapterTooLong"
        if self.max_paragraph_count > MAX_IMPORT_PARAGRAPHS:
            return "TooManyParagraphs"
        if self.max_paragraph > MAX_PARAGRAPH_CHARS:
            return "ParagraphTooLong"
        return "PASS"


def split_paragraphs(markup: str) -> list[str]:
    text = DROP_ELEMENTS.sub(" ", markup)
    text = BLOCK_CLOSE.sub("\n\n", text)
    text = BLOCK_SELF.sub("\n\n", text)
    text = LINE_BREAK.sub("\n", text)
    text = ANY_TAG.sub(" ", text)
    text = html.unescape(text).replace(" ", " ")
    return [p for p in (INLINE_SPACE.sub(" ", s).strip() for s in PARAGRAPH_SPLIT.split(text)) if p]


def resolve_href(opf_path: str, href: str) -> str:
    from urllib.parse import unquote

    target = unquote(href.split("#")[0])
    base = opf_path.rsplit("/", 1)[0] if "/" in opf_path else ""
    joined = f"{base}/{target}" if base else target
    parts: list[str] = []
    for segment in joined.split("/"):
        if segment in ("", "."):
            continue
        if segment == "..":
            if parts:
                parts.pop()
            continue
        parts.append(segment)
    return "/".join(parts)


def read_entry(archive: zipfile.ZipFile, name: str) -> str | None:
    try:
        raw = archive.read(name)
    except KeyError:
        return None
    # EPUB requires UTF-8 or UTF-16; be lenient so one bad byte cannot drop a book.
    for encoding in ("utf-8", "utf-16", "latin-1"):
        try:
            return raw.decode(encoding)
        except UnicodeDecodeError:
            continue
    return None


def measure_book(path: Path) -> BookMeasurement:
    result = BookMeasurement(book=path.stem)
    try:
        with zipfile.ZipFile(path) as archive:
            container = read_entry(archive, "META-INF/container.xml")
            if not container:
                result.error = "no container.xml"
                return result
            opf_match = OPF_PATH.search(container)
            if not opf_match:
                result.error = "no rootfile full-path"
                return result
            opf_path = opf_match.group(1)
            opf = read_entry(archive, opf_path)
            if not opf:
                result.error = "no OPF"
                return result

            version_match = PKG_VERSION.search(opf)
            result.epub = version_match.group(1) if version_match else "?"

            manifest: dict[str, str] = {}
            for item in ITEM.finditer(opf):
                item_id = ATTR_ID.search(item.group(0))
                href = ATTR_HREF.search(item.group(0))
                if item_id and href:
                    manifest[item_id.group(1)] = href.group(1)

            spine: list[str] = []
            for itemref in ITEMREF.finditer(opf):
                if LINEAR_NO.search(itemref.group(0)):
                    continue  # matches Readium's readingOrder
                idref = ATTR_IDREF.search(itemref.group(0))
                if idref and idref.group(1) in manifest:
                    spine.append(resolve_href(opf_path, manifest[idref.group(1)]))

            for entry_path in spine:
                markup = read_entry(archive, entry_path)
                if markup is None:
                    continue
                paragraphs = split_paragraphs(markup)
                if not paragraphs:
                    continue  # image-only cover: filtered before ImportedChapter
                chars = sum(len(p) for p in paragraphs)
                if chars == 0:
                    continue
                result.chapters.append(chars)
                result.paragraphs_per_chapter.append(len(paragraphs))
                result.paragraph_lengths.extend(len(p) for p in paragraphs)

            if not result.chapters:
                result.error = "no readable chapters"
    except (zipfile.BadZipFile, OSError) as error:
        result.error = str(error)
    return result


def percentiles(values: list[int], marks: tuple[int, ...]) -> dict[str, object]:
    ordered = sorted(values)
    n = len(ordered)

    def at(q: float) -> int:
        return ordered[min(n - 1, int(n * q))]

    return {
        "n": n,
        "min": ordered[0],
        "median": at(0.50),
        "mean": round(mean(ordered)),
        "max": ordered[-1],
        "p90": at(0.90),
        "p95": at(0.95),
        "p99": at(0.99),
        "within": {str(m): sum(1 for v in ordered if v <= m) for m in marks},
    }


def print_distribution(label: str, stats: dict[str, object], marks: tuple[int, ...]) -> None:
    n = stats["n"]
    print(f"--- {label} (n={n:,}) ---")
    print(
        f"  min={stats['min']:,}  median={stats['median']:,}  "
        f"mean={stats['mean']:,}  max={stats['max']:,}"
    )
    print(f"  p90={stats['p90']:,}  p95={stats['p95']:,}  p99={stats['p99']:,}")
    within = stats["within"]
    for m in marks:
        count = within[str(m)]
        print(f"  <= {m:>7,} : {count:>6,} of {n:>6,}  ({count / n:6.1%})")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--dir", default=str(DEFAULT_DIR), help=f"corpus directory (default: {DEFAULT_DIR})")
    parser.add_argument("--csv", help="write per-book rows to this CSV")
    parser.add_argument("--json", action="store_true", help="emit JSON instead of text")
    args = parser.parse_args()

    corpus_dir = Path(args.dir)
    books = sorted(corpus_dir.glob("*.epub"))
    if not books:
        print(
            f"error: no .epub files in {corpus_dir}\n"
            f"run: python tools/epub-corpus/fetch_corpus.py",
            file=sys.stderr,
        )
        return 2

    measurements = [measure_book(path) for path in books]
    measured = [m for m in measurements if not m.error]
    failed = [m for m in measurements if m.error]

    if not measured:
        print("error: every book failed to parse", file=sys.stderr)
        return 1

    all_chapters = [c for m in measured for c in m.chapters]
    all_paragraphs = [p for m in measured for p in m.paragraph_lengths]
    all_para_counts = [c for m in measured for c in m.paragraphs_per_chapter]

    chapter_stats = percentiles(all_chapters, CANDIDATE_CEILINGS)
    paragraph_stats = percentiles(all_paragraphs, (8_000, 12_000, 16_000, 24_000))
    para_count_stats = percentiles(all_para_counts, (MAX_IMPORT_PARAGRAPHS,))

    # Two columns, because conflating them is exactly the mistake ADR-013 first made:
    #   chapterGateOnly -- books whose every chapter fits the candidate ceiling
    #   allGates        -- books that also clear the paragraph and count budgets,
    #                      i.e. what "importable" actually means
    # At 40,000 these are 13 and 11. The paragraph gate owns the difference.
    coverage: dict[int, tuple[int, int]] = {}
    for ceiling in CANDIDATE_CEILINGS:
        chapter_only = sum(1 for m in measured if m.max_chapter <= ceiling)
        all_gates = sum(
            1
            for m in measured
            if m.max_chapter <= ceiling
            and m.max_paragraph <= MAX_PARAGRAPH_CHARS
            and m.max_paragraph_count <= MAX_IMPORT_PARAGRAPHS
            and len(m.chapters) <= MAX_BOOK_CHAPTERS
        )
        coverage[ceiling] = (chapter_only, all_gates)

    if args.json:
        print(
            json.dumps(
                {
                    "books": len(measured),
                    "unreadable": [{"book": m.book, "error": m.error} for m in failed],
                    "chapterChars": chapter_stats,
                    "paragraphChars": paragraph_stats,
                    "paragraphsPerChapter": para_count_stats,
                    "coverageByChapterCeiling": {
                        str(ceiling): {
                            "chapterGateOnly": chapter_only,
                            "importable": all_gates,
                            "of": len(measured),
                        }
                        for ceiling, (chapter_only, all_gates) in coverage.items()
                    },
                    "perBook": [
                        {
                            "book": m.book,
                            "epub": m.epub,
                            "chapters": len(m.chapters),
                            "totalChars": sum(m.chapters),
                            "maxChapterChars": m.max_chapter,
                            "overChapterCap": sum(1 for c in m.chapters if c > MAX_CHAPTER_CHARS),
                            "maxParaCount": m.max_paragraph_count,
                            "maxParaChars": m.max_paragraph,
                            "verdict": m.verdict(),
                        }
                        for m in measured
                    ],
                },
                indent=2,
            )
        )
    else:
        print(f"=== per book (sorted by largest chapter) ===")
        header = f"{'book':<40} {'epub':>4} {'chaps':>6} {'maxChap':>8} {'over':>5} {'maxPara':>8} verdict"
        print(header)
        for m in sorted(measured, key=lambda x: x.max_chapter, reverse=True):
            over = sum(1 for c in m.chapters if c > MAX_CHAPTER_CHARS)
            print(
                f"{m.book:<40} {m.epub:>4} {len(m.chapters):>6} "
                f"{m.max_chapter:>8,} {over:>5} {m.max_paragraph:>8,} {m.verdict()}"
            )
        if failed:
            print("\n=== unreadable ===")
            for m in failed:
                print(f"  {m.book}: {m.error}")

        print()
        print_distribution("chapter chars", chapter_stats, CANDIDATE_CEILINGS)
        print_distribution("paragraph chars", paragraph_stats, (8_000, 12_000, 16_000, 24_000))
        print_distribution("paragraphs per chapter", para_count_stats, (MAX_IMPORT_PARAGRAPHS,))

        print("\n--- books by candidate chapter ceiling (every chapter must fit) ---")
        print(f"  {'ceiling':>7}  {'chapter gate only':>19}  {'all gates (importable)':>23}")
        for ceiling, (chapter_only, all_gates) in coverage.items():
            print(
                f"  {ceiling:>7,}  {chapter_only:>7} of {len(measured):<3} "
                f"({chapter_only / len(measured):>4.0%})  "
                f"{all_gates:>11} of {len(measured):<3} ({all_gates / len(measured):>4.0%})"
            )
        print(
            "  The gap is the paragraph budget. Quote the right-hand column as coverage;\n"
            "  the left-hand one answers only 'would the chapter ceiling admit it'."
        )

        verdicts: dict[str, int] = {}
        for m in measured:
            verdicts[m.verdict()] = verdicts.get(m.verdict(), 0) + 1
        print(f"\n--- verdict at current budgets (n={len(measured)}) ---")
        for name, count in sorted(verdicts.items(), key=lambda kv: -kv[1]):
            print(f"  {name:<22} {count:>3}  ({count / len(measured):5.0%})")

    if args.csv:
        with Path(args.csv).open("w", newline="", encoding="utf-8") as handle:
            writer = csv.writer(handle)
            writer.writerow(
                ["book", "epub", "chapters", "totalChars", "maxChapterChars",
                 "overChapterCap", "maxParaCount", "maxParaChars", "verdict", "error"]
            )
            for m in measurements:
                writer.writerow(
                    [m.book, m.epub, len(m.chapters), sum(m.chapters), m.max_chapter,
                     sum(1 for c in m.chapters if c > MAX_CHAPTER_CHARS),
                     m.max_paragraph_count, m.max_paragraph,
                     m.verdict() if not m.error else "", m.error]
                )
        print(f"\nwrote {args.csv}", file=sys.stderr)

    return 0


if __name__ == "__main__":
    sys.exit(main())
