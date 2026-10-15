package com.yezvi.assistant.intelligence

import com.yezvi.assistant.domain.intelligence.*
import com.yezvi.assistant.domain.memory.*
import java.io.File

/** Synthetic F4.02 metadata only; JSON responses remain the canonical files in docs. */
internal object F402Fixtures {
    val root: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .map { File(it, "docs/CONTRATOS/IA/v1") }
        .first { File(it, "proposals.schema.json").isFile }

    private fun source(id: String, text: String, type: CaptureSourceType, captured: Long,
                       zone: String, correction: Long? = null): InformationExtractionSource {
        val entry = MemoryEntry(id, if (correction == null) text else "Original sintético anterior.",
            type, captured, zone, CaptureStatus.SAVED)
        val version = correction?.let { MemoryTextVersion(id, text, it, captured + 1000L) }
        return InformationExtractionSource.from(entry, version)
    }

    private val sources = mapOf(
        "s-multiple" to source("s-multiple", "Ayer entregué el informe del proyecto Atlas. Mañana tengo reunión con Ana. Debo preparar el resumen para Atlas.", CaptureSourceType.TEXT, 1792083600000L, "America/Lima"),
        "s-weekly" to source("s-weekly", "Los lunes y miércoles estudio por la tarde. Esta semana no estudio el miércoles.", CaptureSourceType.TEXT, 1792083600000L, "America/Lima"),
        "s-observed-0" to source("s-observed-0", "2026-10-05 estudié por la tarde.", CaptureSourceType.TEXT, 1791219600000L, "America/Lima"),
        "s-observed-1" to source("s-observed-1", "2026-10-12 estudié por la tarde.", CaptureSourceType.TEXT, 1791824400000L, "America/Lima"),
        "s-observed-2" to source("s-observed-2", "2026-10-19 estudié por la tarde.", CaptureSourceType.TEXT, 1792429200000L, "America/Lima"),
        "s-ambiguous" to source("s-ambiguous", "Quizá tenga reunión el viernes por la tarde.", CaptureSourceType.TEXT, 1792083600000L, "America/Lima"),
        "s-negation" to source("s-negation", "No tengo reunión el viernes.", CaptureSourceType.TEXT, 1792083600000L, "America/Lima"),
        "s-completed" to source("s-completed", "Ya terminé el informe.", CaptureSourceType.TEXT, 1792083600000L, "America/Lima"),
        "s-reminder" to source("s-reminder", "Recuérdame llamar a Ana mañana.", CaptureSourceType.TEXT, 1792083600000L, "America/Lima"),
        "s-change-old" to source("s-change-old", "Ana trabaja por la mañana.", CaptureSourceType.TEXT, 1791651600000L, "America/Lima"),
        "s-change-new" to source("s-change-new", "Creo que Ana trabaja por la tarde.", CaptureSourceType.TEXT, 1792083600000L, "America/Lima"),
        "s-insufficient" to source("s-insufficient", "¿Él ya lo hizo?", CaptureSourceType.TEXT, 1792083600000L, "America/Lima"),
        "s-complement" to source("s-complement", "Quiero crear una aplicación. Mi idea es una agenda. Decidí usar texto primero. Quiero leer tres veces por semana y hacer seguimiento.", CaptureSourceType.TEXT, 1792083600000L, "America/Lima"),
        "s-person-0" to source("s-person-0", "En la reunión del 2026-10-05, Ana pidió un resumen escrito.", CaptureSourceType.TEXT, 1791219600000L, "America/Lima"),
        "s-person-1" to source("s-person-1", "En la reunión del 2026-10-12, Ana pidió un resumen escrito.", CaptureSourceType.TEXT, 1791824400000L, "America/Lima"),
        "s-person-2" to source("s-person-2", "En la reunión del 2026-10-19, Ana pidió un resumen escrito.", CaptureSourceType.TEXT, 1792429200000L, "America/Lima"),
        "s-corrected" to source("s-corrected", "📝 Ayer envié el informe.\r\nFue el sábado.", CaptureSourceType.VOICE, 1792083600000L, "America/Lima", 9007199254740993L),
        "s-personality" to source("s-personality", "Creo que Ana es reservada en las reuniones del curso.", CaptureSourceType.TEXT, 1792083600000L, "America/Lima"),
        "s-resolved" to source("s-resolved", "Reunión 2026-10-20 15:00 America/Lima.", CaptureSourceType.TEXT, 1792083600000L, "America/Lima"),
    )

    val expectedHashes = mapOf(
        "s-multiple" to "af8ea2e4c2d8ab65506bc22bfd81439525944a589caf7fb9933877ec4dec0440",
        "s-weekly" to "5b29d45dee4bd525ebab055cc7d1446a2f93d7ec38962b18c2f8c5955ca0e613",
        "s-observed-0" to "01dc25c9fa7c8c1fc3e5a10c52bfdda4dc49578fd07eed211c886dff245a283c",
        "s-observed-1" to "1a4403ffa69c7125cfe0cfc95f8bdd983de3b03436a37de5ab875390f4d49622",
        "s-observed-2" to "23f6f52fc5c8049dd14f99327843de6d314c4fcd2edcaefa7fd3f788ed576841",
        "s-ambiguous" to "bd3959fce2df8adfd6185f90549645fa576a17046e0119021add5bba1139fe0a",
        "s-negation" to "7cbc3fb21ee7d16c7c87c29f23c044603a75a00850c69a17ebcd7c5968c3154f",
        "s-completed" to "842aeb73acc20b8916571b512248c7ec31cacbfdf6bd5b1c280e6a9edfda2a5f",
        "s-reminder" to "ce20768a132b8d38d8223fc1a9e856a202caf3ba7737964ffa3ffbcf8fac6d69",
        "s-change-old" to "cef1aa570973bb256b10038da10ffec74c72ce5abfcf97d0cff8a9dc6bc6172b",
        "s-change-new" to "bdec395626cfd7f9b95d1fe3a62475346db36b4a1ace02c25ddb5de8ccf982a5",
        "s-insufficient" to "12dda171abb766684ad54b61cccca058a858fff9eb64f47cd3e08c69339852b7",
        "s-complement" to "54dea3b523a22476cce699ff3194b4237dec79cfe4c739dcbcdd115ae7a52a54",
        "s-person-0" to "029c62af0e651246cb3a5067fc9d954579edd7a7e16d7a65a871e41eaeebacac",
        "s-person-1" to "7a5323985d3184fe4be898b51b8708ce78cb14826e0d2963f68df903c266f160",
        "s-person-2" to "19cbe66c9780f922108120463b1049a96bc76ca73d7a77438be331871ff29971",
        "s-corrected" to "f6f9e56b05d2d403de99eb1e2b95c320777923eae9b49c35f08ae0a2863ded5e",
        "s-personality" to "bd258639528253101bb74b218ffecc686d3f6f9a445a9b1e2fc1bffb1aee5fd6",
        "s-resolved" to "4fafce55c84faa807fd68fdf2cc214cbd5e59fecb4214c3d07bb7fdd6c590fa5",
    )

    val cases: List<Case> = listOf(
        Case("01-multiple", InformationExtractionRequest("request-01-multiple", "extraction-01-multiple", listOf(sources.getValue("s-multiple"))),
            ExtractionOutcome.COMPLETE, File(root, "fixtures/valid/01-multiple.json").readText(Charsets.UTF_8), "0fdc8033c52fb97318cf5bfd17ca4940958d906dc8e9423acc22e313dc741e64"),
        Case("02-rutina-semanal", InformationExtractionRequest("request-02-rutina-semanal", "extraction-02-rutina-semanal", listOf(sources.getValue("s-weekly"))),
            ExtractionOutcome.COMPLETE, File(root, "fixtures/valid/02-rutina-semanal.json").readText(Charsets.UTF_8), "12ffd96141e71546316c76c1276f47506c3812df7cc77110b16f375adc71a250"),
        Case("03-patron-observado", InformationExtractionRequest("request-03-patron-observado", "extraction-03-patron-observado", listOf(sources.getValue("s-observed-0"), sources.getValue("s-observed-1"), sources.getValue("s-observed-2"))),
            ExtractionOutcome.COMPLETE, File(root, "fixtures/valid/03-patron-observado.json").readText(Charsets.UTF_8), "18cc9058d08cf8b95e979f56f82c3ceae2e7000db2ab74c8549a9cd35ae5ec96"),
        Case("04-evento-ambiguo", InformationExtractionRequest("request-04-evento-ambiguo", "extraction-04-evento-ambiguo", listOf(sources.getValue("s-ambiguous"))),
            ExtractionOutcome.PARTIAL, File(root, "fixtures/valid/04-evento-ambiguo.json").readText(Charsets.UTF_8), "e4c8e3722e7dde24138f80e41614aaad0c0c201564b170cdc72a3cb59b2bfcb5"),
        Case("05-negacion", InformationExtractionRequest("request-05-negacion", "extraction-05-negacion", listOf(sources.getValue("s-negation"))),
            ExtractionOutcome.COMPLETE, File(root, "fixtures/valid/05-negacion.json").readText(Charsets.UTF_8), "1dd94864b8434818f40bb1b0f3fa164e4e48cc762dcb30dd4b02c0998d77e817"),
        Case("06-tarea-completada", InformationExtractionRequest("request-06-tarea-completada", "extraction-06-tarea-completada", listOf(sources.getValue("s-completed"))),
            ExtractionOutcome.COMPLETE, File(root, "fixtures/valid/06-tarea-completada.json").readText(Charsets.UTF_8), "0b314c6f4b32a475d9f8cabb180dc21b7cd2fea492b0dd6a11f840cc68fca89f"),
        Case("07-recordatorio-explicito", InformationExtractionRequest("request-07-recordatorio-explicito", "extraction-07-recordatorio-explicito", listOf(sources.getValue("s-reminder"))),
            ExtractionOutcome.COMPLETE, File(root, "fixtures/valid/07-recordatorio-explicito.json").readText(Charsets.UTF_8), "a8f8d8ee90c12ba4c1726b7bc0afc6ddd73f2a58332573029f0fa16c40d00a14"),
        Case("08-actualizacion-contradictoria", InformationExtractionRequest("request-08-actualizacion-contradicto", "extraction-08-actualizacion-contradicto", listOf(sources.getValue("s-change-old"), sources.getValue("s-change-new"))),
            ExtractionOutcome.PARTIAL, File(root, "fixtures/valid/08-actualizacion-contradictoria.json").readText(Charsets.UTF_8), "6635846282a285fa33f3780f17244dec78f61ba281031aa8331552ee8eaa1506"),
        Case("09-evidencia-insuficiente", InformationExtractionRequest("request-09-evidencia-insuficiente", "extraction-09-evidencia-insuficiente", listOf(sources.getValue("s-insufficient"))),
            ExtractionOutcome.NO_PROPOSALS, File(root, "fixtures/valid/09-evidencia-insuficiente.json").readText(Charsets.UTF_8), "b0b275d8fe8bf4043a51a7e09e7ab611a7e8f1841a43d12ab77e346c6156953b"),
        Case("10-idea-decision-objetivo-habito", InformationExtractionRequest("request-10-idea-decision-objetivo-ha", "extraction-10-idea-decision-objetivo-ha", listOf(sources.getValue("s-complement"))),
            ExtractionOutcome.COMPLETE, File(root, "fixtures/valid/10-idea-decision-objetivo-habito.json").readText(Charsets.UTF_8), "25b714a4c4441eb32a1c2c5ad7e98056e2b4ab2ce1c4bc6f1a1d6a401b4fedeb"),
        Case("11-conclusion-personal-acotada", InformationExtractionRequest("request-11-conclusion-personal-acota", "extraction-11-conclusion-personal-acota", listOf(sources.getValue("s-person-0"), sources.getValue("s-person-1"), sources.getValue("s-person-2"))),
            ExtractionOutcome.COMPLETE, File(root, "fixtures/valid/11-conclusion-personal-acotada.json").readText(Charsets.UTF_8), "ca5fbeb5852bd1474d7fe02f18226714ebb16a1b216a0ffed6cf55aafce29360"),
        Case("12-revision-utf16", InformationExtractionRequest("request-12-revision-utf16", "extraction-12-revision-utf16", listOf(sources.getValue("s-corrected"))),
            ExtractionOutcome.COMPLETE, File(root, "fixtures/valid/12-revision-utf16.json").readText(Charsets.UTF_8), "a71592e2bee6d483f180b883f503aee3a4c021f9befc6f549b58b2132d0b99ad"),
        Case("13-descripcion-atribuida", InformationExtractionRequest("request-13-descripcion-atribuida", "extraction-13-descripcion-atribuida", listOf(sources.getValue("s-personality"))),
            ExtractionOutcome.COMPLETE, File(root, "fixtures/valid/13-descripcion-atribuida.json").readText(Charsets.UTF_8), "c4038c0a35340633d89c4474a5a464b1f3f81d6216d8e0258d4ea93f57abf863"),
        Case("14-tiempo-resuelto", InformationExtractionRequest("request-14-tiempo-resuelto", "extraction-14-tiempo-resuelto", listOf(sources.getValue("s-resolved"))),
            ExtractionOutcome.COMPLETE, File(root, "fixtures/valid/14-tiempo-resuelto.json").readText(Charsets.UTF_8), "defd73d70e61894fe77a5c9757ee87485c419b3068ee0ad72057e4d7474f3d5c"),
    )

    data class Case(val name: String, val request: InformationExtractionRequest,
                    val outcome: ExtractionOutcome, val json: String, val sha256: String)
}
