"""Regression tests for threshold independence, token mapping, and public corpus safety."""
import importlib.util
import pathlib
import tempfile
import unittest
from unittest.mock import patch


def load(name):
    path = pathlib.Path(__file__).resolve().parents[1] / name
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


evaluation = load("Evaluate-SpeechConfidence.py")
preparation = load("Prepare-SpeechConfidenceCorpus.py")


class ConfidenceEvaluationTests(unittest.TestCase):
    def test_original_word_offsets_and_punctuation_scores(self):
        rows = evaluation.word_scores("¡Árbol, CPU!", ["▁¡", "Ár", "bol", ",", "▁CPU", "!"],
                                     [-.1, -.2, -.8, -99, -.3, -99])
        self.assertEqual([r["word"] for r in rows], ["arbol", "cpu"])
        self.assertEqual([r["score"] for r in rows], [-.8, -.3])
        self.assertEqual([(r["startUtf16"], r["endUtf16"]) for r in rows], [(1, 6), (8, 11)])

    def test_supplementary_unicode_utf16_spans(self):
        rows = evaluation.word_scores("😀 voz", ["▁😀", "▁voz"], [-9, -.2])
        self.assertEqual((rows[0]["startUtf16"], rows[0]["endUtf16"]), (3, 6))

    def test_invalid_or_misaligned_scores_never_make_false_word_alerts(self):
        for text, tokens, scores, times in [
            ("voz", ["▁vox"], [-.1], [0]),
            ("voz", ["▁voz"], [], [0]),
            ("voz", ["▁voz"], [float("nan")], [0]),
            ("voz", ["▁voz"], [.1], [0]),
            ("voz", ["▁voz"], [-.1], []),
        ]:
            with self.subTest(tokens=tokens, scores=scores):
                with self.assertRaises(ValueError):
                    evaluation.word_scores(text, tokens, scores, times)

    def test_special_whitespace_tokens_and_out_of_audio_timestamps_are_rejected(self):
        for token in ("", "<unk>", "<blk>", "<s>", "</s>", "voz\talta", "▁voz\n", " voz\u00a0", "voz\x00"):
            with self.subTest(token=token):
                with self.assertRaises(ValueError):
                    evaluation.word_scores(token.replace("▁", " ").strip(), [token], [-.1], [0])
        with self.assertRaises(ValueError):
            evaluation.word_scores("voz", ["▁voz"], [-.1], [1.11], audio_duration_ms=1000)

    def test_jni_rendered_ascii_spaces_match_original_sentencepiece_boundaries(self):
        raw = evaluation.word_scores("Esta voz ¿sí?", ["▁Esta", "▁", "voz", "▁¿", "sí", "?"],
            [-.1, -20, -.2, -20, -.3, -20], [0, 0, .1, .1, .2, .2])
        jni = evaluation.word_scores("Esta voz ¿sí?", [" Esta", " ", "voz", " ¿", "sí", "?"],
            [-.1, -20, -.2, -20, -.3, -20], [0, 0, .1, .1, .2, .2])
        self.assertEqual(jni, raw)
        self.assertEqual([word["score"] for word in jni], [-.1, -.2, -.3])

    def test_substitutions_insertions_and_omissions_have_separate_labels(self):
        errors, counts = evaluation.align(["a", "b", "c"], ["a", "x", "c", "d"])
        self.assertEqual(errors, [False, True, False, True])
        self.assertEqual(counts, dict(substitutions=1, deletions=0, insertions=1))
        errors, counts = evaluation.align(["a", "b", "c"], ["a", "c"])
        self.assertEqual(errors, [False, False])
        self.assertEqual(counts["deletions"], 1)

    def test_threshold_tie_uses_lower_more_conservative_value(self):
        items = [(-3, True), (-2, False)] + [(-1, False)] * 18
        threshold, metrics = evaluation.select_threshold(items)
        self.assertEqual(threshold, -3)
        self.assertEqual(metrics["precision"], 1)

    def test_threshold_never_tunes_using_independent_labels(self):
        manifest = {"samples": [dict(fixture=name, split=split, sentenceId=index,
            reference="a b", referenceKind="official_exact_transcription")
            for index, (name, split) in enumerate((("dev", "adjustment"), ("test", "evaluation")))]}
        # Different normalized references are required across partitions.
        manifest["samples"][1]["reference"] = "c d"
        base = dict(fixture="dev", text="x b", tokens=["▁x", "▁b"], tokenLogProbabilities=[-5, -.1])
        independent = dict(fixture="test", text="c d", tokens=["▁c", "▁d"], tokenLogProbabilities=[-9, -.1])
        one = evaluation.evaluate(manifest, {"rows": [base, independent]})
        independent["text"], independent["tokens"] = "x d", ["▁x", "▁d"]
        two = evaluation.evaluate(manifest, {"rows": [base, independent]})
        self.assertEqual(one["calibrationThreshold"], two["calibrationThreshold"])
        self.assertFalse(two["automaticSuggestionsEnabled"])

    def test_partition_leakage_is_rejected_even_if_sentence_ids_differ(self):
        manifest = {"samples": [dict(fixture=name, split=split, sentenceId=index,
            reference=text, referenceKind="official_exact_transcription")
            for index, (name, split, text) in enumerate((("a", "adjustment", "Árbol, CPU"),
                                                        ("b", "evaluation", "arbol cpu")))]}
        with self.assertRaises(ValueError):
            evaluation.validate_manifest(manifest)

    def test_repeated_sentences_never_cross_partition_in_preparation(self):
        samples = [dict(sentenceId=1, referenceWords=10), dict(sentenceId=1, referenceWords=12),
                   dict(sentenceId=2, referenceWords=20), dict(sentenceId=3, referenceWords=2)]
        preparation.split_samples(samples)
        self.assertEqual(samples[0]["split"], samples[1]["split"])

    def test_missing_or_repeated_fixtures_cannot_pass_evidence_gate(self):
        manifest = {"samples": [dict(fixture="x", split="adjustment", sentenceId=1,
            reference="voz", referenceKind="official_exact_transcription")]}
        self.assertFalse(evaluation.evaluate(manifest, {"rows": []})["gates"]["corpusComplete"])
        row = dict(fixture="x", text="voz", tokens=["▁voz"], tokenLogProbabilities=[-.1])
        with self.assertRaises(ValueError):
            evaluation.evaluate(manifest, {"rows": [row, row]})

    def test_empty_audio_transcript_has_no_false_zero_score(self):
        self.assertEqual(evaluation.word_scores("", [], []), [])
        self.assertIsNone(evaluation.select_threshold([])[0])

    def test_full_archive_fallback_is_refused_before_reading_response(self):
        class FullArchiveResponse:
            status = 200
            headers = {}
            def __enter__(self):
                return self
            def __exit__(self, *_):
                return False
            def read(self, _):
                raise AssertionError("Must not read a full archive")
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory)
            previous = path / "previous"
            previous.write_bytes(b"existing")
            with patch.object(preparation.urllib.request, "urlopen", return_value=FullArchiveResponse()):
                with self.assertRaises(RuntimeError):
                    preparation.get_prefix(path / "prefix", 32, previous)
            self.assertFalse((path / "prefix").exists())

    def test_benchmark_pairs_do_not_expose_hypotheses_or_mutate_input(self):
        before = dict(fixture="x", phase="benchmark", round=0, scoresEnabled=False,
                      text="private hypothesis", tokens=["▁private"], timestamps=[0], inferenceMs=10,
                      scoreReadMs=0, pssKb=100)
        after = dict(before, scoresEnabled=True, scoreReadMs=.01, wordAnalysisMs=.02,
                     analysisWords=1, analysisUnavailable=None)
        result = evaluation.benchmark_summary({"rows": [before, after]})
        self.assertEqual(result["pairedIdentity"]["completePairs"], 1)
        self.assertEqual(result["pairedIdentity"]["changedTextPairs"], 0)
        self.assertNotIn("private hypothesis", str(result))
        self.assertNotIn("totalMeasuredMs", before)
        self.assertAlmostEqual(result["pairedTotalOverheadMs"]["median"], .03)
        self.assertEqual(result["enabled"]["analysisWords"]["median"], 1)


if __name__ == "__main__":
    unittest.main()
