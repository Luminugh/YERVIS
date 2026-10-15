"""Summarize local A/B reports without printing or publishing private references/transcripts."""
import argparse
import importlib.util
import json
import pathlib
import statistics

spec = importlib.util.spec_from_file_location("local_accuracy", pathlib.Path(__file__).with_name("Measure-LocalSpeechAccuracy.py"))
accuracy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(accuracy)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=pathlib.Path, required=True)
    parser.add_argument("--public-manifest", type=pathlib.Path, required=True)
    parser.add_argument("--private-manifest", type=pathlib.Path)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    report = json.loads(args.report.read_text(encoding="utf-8-sig"))
    public = json.loads(args.public_manifest.read_text(encoding="utf-8-sig"))
    references = {s["path"]: s["rawText"] for s in public["samples"]}
    if args.private_manifest:
        references["authorized-private-recording"] = json.loads(args.private_manifest.read_text(encoding="utf-8-sig"))["expectedText"]
    rows = []
    for run in report["runs"]:
        result = accuracy.score(references[run["fixture"]], run["text"])
        row = {key: run[key] for key in ("fixture", "round", "engine", "samples", "durationMs", "loadMs", "inferenceMs")}
        row.update({key: result[key] for key in ("referenceWords", "hypothesisWords", "S", "D", "I", "wer")})
        row["rtf"] = run["inferenceMs"] / run["durationMs"]
        if run["fixture"] == "authorized-private-recording":
            recognized = accuracy.words(run["text"])
            expected = accuracy.words(references[run["fixture"]])
            row["technicalTokenOccurrences"] = {term: dict(reference=expected.count(term), recognized=recognized.count(term))
                for term in ("discord", "cpu", "edge", "8792", "4668", "yezvi", "kotlin", "compose", "nalbero")}
        if "afterFinishMs" in run:
            row["afterFinishMs"] = run["afterFinishMs"]
        rows.append(row)
    aggregates = []
    for dataset in ("public", "private-approximate-reference"):
        for engine in sorted({r["engine"] for r in rows}):
            selected = [r for r in rows if r["engine"] == engine and
                        ((r["fixture"] == "authorized-private-recording") == dataset.startswith("private"))]
            if not selected:
                continue
            total_words = sum(r["referenceWords"] for r in selected)
            total_errors = sum(r[k] for r in selected for k in ("S", "D", "I"))
            aggregates.append(dict(dataset=dataset, engine=engine, runs=len(selected),
                wer=total_errors / total_words, werMin=min(r["wer"] for r in selected),
                werMax=max(r["wer"] for r in selected),
                medianInferenceMs=statistics.median(r["inferenceMs"] for r in selected),
                totalInferenceMs=sum(r["inferenceMs"] for r in selected),
                medianLoadMs=statistics.median(r["loadMs"] for r in selected),
                medianRtf=statistics.median(r["rtf"] for r in selected)))
    safe_report = dict(normalization="word edit distance; lowercase, punctuation and diacritics ignored; no numeric/technical aliases",
        device="Pixel 8 AVD API35 x86_64, 4096-byte pages; not Samsung/16KB/microphone",
        voskPreparationMs=report["voskPreparationMs"], sherpaPreparationMs=report["sherpaPreparationMs"],
        rows=rows, aggregates=aggregates)
    args.output.write_text(json.dumps(safe_report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(aggregates, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
