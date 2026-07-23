"""Fetch BeatNet (and madmom's sources) — everything the other two scripts read.

Unlike the Essentia genre models, BeatNet's checkpoints are not published as
standalone downloads: they ship *inside* the ``BeatNet`` wheel on PyPI, under
``BeatNet/models/``. So this script downloads the wheel (a zip), verifies its
SHA-256 against the digest PyPI publishes, and unpacks it into
``<repo>/models/beatnet/pkg``::

    BeatNet/model.py                   the CRNN definition (BDA)
    BeatNet/log_spect.py               the madmom feature pipeline
    BeatNet/models/model_1_weights.pt  GTZAN-trained     (1.6 MB)
    BeatNet/models/model_2_weights.pt  Ballroom-trained  (1.6 MB)
    BeatNet/models/model_3_weights.pt  Rock corpus       (1.6 MB)

The wheel is *not* installed with pip on purpose. Its metadata pins
``numba==0.54.1``, which resolves against nothing modern, and it needs pyaudio
and matplotlib for the parts of the package (live streaming, plotting) that this
project does not use. ``export_beatnet.py`` and ``dump_beat_reference.py`` add
the unpacked directory to ``sys.path`` instead.

madmom, and why it is unpacked rather than installed
----------------------------------------------------
``BeatNet/log_spect.py`` — the feature extractor the CRNN was trained with — is a
thin wrapper over six madmom modules. madmom 0.16.1 publishes **no wheels**, only
an sdist that Cythonises three ``.pyx`` files, so ``pip install madmom`` needs a
C toolchain *and* Python < 3.10 (``from collections import MutableSequence``) and
numpy < 1.24 (``np.float``).

None of the compiled modules are on the path this project uses. The three
``.pyx`` files are ``madmom/ml/hmm.pyx``, ``madmom/features/beats_crf.pyx`` and
``madmom/audio/comb_filters.pyx`` — an HMM decoder, a CRF beat decoder and a comb
filterbank, all part of madmom's *decoder*, which TailApp replaces with its own.
The feature path (``madmom.processors``, ``madmom.utils``, and
``madmom.audio.{signal,stft,filters,spectrogram}``) is pure Python over numpy and
scipy.

So this script unpacks madmom's sdist into ``<repo>/models/madmom/`` and
``dump_beat_reference.py`` imports those modules directly, with stub parent
packages so that ``madmom/__init__.py`` and ``madmom/audio/__init__.py`` — the
only two files that pull the compiled modules in — never execute. Nothing is
patched: the modules that run are upstream's bytes, unmodified.

Licence
-------
BeatNet is **CC BY 4.0** — https://creativecommons.org/licenses/by/4.0/ — which
is materially more permissive than the genre models' BY-NC-ND: attribution is
the only condition, so redistribution and commercial use are both allowed. The
weights are still kept out of git (``/models/`` is gitignored) for the same
practical reason the ONNX conversions are: they are build outputs, rebuildable
from this script, and 5 MB of binary in a source tree earns nothing.

    Heydari, M., Cwitkowitz, F., & Duan, Z. (2021).
    *BeatNet: CRNN and Particle Filtering for Online Joint Beat, Downbeat and
    Meter Tracking.* ISMIR 2021.
    https://github.com/mjhydri/BeatNet

madmom is BSD-licensed for academic/non-commercial use (the package declares
"BSD, CC BY-NC-SA"); it is used here only on the workstation, to generate a
reference dump. None of it is ported into the app or shipped.

Idempotent: an already-unpacked tree with the right digest is left alone.

Usage:
    uv run tools/download_beatnet.py [--force]
"""

# /// script
# requires-python = ">=3.9"
# dependencies = []
# ///

from __future__ import annotations

import argparse
import hashlib
import shutil
import sys
import tarfile
import urllib.error
import urllib.request
import zipfile
from dataclasses import dataclass
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
MODELS_DIR = REPO_ROOT / "models"
BEATNET_DIR = MODELS_DIR / "beatnet"
PACKAGE_DIR = BEATNET_DIR / "pkg"
MADMOM_DIR = MODELS_DIR / "madmom"
MADMOM_SRC = MADMOM_DIR / "madmom-0.16.1"


@dataclass(frozen=True)
class Archive:
    name: str
    url: str
    # PyPI's own digest. These archives are unpacked and *executed* as source, so
    # "the bytes upstream published" is worth checking rather than assuming.
    sha256: str
    directory: Path
    unpack_into: Path
    # Paths inside `unpack_into` that must exist afterwards.
    required: tuple


BEATNET = Archive(
    name="BeatNet-1.1.3-py3-none-any.whl",
    url=(
        "https://files.pythonhosted.org/packages/67/c7/"
        "3ece1b101a1f841655b76322ea9fafe64591a711cb3d99dc52ded06eaa11/"
        "BeatNet-1.1.3-py3-none-any.whl"
    ),
    sha256="1ecfa17bdcbe899975a88bdb6efebd6970d846a4f3b6cfd5c6f320647c641c7e",
    directory=BEATNET_DIR,
    unpack_into=PACKAGE_DIR,
    required=(
        "BeatNet/model.py",
        "BeatNet/log_spect.py",
        "BeatNet/common.py",
        "BeatNet/models/model_1_weights.pt",
        "BeatNet/models/model_2_weights.pt",
        "BeatNet/models/model_3_weights.pt",
    ),
)

MADMOM = Archive(
    name="madmom-0.16.1.tar.gz",
    url=(
        "https://files.pythonhosted.org/packages/c7/a3/"
        "9f3de3e8068a3606331134d96b84c8db4f7624d6715be8ab3c1f56e6731d/"
        "madmom-0.16.1.tar.gz"
    ),
    sha256="64a86a29106e7c4e0c5f4ec96801c2d1a8492db710e4fe27e097da5517d68cb2",
    directory=MADMOM_DIR,
    unpack_into=MADMOM_DIR,
    required=(
        "madmom-0.16.1/madmom/processors.py",
        "madmom-0.16.1/madmom/utils/__init__.py",
        "madmom-0.16.1/madmom/audio/signal.py",
        "madmom-0.16.1/madmom/audio/stft.py",
        "madmom-0.16.1/madmom/audio/filters.py",
        "madmom-0.16.1/madmom/audio/spectrogram.py",
    ),
)

ARCHIVES = (BEATNET, MADMOM)


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(1 << 20):
            digest.update(chunk)
    return digest.hexdigest()


def _unpacked_ok(archive: Archive) -> bool:
    return all((archive.unpack_into / name).is_file() for name in archive.required)


def fetch(archive: Archive, force: bool) -> Path:
    target = archive.directory / archive.name
    if target.is_file() and not force:
        actual = _sha256(target)
        if actual == archive.sha256:
            print(f"  ok       {archive.name} ({target.stat().st_size:,} bytes, already present)")
            return target
        print(f"  stale    {archive.name} (sha256 {actual[:12]}, expected {archive.sha256[:12]}) — refetching")

    print(f"  fetching {archive.name} <- {archive.url}")
    tmp = target.with_suffix(target.suffix + ".part")
    try:
        with urllib.request.urlopen(archive.url, timeout=180) as response, tmp.open("wb") as out:
            while chunk := response.read(1 << 20):
                out.write(chunk)
    except urllib.error.URLError as exc:
        tmp.unlink(missing_ok=True)
        raise SystemExit(f"failed to download {archive.url}: {exc}") from exc

    actual = _sha256(tmp)
    if actual != archive.sha256:
        tmp.unlink(missing_ok=True)
        raise SystemExit(
            f"{archive.name}: sha256 {actual} does not match the published {archive.sha256}. "
            "Refusing to unpack; check PyPI by hand before changing the pin."
        )

    tmp.replace(target)
    print(f"  ok       {archive.name} ({target.stat().st_size:,} bytes)")
    return target


def unpack(archive: Archive, path: Path, force: bool) -> None:
    if _unpacked_ok(archive) and not force:
        print(f"  ok       unpacked tree already complete at {archive.unpack_into}")
        return
    # Only ever remove a directory this script created.
    stale = archive.unpack_into if archive.unpack_into != archive.directory else None
    if stale is not None and stale.exists():
        shutil.rmtree(stale)

    if path.suffix == ".whl":
        with zipfile.ZipFile(path) as zip_archive:
            zip_archive.extractall(archive.unpack_into)
    else:
        with tarfile.open(path, "r:gz") as tar_archive:
            # `filter="data"` on 3.12+; the 3.9 fallback is the old behaviour,
            # which is acceptable for an archive whose digest we just checked.
            try:
                tar_archive.extractall(archive.unpack_into, filter="data")
            except TypeError:
                tar_archive.extractall(archive.unpack_into)

    missing = [name for name in archive.required if not (archive.unpack_into / name).is_file()]
    if missing:
        raise SystemExit(f"{archive.name} unpacked without {missing}; upstream layout must have changed")
    print(f"  ok       unpacked into {archive.unpack_into}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--force", action="store_true", help="re-download and re-unpack unconditionally")
    args = parser.parse_args()

    for archive in ARCHIVES:
        archive.directory.mkdir(parents=True, exist_ok=True)
        print(f"{archive.name}:")
        unpack(archive, fetch(archive, args.force), args.force)

    licence = PACKAGE_DIR / "BeatNet-1.1.3.dist-info" / "LICENSE"
    first_line = licence.read_text(encoding="utf-8").splitlines()[0] if licence.is_file() else "(not found)"
    print(f"\nBeatNet's bundled licence file reads: {first_line!r}")
    print(
        "BeatNet is CC BY 4.0, (c) Mojtaba Heydari et al. (ISMIR 2021). "
        "Attribution is required; see docs/beat-model.md. Do not commit the weights."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
