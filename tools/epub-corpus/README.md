# EPUB measurement corpus

Reproduces the measurement behind [ADR-013](../../.trellis/spec/project/decisions.md):
`MAX_CHAPTER_CHARS` — not packaging granularity, and not any book-level ceiling — is what
rejected most real books, and **splitting at paragraph boundaries** rather than raising that
ceiling is what fixed it. Coverage went from **34%** (11 of 32) to **97%** (31 of 32) with
every budget constant unchanged.

The distributions below are the pre-splitting measurement and still describe the corpus shape;
what changed is what the parser *does* with an over-ceiling chapter. The post-split projection
(`--- projected after splitting ---`) models the new behavior, but treat its part counts as a
**lower bound**: it divides an over-ceiling paragraph evenly, while real sentence boundaries do
not, and it cannot see a paragraph with no sentence boundary at all. That blind spot is exactly
why it projected 32 of 32 while the production parser reports 31 — *Ulysses* has a 21,381-char
paragraph the splitter must leave intact. `CorpusImportSurveyTest` is the authority on coverage.

```powershell
python tools/epub-corpus/fetch_corpus.py      # ~40 MiB into build/epub-corpus/
python tools/epub-corpus/measure_corpus.py
```

`fetch_corpus.py --verify-only` checks what is already on disk without downloading.
Both scripts need only the Python standard library.

## Why the books are not committed

About 40 MiB of binaries, and Project Gutenberg regenerates its artifacts, so a
committed copy would drift from what the sources actually serve — silently, which is
the worst version. `corpus.manifest` pins a SHA-256 per file instead, and a mismatch
is a hard failure. If upstream changes, the fetch fails and says so; re-measure and
update the digest deliberately rather than letting ADR-013's numbers shift underneath.

`build/` is already gitignored, so the default download location stays out of git.

## Corpus shape

32 books, chosen to separate "the packer's habits" from "the ceiling is wrong":

| group | n | source |
|---|---|---|
| `gutenberg-*` | 18 | Project Gutenberg EPUB 2 (`.epub.noimages`) — coarse packing |
| `gutenberg3-*` | 4 | Project Gutenberg EPUB 3 (`.epub3.images`) — same texts, different packer |
| `se-*` | 10 | Standard Ebooks — one chapter per file |

The Standard Ebooks group is the load-bearing one. It is already split as finely as a
publisher reasonably can, and 5 of its 10 books still fail the *chapter* gate, which is
what rules out TOC-anchor splitting as a fix: its oversized resources are single genuine
chapters, so there is nothing left to split. Counting the paragraph gate too, 6 of 10 are
rejected — `se-pride-and-prejudice` clears the chapter ceiling and then loses on Darcy's
letter.

All titles are public domain in the United States. Gutenberg content carries the
Project Gutenberg License; Standard Ebooks releases to the public domain via CC0. The
corpus is used for measurement only — no text is redistributed here, and nothing from
it ships as an app fixture.

## What the numbers are worth

`measure_corpus.py` splits paragraphs with a regex over block-level tags. That
approximates `XhtmlTextExtractor`; it is not the same code. So:

- Treat absolute values as ±a few percent.
- The conclusions that decisions rest on are re-checked through the production parser
  by `RealBookImportBudgetTest`, which runs the two repository fixtures against real
  `EpubBookParser`.
- This script's job is to say *which* ceiling deserves a hardware benchmark. It cannot
  set one — that needs render cost on a physical device.

Budget constants are duplicated at the top of `measure_corpus.py`. If `ImportBudget.kt`
changes, update them; nothing enforces the pairing yet.

## Reading the output

Per-book rows, then three distributions, then book-level coverage. A book counts as
importable only when *every* chapter fits — that is why 91.3% of chapters fitting under
40,000 chars still yields only 13 of 32 books at the chapter gate, and 11 once the
paragraph gate counts.

The coverage table has two columns for that reason. **Quote the right one**
(`all gates (importable)` = 34%); the left one (`chapter gate only` = 41%) answers a
narrower question and was misread as coverage in an earlier revision of ADR-013.

Both columns answer "what would the ceiling admit **without** splitting", which is now a
counterfactual: they are what makes the case that splitting, not a higher ceiling, was the
right lever. Present coverage is 31 of 32.

`verdict` names the first budget that would reject the book, in the parser's own
evaluation order (`BookTooManyChapters` → `ChapterTooLong` → `TooManyParagraphs` →
`ParagraphTooLong`). It is a prediction from measured shape, not an observed failure;
`RealBookImportBudgetTest` is where a real rejection is asserted.
