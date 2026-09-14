#!/usr/bin/env python3
"""Fetch the EPUB measurement corpus described by corpus.manifest.

The corpus is not committed -- ~40 MiB of binaries, and Project Gutenberg
regenerates its artifacts, so a committed copy would silently drift from what the
sources serve. The SHA-256 in the manifest is the anchor: a mismatch is a hard
failure, not a warning, because ADR-013's numbers are only meaningful against
these exact bytes.

Usage:
    python tools/epub-corpus/fetch_corpus.py [--dir DIR] [--verify-only]

Default DIR is `<repo>/build/epub-corpus`, which is already gitignored.

Exit codes: 0 every manifest entry present and verified, 1 something is missing
or corrupt, 2 bad usage.
"""

from __future__ import annotations

import argparse
import hashlib
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parents[1]
MANIFEST = SCRIPT_DIR / "corpus.manifest"
DEFAULT_DIR = REPO_ROOT / "build" / "epub-corpus"

# Both hosts serve a landing page to clients they do not recognize.
USER_AGENT = "Mozilla/5.0 (compatible; english-reader-corpus-measurement)"


@dataclass(frozen=True)
class Entry:
    name: str
    sha256: str
    size: int
    url: str


def load_manifest(path: Path) -> list[Entry]:
    entries: list[Entry] = []
    for lineno, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        if len(parts) != 4:
            raise SystemExit(f"{path}:{lineno}: expected 4 fields, got {len(parts)}")
        name, digest, size, url = parts
        if len(digest) != 64:
            raise SystemExit(f"{path}:{lineno}: {name} has a malformed SHA-256")
        entries.append(Entry(name=name, sha256=digest.lower(), size=int(size), url=url))
    return entries


def digest_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def check(path: Path, entry: Entry) -> str | None:
    """None when the file matches the manifest, else the reason it does not."""
    if not path.is_file():
        return "missing"
    actual_size = path.stat().st_size
    if actual_size != entry.size:
        return f"size {actual_size} != manifest {entry.size}"
    actual = digest_of(path)
    if actual != entry.sha256:
        return f"sha256 {actual[:16]}... != manifest {entry.sha256[:16]}..."
    return None


def download(entry: Entry, target: Path) -> None:
    request = urllib.request.Request(entry.url, headers={"User-Agent": USER_AGENT})
    # Write to a temp name first so an interrupted fetch cannot leave a
    # truncated file that later looks like a digest mismatch.
    staging = target.with_suffix(target.suffix + ".part")
    with urllib.request.urlopen(request, timeout=120) as response, staging.open("wb") as sink:
        while chunk := response.read(1 << 16):
            sink.write(chunk)
    staging.replace(target)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--dir", default=str(DEFAULT_DIR), help=f"corpus directory (default: {DEFAULT_DIR})")
    parser.add_argument(
        "--verify-only",
        action="store_true",
        help="check what is already on disk; never download",
    )
    args = parser.parse_args()

    corpus_dir = Path(args.dir)
    entries = load_manifest(MANIFEST)

    if not args.verify_only:
        corpus_dir.mkdir(parents=True, exist_ok=True)

    ok = 0
    problems: list[str] = []

    for entry in entries:
        target = corpus_dir / entry.name
        reason = check(target, entry)

        if reason is None:
            ok += 1
            continue

        if args.verify_only:
            problems.append(f"{entry.name}: {reason}")
            continue

        if reason != "missing":
            # A stale artifact must not be silently overwritten and re-verified;
            # say what changed, then try a fresh copy.
            print(f"  {entry.name}: {reason} -- refetching", file=sys.stderr)
            target.unlink(missing_ok=True)

        print(f"fetching {entry.name}", file=sys.stderr)
        try:
            download(entry, target)
        except (urllib.error.URLError, OSError) as error:
            problems.append(f"{entry.name}: download failed: {error}")
            continue

        reason = check(target, entry)
        if reason is None:
            ok += 1
        else:
            problems.append(
                f"{entry.name}: {reason} after download -- upstream artifact changed; "
                f"re-measure before updating the manifest digest"
            )

    print(f"\n{ok}/{len(entries)} corpus files verified in {corpus_dir}")
    if problems:
        print(f"\n{len(problems)} problem(s):", file=sys.stderr)
        for problem in problems:
            print(f"  {problem}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
