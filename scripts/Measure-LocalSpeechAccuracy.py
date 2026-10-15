"""Local-only WER scoring; references/transcripts stay in ignored diagnostics, never stdout.

Use the exact same manifest reference for every run. This normalization removes only
case, punctuation and diacritics; it does NOT rewrite technical words or number forms.
An approximate reference yields descriptive comparisons, not certified accuracy.
"""
import argparse
import json
import pathlib
import re
import unicodedata
import unittest


def words(text):
    text = unicodedata.normalize("NFD", text.lower())
    text = "".join(c for c in text if unicodedata.category(c) != "Mn")
    return "".join(c if c.isalnum() or c.isspace() else " " for c in text).split()


def score(reference, hypothesis):
    a, b = words(reference), words(hypothesis)
    cost = [[0] * (len(b) + 1) for _ in range(len(a) + 1)]
    for i in range(len(a) + 1):
        cost[i][0] = i
    for j in range(len(b) + 1):
        cost[0][j] = j
    for i in range(1, len(a) + 1):
        for j in range(1, len(b) + 1):
            cost[i][j] = min(cost[i - 1][j - 1] + (a[i - 1] != b[j - 1]),
                             cost[i - 1][j] + 1, cost[i][j - 1] + 1)
    i, j = len(a), len(b)
    alignment = []
    while i or j:
        if i and j and cost[i][j] == cost[i - 1][j - 1] + (a[i - 1] != b[j - 1]):
            alignment.append(("match" if a[i - 1] == b[j - 1] else "S", a[i - 1], b[j - 1]))
            i -= 1; j -= 1
        elif i and cost[i][j] == cost[i - 1][j] + 1:
            alignment.append(("D", a[i - 1], "")); i -= 1
        else:
            alignment.append(("I", "", b[j - 1])); j -= 1
    counts = {op: sum(row[0] == op for row in alignment) for op in ("S", "D", "I")}
    return dict(referenceWords=len(a), hypothesisWords=len(b), **counts,
                wer=sum(counts.values()) / max(1, len(a)), alignment=alignment[::-1])


def score_characters(reference, hypothesis):
    """CER with the same normalization as WER, including normalized interword spaces."""
    a, b = " ".join(words(reference)), " ".join(words(hypothesis))
    previous = list(range(len(b) + 1))
    for i, expected in enumerate(a, 1):
        current = [i]
        for j, actual in enumerate(b, 1):
            current.append(min(previous[j] + 1, current[j - 1] + 1,
                               previous[j - 1] + (expected != actual)))
        previous = current
    return dict(referenceCharacters=len(a), characterErrors=previous[-1],
                cer=previous[-1] / max(1, len(a)))


class ScoringTests(unittest.TestCase):
    def test_substitution_deletion_insertion(self):
        self.assertEqual(score("a b c", "a x c y")["S"], 1)
        self.assertEqual(score("a b c", "a c")["D"], 1)
        self.assertEqual(score("a b c", "a x c y")["I"], 1)

    def test_normalization_and_numbers_are_not_rewritten(self):
        self.assertEqual(score("Árbol, CPU", "arbol cpu")["wer"], 0)
        self.assertGreater(score("8792 CPU", "ocho mil setecientos noventa y dos ce pe u")["wer"], 0)

    def test_empty(self):
        self.assertEqual(score("a b", "")["D"], 2)
        self.assertEqual(score("", "a")["I"], 1)

    def test_character_errors(self):
        self.assertEqual(score_characters("abc", "axc")["cer"], 1 / 3)
        self.assertEqual(score_characters("abc", "ac")["characterErrors"], 1)
        self.assertEqual(score_characters("abc", "abxc")["characterErrors"], 1)
        self.assertEqual(score_characters("abc", "")["cer"], 1)

    def test_character_normalization_preserves_number_forms(self):
        self.assertEqual(score_characters("Árbol, CPU", "arbol cpu")["cer"], 0)
        self.assertGreater(score_characters("8792", "ocho mil setecientos noventa y dos")["cer"], 0)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=pathlib.Path, required=True)
    parser.add_argument("--report", type=pathlib.Path, required=True)
    parser.add_argument("--whisper", type=pathlib.Path)
    parser.add_argument("--output", type=pathlib.Path, required=True,
                        help="Private ignored local output; contains alignments, do not publish")
    args = parser.parse_args()
    reference = json.loads(args.manifest.read_text(encoding="utf-8-sig"))["expectedText"]
    report = json.loads(args.report.read_text(encoding="utf-8-sig"))
    runs = report["runs"]
    if args.whisper:
        whisper = json.loads(args.whisper.read_text(encoding="utf-8-sig"))
        runs.append(dict(preparation="fir", profile="whisper-base-int8", mode="contiguous25s",
                         text=" ".join(p["text"].strip() for p in whisper["parts"]),
                         initMs=whisper["initMs"], asrMs=whisper["asrMs"]))
    for run in runs:
        run.update(score(reference, run["text"]))
        run["termOccurrences"] = {term: words(run["text"]).count(term) for term in
                                  ("cpu", "discord", "edge", "8792", "4668", "yezvi", "kotlin", "nalvero")}
        summary = {key: run[key] for key in ("preparation", "profile", "mode", "S", "D", "I", "wer", "initMs", "asrMs", "termOccurrences")}
        print(json.dumps(summary, ensure_ascii=False))
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
