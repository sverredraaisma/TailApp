"""Fetch the Essentia model artifacts TailApp's genre classifier is built on.

Downloads three files from https://essentia.upf.edu/models/ into ``<repo>/models``:

* ``discogs-effnet-bs64-1.pb``            — frozen EfficientNet-B0 embedding graph
* ``genre_discogs400-discogs-effnet-1.pb`` — frozen 400-class genre head
* ``genre_discogs400-discogs-effnet-1.json`` — class labels + inference settings

The weights are **CC BY-NC-ND 4.0** (see tools/README.md). They are deliberately
*not* committed: this script downloads the original, unmodified files. ``/models/``
is gitignored.

Idempotent: a file whose size already matches is left alone. ``--force``
re-downloads everything.

Usage:
    uv run python download_models.py [--force]
"""

from __future__ import annotations

import argparse
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
MODELS_DIR = REPO_ROOT / "models"

BASE = "https://essentia.upf.edu/models"


@dataclass(frozen=True)
class Artifact:
    url: str
    name: str
    # Expected size in bytes. Upstream serves fixed releases (version "1"), so a
    # size mismatch means a truncated download or a silently republished file —
    # both worth failing on rather than feeding to tf2onnx.
    expected_size: int
    # Tolerance as a fraction of expected_size; the .json is the only file that
    # has ever been reformatted upstream.
    tolerance: float = 0.0


ARTIFACTS = (
    Artifact(
        url=f"{BASE}/feature-extractors/discogs-effnet/discogs-effnet-bs64-1.pb",
        name="discogs-effnet-bs64-1.pb",
        expected_size=18_366_619,
        tolerance=0.02,
    ),
    Artifact(
        url=f"{BASE}/classification-heads/genre_discogs400/genre_discogs400-discogs-effnet-1.pb",
        name="genre_discogs400-discogs-effnet-1.pb",
        expected_size=2_057_977,
        tolerance=0.05,
    ),
    Artifact(
        url=f"{BASE}/classification-heads/genre_discogs400/genre_discogs400-discogs-effnet-1.json",
        name="genre_discogs400-discogs-effnet-1.json",
        expected_size=14_951,
        tolerance=0.10,
    ),
)


def _size_ok(artifact: Artifact, size: int) -> bool:
    slack = max(1, int(artifact.expected_size * artifact.tolerance))
    return abs(size - artifact.expected_size) <= slack


def download(artifact: Artifact, force: bool) -> Path:
    target = MODELS_DIR / artifact.name
    if target.exists() and not force:
        size = target.stat().st_size
        if _size_ok(artifact, size):
            print(f"  ok       {artifact.name} ({size:,} bytes, already present)")
            return target
        print(f"  stale    {artifact.name} ({size:,} bytes, expected ~{artifact.expected_size:,}) — refetching")

    print(f"  fetching {artifact.name} <- {artifact.url}")
    # Download to a sibling temp file so an interrupted run never leaves a
    # half-written .pb that the next run would treat as present.
    tmp = target.with_suffix(target.suffix + ".part")
    try:
        with urllib.request.urlopen(artifact.url, timeout=120) as response, tmp.open("wb") as out:
            while chunk := response.read(1 << 20):
                out.write(chunk)
    except urllib.error.URLError as exc:
        tmp.unlink(missing_ok=True)
        raise SystemExit(f"failed to download {artifact.url}: {exc}") from exc

    size = tmp.stat().st_size
    if not _size_ok(artifact, size):
        tmp.unlink(missing_ok=True)
        raise SystemExit(
            f"{artifact.name}: downloaded {size:,} bytes, expected ~{artifact.expected_size:,}. "
            "Upstream may have republished the file; check it by hand before bumping the size."
        )

    tmp.replace(target)
    print(f"  ok       {artifact.name} ({size:,} bytes)")
    return target


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--force", action="store_true", help="re-download even if the file looks intact")
    args = parser.parse_args()

    MODELS_DIR.mkdir(parents=True, exist_ok=True)
    print(f"models directory: {MODELS_DIR}")
    for artifact in ARTIFACTS:
        download(artifact, args.force)

    print(
        "\nThese weights are CC BY-NC-ND 4.0, (c) Music Technology Group, "
        "Universitat Pompeu Fabra. Do not commit them; see tools/README.md."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
