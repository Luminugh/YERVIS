"""Evaluate local token scores against a frozen public corpus without leaking text.

Input Android reports may contain private hypotheses. Only aggregate counts,
latencies and a threshold selected on adjustment files are written or printed.
The independent evaluation split is never used to select the threshold.
"""
from __future__ import annotations

import argparse
import json
import math
import pathlib
import statistics
import unicodedata


def normalized_words(text: str) -> list[str]:
    text = unicodedata.normalize("NFD", text.lower())
    text = "".join(c for c in text if unicodedata.category(c) != "Mn")
    return "".join(c if c.isalnum() or c.isspace() else " " for c in text).split()


def align(reference: list[str], hypothesis: list[str]) -> tuple[list[bool], dict]:
    """WordErrorScorer's diagonal/substitution, deletion, insertion tie precedence."""
    costs = [[0] * (len(hypothesis) + 1) for _ in range(len(reference) + 1)]
    for i in range(len(reference) + 1):
        costs[i][0] = i
    for j in range(len(hypothesis) + 1):
        costs[0][j] = j
    for i, expected in enumerate(reference, 1):
        for j, actual in enumerate(hypothesis, 1):
            costs[i][j] = min(costs[i - 1][j - 1] + (expected != actual),
                              costs[i - 1][j] + 1, costs[i][j - 1] + 1)
    counts = {"substitutions": 0, "deletions": 0, "insertions": 0}
    errors = [False] * len(hypothesis)
    i, j = len(reference), len(hypothesis)
    while i or j:
        if i and j and costs[i][j] == costs[i - 1][j - 1] + (reference[i - 1] != hypothesis[j - 1]):
            if reference[i - 1] != hypothesis[j - 1]:
                errors[j - 1] = True
                counts["substitutions"] += 1
            i -= 1
            j -= 1
        elif i and costs[i][j] == costs[i - 1][j] + 1:
            counts["deletions"] += 1
            i -= 1
        else:
            errors[j - 1] = True
            counts["insertions"] += 1
            j -= 1
    return errors, counts


def word_scores(text: str, tokens: list[str], log_probs: list[float],
                timestamps: list[float] | None = None,
                audio_duration_ms: float | None = None) -> list[dict]:
    """Reconstruct exact text and map min pre-bias token logP to lexical UTF16 spans."""
    if len(tokens) != len(log_probs) or len(tokens) > 2048:
        raise ValueError("Token/score alignment or session token limit")
    if any(not isinstance(value, (int, float)) or not math.isfinite(value) or value > 0
           for value in log_probs):
        raise ValueError("Invalid raw token log probability")
    if timestamps is not None and (len(timestamps) != len(tokens) or
            any(not isinstance(value, (int, float)) or not math.isfinite(value) or value < 0
                for value in timestamps) or timestamps != sorted(timestamps)):
        raise ValueError("Invalid token timestamps")
    if not all(isinstance(token, str) for token in tokens):
        raise ValueError("Invalid token type")
    # Sherpa's Kotlin/JNI result already renders SentencePiece U+2581 as ASCII
    # spaces. Accept both forms, including standalone boundary pieces. Other
    # whitespace/control characters cannot be trusted to preserve word spans.
    if any(not token or any((c.isspace() and c != " ") or unicodedata.category(c) == "Cc"
                            for c in token) or
           token in ("<unk>", "<blk>", "<s>", "</s>") for token in tokens):
        raise ValueError("Unsupported empty, control, non-ASCII whitespace or special BPE token")
    if audio_duration_ms is not None and (not math.isfinite(audio_duration_ms) or audio_duration_ms < 0
            or timestamps is not None and any(time > audio_duration_ms / 1000 + .1 for time in timestamps)):
        raise ValueError("Token timestamp exceeds audio duration")
    expanded = [token.replace("▁", " ") for token in tokens]
    reconstructed = "".join(expanded)
    leading = len(reconstructed) - len(reconstructed.lstrip())
    if reconstructed.strip() != text:
        raise ValueError("BPE pieces do not reconstruct the exact recognized text")
    token_spans = []
    position = -leading
    for piece, score in zip(expanded, log_probs):
        token_spans.append((position, position + len(piece), float(score),
                            any(c.isalnum() for c in piece)))
        position += len(piece)
    words = []
    start = None
    for index in range(len(text) + 1):
        lexical = index < len(text) and (text[index].isalnum() or unicodedata.category(text[index]) == "Mn")
        if lexical and start is None:
            start = index
        elif not lexical and start is not None:
            normalized = normalized_words(text[start:index])
            values = [score for first, last, score, has_letters in token_spans
                      if has_letters and first < index and last > start]
            if not values or len(normalized) != 1:
                raise ValueError("Unmappable lexical word")
            words.append(dict(word=normalized[0], score=min(values),
                startUtf16=len(text[:start].encode("utf-16-le")) // 2,
                endUtf16=len(text[:index].encode("utf-16-le")) // 2))
            start = None
    if len(words) > 512 or [row["word"] for row in words] != normalized_words(text):
        raise ValueError("Word normalization mismatch or session word limit")
    return words


def alert_metrics(items: list[tuple[float, bool]], threshold: float | None) -> dict:
    flagged = [(score, error) for score, error in items if threshold is not None and score <= threshold]
    true_positive = sum(error for _, error in flagged)
    total_errors = sum(error for _, error in items)
    return dict(words=len(items), detectableErrors=total_errors, flags=len(flagged),
        trueAlerts=true_positive, falseAlerts=len(flagged) - true_positive,
        precision=true_positive / len(flagged) if flagged else None,
        recall=true_positive / total_errors if total_errors else None,
        coverage=len(flagged) / len(items) if items else 0.0)


def select_threshold(items: list[tuple[float, bool]], minimum_precision: float = .85,
                     maximum_coverage: float = .15) -> tuple[float | None, dict]:
    """Maximize recall on adjustment data only; conservative lower threshold wins ties."""
    best = None
    best_metrics = alert_metrics(items, None)
    for threshold in sorted({score for score, _ in items}):
        result = alert_metrics(items, threshold)
        if result["flags"] and result["precision"] >= minimum_precision and result["coverage"] <= maximum_coverage:
            if best is None or result["recall"] > best_metrics["recall"]:
                best, best_metrics = threshold, result
    return best, best_metrics


def validate_manifest(manifest: dict) -> dict:
    samples = manifest["samples"]
    by_fixture = {sample["fixture"]: sample for sample in samples}
    if len(by_fixture) != len(samples):
        raise ValueError("Duplicate fixture identifiers")
    sentence_splits = {}
    reference_splits = {}
    for sample in samples:
        split = sample["split"]
        if split not in ("adjustment", "evaluation") or sample.get("referenceKind") != "official_exact_transcription":
            raise ValueError("Only frozen exact public references are accepted")
        for table, key in ((sentence_splits, str(sample["sentenceId"])),
                           (reference_splits, " ".join(normalized_words(sample["reference"])))):
            if key in table and table[key] != split:
                raise ValueError("Adjustment/evaluation leakage")
            table[key] = split
    return by_fixture


def distribution(values: list[float]) -> dict | None:
    if not values:
        return None
    ordered = sorted(values)
    return dict(count=len(values), minimum=ordered[0], median=statistics.median(values),
                p95=ordered[min(len(values) - 1, math.ceil(.95 * len(values)) - 1)], maximum=ordered[-1])


def compare_baseline(manifest: dict, report: dict, baseline: dict) -> dict:
    """Compare exact text/timestamps, emitting counts only, never either hypothesis."""
    allowed = validate_manifest(manifest)
    def corpus_rows(source):
        selected = {}
        for row in source.get("rows", source.get("runs", [])):
            if row.get("phase") == "benchmark":
                continue
            fixture = row["fixture"]
            if fixture not in allowed or fixture in selected:
                raise ValueError("Unexpected or repeated baseline corpus fixture")
            selected[fixture] = row
        return selected
    before, after = corpus_rows(baseline), corpus_rows(report)
    common = set(before) & set(after)
    return dict(expectedFiles=len(allowed), comparedFiles=len(common),
        baselineComplete=set(before) == set(allowed), candidateComplete=set(after) == set(allowed),
        changedTextFiles=sum(before[key]["text"] != after[key]["text"] for key in common),
        changedTokenFiles=sum(before[key].get("tokens") != after[key].get("tokens") for key in common),
        changedTimestampFiles=sum(before[key].get("timestamps") != after[key].get("timestamps") for key in common),
        interpretation="Exact identity only; independent runs are not a controlled latency benchmark.")


def benchmark_summary(report: dict) -> dict:
    modes = {True: [], False: []}
    pairs = {}
    for row in report.get("rows", report.get("runs", [])):
        if row.get("phase") != "benchmark" or not isinstance(row.get("scoresEnabled"), bool):
            continue
        modes[row["scoresEnabled"]].append(row)
        key = (row["fixture"], row.get("round"))
        pair = pairs.setdefault(key, {})
        if row["scoresEnabled"] in pair:
            raise ValueError("Repeated benchmark mode/round")
        pair[row["scoresEnabled"]] = row
    result = {}
    for enabled, rows in modes.items():
        rows = [dict(row, totalMeasuredMs=sum(row.get(key, 0) for key in
                ("inferenceMs", "scoreReadMs", "wordAnalysisMs"))) for row in rows]
        memory = [row["pssKb"] * 1024 for row in rows if isinstance(row.get("pssKb"), (int, float))]
        result["enabled" if enabled else "disabled"] = dict(runs=len(rows), **{
            key: distribution([row[key] for row in rows if isinstance(row.get(key), (int, float))
                               and math.isfinite(row[key]) and row[key] >= 0])
            for key in ("inferenceMs", "scoreReadMs", "wordAnalysisMs", "totalMeasuredMs", "cpuMs", "nativeHeapBytes")},
            pssBytes=distribution(memory),
            analysisUnavailableRuns=sum(bool(row.get("analysisUnavailable")) for row in rows),
            analysisWords=distribution([row["analysisWords"] for row in rows
                if isinstance(row.get("analysisWords"), (int, float)) and row["analysisWords"] >= 0]))
    disabled = result["disabled"]["inferenceMs"]
    enabled = result["enabled"]["inferenceMs"]
    result["medianInferenceRatio"] = (enabled["median"] / disabled["median"]
        if disabled and enabled and disabled["median"] > 0 else None)
    disabled_total = result["disabled"]["totalMeasuredMs"]
    enabled_total = result["enabled"]["totalMeasuredMs"]
    result["medianTotalMeasuredRatio"] = (enabled_total["median"] / disabled_total["median"]
        if disabled_total and enabled_total and disabled_total["median"] > 0 else None)
    result["thirtyRunsEachMode"] = all(len(rows) >= 30 for rows in modes.values())
    complete_pairs = [pair for pair in pairs.values() if len(pair) == 2]
    result["pairedIdentity"] = dict(completePairs=len(complete_pairs),
        incompletePairs=len(pairs) - len(complete_pairs),
        changedTextPairs=sum(pair[True]["text"] != pair[False]["text"] for pair in complete_pairs),
        changedTokenPairs=sum(pair[True].get("tokens") != pair[False].get("tokens") for pair in complete_pairs),
        changedTimestampPairs=sum(pair[True].get("timestamps") != pair[False].get("timestamps") for pair in complete_pairs))
    def total(row):
        return sum(row.get(key, 0) for key in ("inferenceMs", "scoreReadMs", "wordAnalysisMs"))
    result["pairedTotalOverheadMs"] = distribution([total(pair[True]) - total(pair[False]) for pair in complete_pairs])
    result["pairedTotalOverheadFraction"] = distribution([
        total(pair[True]) / total(pair[False]) - 1 for pair in complete_pairs if total(pair[False]) > 0])
    result["pairedCpuOverheadMs"] = distribution([pair[True]["cpuMs"] - pair[False]["cpuMs"]
        for pair in complete_pairs if "cpuMs" in pair[True] and "cpuMs" in pair[False]])
    result["pairedPssDifferenceBytes"] = distribution([(pair[True]["pssKb"] - pair[False]["pssKb"]) * 1024
        for pair in complete_pairs if "pssKb" in pair[True] and "pssKb" in pair[False]])
    result["phoneBudgetVerified"] = False
    result["interpretation"] = "AVD/host timing is descriptive. ARM64 phone latency/RAM/thermal gates require physical measurements."
    return result


def evaluate(manifest: dict, report: dict, baseline: dict | None = None) -> dict:
    by_fixture = validate_manifest(manifest)
    data = {name: dict(items=[], referenceWords=0, hypothesisWords=0, substitutions=0,
                       deletions=0, insertions=0, files=0, invalidScoreFiles=0)
            for name in ("adjustment", "evaluation")}
    seen = set()
    invalid_reasons = {}
    timing = {key: [] for key in ("loadMs", "inferenceMs", "scoreReadMs", "cpuMs")}
    for row in report.get("rows", report.get("runs", [])):
        if row.get("phase") == "benchmark" or row.get("scoresEnabled") is False:
            continue
        if "tokenLogProbabilities" not in row:
            continue
        fixture = row["fixture"]
        if fixture not in by_fixture:
            raise ValueError("Report contains an unapproved fixture")
        if fixture in seen:
            raise ValueError("Repeated scored corpus fixture; benchmark repetitions require phase=benchmark")
        seen.add(fixture)
        sample = by_fixture[fixture]
        target = data[sample["split"]]
        expected, recognized = normalized_words(sample["reference"]), normalized_words(row["text"])
        errors, counts = align(expected, recognized)
        target["referenceWords"] += len(expected)
        target["hypothesisWords"] += len(recognized)
        target["files"] += 1
        for key, count in counts.items():
            target[key] += count
        try:
            scores = word_scores(row["text"], row["tokens"], row["tokenLogProbabilities"], row.get("timestamps"),
                                 sample.get("durationSeconds", 0) * 1000 if "durationSeconds" in sample else None)
            target["items"].extend((word["score"], error) for word, error in zip(scores, errors))
        except (TypeError, KeyError, ValueError) as error:
            target["invalidScoreFiles"] += 1
            reason = str(error) if isinstance(error, ValueError) else type(error).__name__
            invalid_reasons[reason] = invalid_reasons.get(reason, 0) + 1
        for key in timing:
            value = row.get(key)
            if isinstance(value, (int, float)) and math.isfinite(value) and value >= 0:
                timing[key].append(value)
    threshold, adjustment = select_threshold(data["adjustment"]["items"])
    independent = alert_metrics(data["evaluation"]["items"], threshold)
    corpus_complete = len(seen) == len(by_fixture)
    gates = dict(corpusComplete=corpus_complete,
        exactPublicReferencesOnly=True, sentenceAndReferenceDisjoint=True,
        thousandReferenceWordsPerSplit=all(d["referenceWords"] >= 1000 for d in data.values()),
        noInvalidScoreFiles=all(d["invalidScoreFiles"] == 0 for d in data.values()),
        adjustmentThresholdFound=threshold is not None,
        evaluationPrecisionAtLeast80Percent=independent["precision"] is not None and independent["precision"] >= .8,
        evaluationAtLeast30Alerts=independent["flags"] >= 30,
        evaluationAtLeast50DetectableErrors=independent["detectableErrors"] >= 50)
    summaries = {}
    for name, values in data.items():
        summaries[name] = {key: value for key, value in values.items() if key != "items"}
        summaries[name]["wer"] = (sum(values[k] for k in ("substitutions", "deletions", "insertions")) /
                                   values["referenceWords"] if values["referenceWords"] else None)
        summaries[name]["alerts"] = adjustment if name == "adjustment" else independent
        summaries[name]["scoreDistribution"] = distribution([score for score, _ in values["items"]])
    result = dict(version=1, metric="minimum unbiased emitted-token log probability per lexical BPE word",
        interpretation="Uncalibrated uncertainty heuristic; no per-word accuracy probability is inferred.",
        normalization="Lowercase, punctuation and diacritics ignored; word order retained; no aliases/number rewriting.",
        selection="Adjustment only: maximize recall with precision>=0.85 and coverage<=0.15; lower threshold wins ties.",
        calibrationThreshold=threshold, recommendedRuntimeThreshold=threshold if all(gates.values()) else None,
        automaticSuggestionsEnabled=all(gates.values()), gates=gates,
        missingCorpusFiles=len(by_fixture) - len(seen), invalidScoreReasons=invalid_reasons,
        summaries=summaries, timing={key: distribution(values) for key, values in timing.items()},
        benchmark=benchmark_summary(report),
        limitations=["Omitted words have no emitted token to highlight and are counted separately.",
                     "Speaker independence is not established by FLEURS metadata.",
                     "No extrapolation to conversational speech, technical vocabulary, or Samsung accuracy."])
    if baseline is not None:
        result["baselineComparison"] = compare_baseline(manifest, report, baseline)
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=pathlib.Path, required=True)
    parser.add_argument("--report", type=pathlib.Path, required=True)
    parser.add_argument("--baseline-report", type=pathlib.Path)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text(encoding="utf-8-sig"))
    report = json.loads(args.report.read_text(encoding="utf-8-sig"))
    baseline = (json.loads(args.baseline_report.read_text(encoding="utf-8-sig"))
                if args.baseline_report else None)
    result = evaluate(manifest, report, baseline)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(dict(automaticSuggestionsEnabled=result["automaticSuggestionsEnabled"], gates=result["gates"])))


if __name__ == "__main__":
    main()
