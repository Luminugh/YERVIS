"""Prepare a bounded, licensed FLEURS corpus; never download the full audio archive.

Only public references are written to the reviewable manifest. Audio stays under
the ignored .gradle directory. No model, executable, or remote script is fetched.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import math
import pathlib
import struct
import tarfile
import unicodedata
import urllib.request
import wave

REVISION = "70bb2e84b976b7e960aa89f1c648e09c59f894dd"
ARCHIVE_BYTES = 582112372
ARCHIVE_SHA256 = "981802f6c828fd214fcf8bfc1036d80c9184b6eeb5650b3f7882f8affec046c9"
URL = f"https://huggingface.co/datasets/google/fleurs/resolve/{REVISION}/data/es_419/audio/test.tar.gz"
MAX_PREFIX_BYTES = 96_000_000
ROOT = pathlib.Path(__file__).resolve().parents[1]


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def word_count(text: str) -> int:
    text = "".join(c for c in unicodedata.normalize("NFD", text.lower())
                   if unicodedata.category(c) != "Mn")
    return len("".join(c if c.isalnum() or c.isspace() else " " for c in text).split())


def atomic_bytes(path: pathlib.Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".preparing")
    temporary.write_bytes(data)
    temporary.replace(path)


def get_prefix(target: pathlib.Path, count: int, previous: pathlib.Path) -> tuple[bytes, int]:
    """Extend an already downloaded prefix and refuse a server ignoring HTTP Range."""
    if count <= 0 or count > MAX_PREFIX_BYTES:
        raise ValueError("Prefix must be between 1 and 96,000,000 bytes")
    cached = target.read_bytes() if target.exists() else previous.read_bytes()
    if len(cached) >= count:
        return cached[:count], 0
    start = len(cached)
    request = urllib.request.Request(URL, headers={"Range": f"bytes={start}-{count - 1}"})
    with urllib.request.urlopen(request, timeout=90) as response:
        expected = f"bytes {start}-{count - 1}/{ARCHIVE_BYTES}"
        if response.status != 206 or response.headers.get("Content-Range") != expected:
            raise RuntimeError("Server did not honor the bounded range; archive not downloaded")
        remainder = response.read(count - start + 1)
    if len(remainder) != count - start:
        raise RuntimeError("Incomplete bounded download")
    data = cached + remainder
    atomic_bytes(target, data)
    return data, len(remainder)


def pcm16(original: bytes, expected_samples: int) -> tuple[bytes, int]:
    """Float32 mono16k -> PCM16 mono16k; no resample, gain, trim, or denoising."""
    if original[:4] != b"RIFF" or original[8:12] != b"WAVE":
        raise ValueError("Invalid source RIFF")
    chunks = {}
    offset = 12
    while offset + 8 <= len(original):
        name = original[offset:offset + 4]
        length = struct.unpack_from("<I", original, offset + 4)[0]
        if offset + 8 + length > len(original):
            raise ValueError("Truncated source WAV")
        chunks[name] = original[offset + 8:offset + 8 + length]
        offset += 8 + length + length % 2
    fmt = struct.unpack_from("<HHIIHH", chunks[b"fmt "])
    if fmt != (3, 1, 16000, 64000, 4, 32) or len(chunks[b"data"]) != expected_samples * 4:
        raise ValueError("Unexpected source sample format/count")
    pcm = bytearray(expected_samples * 2)
    clipped = 0
    for index, (value,) in enumerate(struct.iter_unpack("<f", chunks[b"data"])):
        if not math.isfinite(value):
            raise ValueError("Nonfinite source audio")
        clipped += value < -1.0 or value >= 1.0
        struct.pack_into("<h", pcm, index * 2, max(-32768, min(32767, round(value * 32768))))
    output = io.BytesIO()
    with wave.open(output, "wb") as writer:
        writer.setnchannels(1)
        writer.setsampwidth(2)
        writer.setframerate(16000)
        writer.writeframes(pcm)
    return output.getvalue(), clipped


def extract(prefix: bytes, metadata: dict[str, list[str]], destination: pathlib.Path) -> list[dict]:
    samples = []
    incomplete = False
    try:
        with tarfile.open(fileobj=io.BytesIO(prefix), mode="r|gz") as archive:
            for member in archive:
                if not member.isfile() or not member.name.endswith(".wav"):
                    continue
                name = pathlib.PurePosixPath(member.name).name
                if name not in metadata or member.size > 4_000_000:
                    raise ValueError("Unexpected archive WAV member")
                original = archive.extractfile(member).read()
                if len(original) != member.size:
                    incomplete = True
                    break
                row = metadata[name]
                prepared, clipped = pcm16(original, int(row[5]))
                fixture = f"fleurs-es419-{pathlib.Path(name).stem}"
                filename = fixture + ".wav"
                atomic_bytes(destination / filename, prepared)
                samples.append(dict(fixture=fixture, path=filename, sentenceId=int(row[0]),
                    reference=row[2], referenceKind="official_exact_transcription",
                    referenceWords=word_count(row[2]), sourceArchiveMember=member.name,
                    originalBytes=len(original), originalSha256=sha(original), bytes=len(prepared),
                    sha256=sha(prepared), sampleCount=int(row[5]), sampleRateHz=16000,
                    channels=1, bitsPerSample=16, encoding="PCM_S16LE",
                    durationSeconds=int(row[5]) / 16000, clippedSourceSamples=clipped,
                    speakerGender=row[6]))
    except (EOFError, tarfile.ReadError):
        incomplete = True
    if not incomplete:
        raise ValueError("Unexpected complete archive: bounded extraction invariant failed")
    return samples


def split_samples(samples: list[dict]) -> None:
    """Deterministic sentence-disjoint partition, balancing words without recognition."""
    groups = {}
    for sample in samples:
        groups.setdefault(sample["sentenceId"], []).append(sample)
    totals = {"adjustment": 0, "evaluation": 0}
    ordered = sorted(groups.items(), key=lambda item: hashlib.sha256(
        f"yezvi-confidence-v1:{item[0]}".encode()).hexdigest())
    for _, group in ordered:
        split = min(totals, key=lambda name: (totals[name], name))
        for sample in group:
            sample["split"] = split
            totals[split] += sample["referenceWords"]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prefix-bytes", type=int, default=64 * 1024 * 1024)
    parser.add_argument("--cache", type=pathlib.Path,
                        default=ROOT / ".gradle/yezvi-stt-research/confidence-corpus")
    parser.add_argument("--manifest", type=pathlib.Path,
                        default=ROOT / "docs/INVESTIGACION/F3.06-confianza-corpus/corpus-manifest.json")
    args = parser.parse_args()
    # Avoid accidentally admitting audio into a source-set or the Git worktree.
    ignored_root = (ROOT / ".gradle").resolve()
    if not args.cache.resolve().is_relative_to(ignored_root):
        raise ValueError("Audio cache must remain under ignored .gradle")
    stage2 = ROOT / ".gradle/yezvi-stt-research/stage2-audio"
    baseline_manifest = json.loads((stage2 / "audio-manifest.json").read_text(encoding="utf-8-sig"))
    expected_files = {row["path"]: row["sha256"] for row in baseline_manifest["metadata"]}
    tsv = (stage2 / "fleurs-es_419-test.tsv").read_bytes()
    readme = (stage2 / "fleurs-README.md").read_bytes()
    license_bytes = (stage2 / "CC-BY-4.0-legalcode.txt").read_bytes()
    for filename, contents in (("fleurs-es_419-test.tsv", tsv), ("fleurs-README.md", readme),
                               ("CC-BY-4.0-legalcode.txt", license_bytes)):
        if sha(contents) != expected_files[filename]:
            raise ValueError("Existing official metadata hash mismatch")
    metadata = {row[1]: row for row in csv.reader(io.StringIO(tsv.decode("utf-8")), delimiter="\t")}
    old_prefix = stage2 / "fleurs-es_419-test.prefix.partial.gz"
    if sha(old_prefix.read_bytes()) != baseline_manifest["rangeReceipt"]["prefixSha256"]:
        raise ValueError("Existing bounded prefix hash mismatch")
    args.cache.mkdir(parents=True, exist_ok=True)
    prefix_path = args.cache / "fleurs-test.prefix.partial.gz"
    if prefix_path.exists():
        cached_receipt_path = args.cache / "corpus-manifest.json"
        if not cached_receipt_path.exists():
            raise ValueError("Cached prefix has no receipt; inspect it before reuse")
        cached_receipt = json.loads(cached_receipt_path.read_text(encoding="utf-8-sig"))
        cached = prefix_path.read_bytes()
        if len(cached) != cached_receipt["prefixBytes"] or sha(cached) != cached_receipt["prefixSha256"]:
            raise ValueError("Cached bounded prefix differs from its recorded SHA-256")
    prefix, fetched = get_prefix(prefix_path, args.prefix_bytes, old_prefix)
    samples = extract(prefix, metadata, args.cache)
    split_samples(samples)
    totals = {name: dict(files=sum(s["split"] == name for s in samples),
                        referenceWords=sum(s["referenceWords"] for s in samples if s["split"] == name),
                        durationSeconds=sum(s["durationSeconds"] for s in samples if s["split"] == name))
              for name in ("adjustment", "evaluation")}
    manifest = dict(version=1, preparedDate="2026-10-14", corpus="Google FLEURS", language="es_419",
        speechType="human_read_speech", revision=REVISION, license="CC-BY-4.0",
        licenseUrl="https://creativecommons.org/licenses/by/4.0/",
        licenseEvidenceUrl=f"https://huggingface.co/datasets/google/fleurs/blob/{REVISION}/README.md",
        attribution=baseline_manifest["attribution"], sourceUrl=URL,
        publishedArchiveBytes=ARCHIVE_BYTES, publishedArchiveSha256=ARCHIVE_SHA256,
        fullArchiveHashVerified=False, prefixBytes=len(prefix), prefixSha256=sha(prefix),
        reusedBaselineBytes=old_prefix.stat().st_size,
        ranges=[dict(source="existing_verified_stage2_prefix", firstByte=0,
                     lastByte=old_prefix.stat().st_size - 1, status=206),
                dict(source="bounded_official_https_range", firstByte=old_prefix.stat().st_size,
                     lastByte=len(prefix) - 1, status=206)],
        integrityLimit="Pinned HTTPS bounded prefix only: full archive SHA-256/final gzip CRC not verified. "
                       "Per-file SHA-256 values are locally computed, not publisher checksums.",
        modifications="Complete WAV members only. Float32 mono16k converted to signed16 mono16k "
                      "using round(x*32768) and saturation. No trimming, resampling, gain, or transcript rewriting.",
        partition="Sentence-ID disjoint; deterministic SHA256(yezvi-confidence-v1:sentenceId) ordering and "
                  "greedy reference-word balance. No ASR outputs used for partitioning.",
        speakerIndependenceVerified=False,
        speakerLimit="The official TSV exposes gender but no verified speaker ID; no claim of speaker independence.",
        cacheDirectory=args.cache.relative_to(ROOT).as_posix(),
        metadataHashes={"officialTsvSha256": sha(tsv), "officialReadmeSha256": sha(readme),
                        "ccBy40LegalcodeSha256": sha(license_bytes)},
        totals=totals, minimumCorpusWordsMet=all(t["referenceWords"] >= 1000 for t in totals.values()),
        samples=samples)
    payload = (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    atomic_bytes(args.manifest, payload)
    atomic_bytes(args.cache / "corpus-manifest.json", payload)
    atomic_bytes(args.cache / "CC-BY-4.0-legalcode.txt", license_bytes)
    # Only counts/size are printed. No audio, reference, or recognized text.
    print(json.dumps(dict(downloadedThisRunBytes=fetched, prefixBytes=len(prefix),
                          samples=len(samples), totals=totals,
                          minimumCorpusWordsMet=manifest["minimumCorpusWordsMet"])))


if __name__ == "__main__":
    main()
