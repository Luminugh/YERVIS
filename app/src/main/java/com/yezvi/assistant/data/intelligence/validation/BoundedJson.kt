package com.yezvi.assistant.data.intelligence.validation

import com.yezvi.assistant.domain.intelligence.ProposalRejectionCode
import java.math.BigDecimal

/** Internal syntax tree, not a second information taxonomy. No implicit number/string coercion. */
internal sealed interface JsonValue
internal data class JsonObject(val fields: Map<String, JsonValue>) : JsonValue {
    operator fun get(key: String): JsonValue? = fields[key]
}
internal data class JsonArray(val values: List<JsonValue>) : JsonValue
internal data class JsonString(val value: String) : JsonValue
internal data class JsonNumber(val value: BigDecimal) : JsonValue
internal data class JsonBoolean(val value: Boolean) : JsonValue
internal data object JsonNull : JsonValue
internal fun JsonValue.obj() = this as JsonObject
internal fun JsonValue.array() = (this as JsonArray).values
internal fun JsonValue.string() = (this as JsonString).value
internal fun JsonObject.s(key: String) = get(key)!!.string()
internal fun JsonObject.o(key: String) = get(key)!!.obj()
internal fun JsonObject.a(key: String) = get(key)!!.array()
internal fun JsonObject.field(key: String) = get(key)?.obj()?.get("value")
internal fun JsonObject.text(key: String) = (field(key) as? JsonString)?.value
internal fun JsonValue.integer() = (this as JsonNumber).value.intValueExact()

internal class ValidationAbort(val code: ProposalRejectionCode, val path: String = "", val offset: Int? = null) : RuntimeException() {
    // Never attach provider text or parser exceptions to a diagnostic.
    override fun fillInStackTrace(): Throwable = this
}

/** Strict bounded RFC-8259 parser for the pinned local schema, no IO or external references. */
internal class BoundedJson(private val text: String, private val maxNodes: Int = 24000) {
    private var position = 0
    private var nodes = 0

    fun parse(): JsonValue {
        if (text.length > MAX_BYTES) fail(ProposalRejectionCode.PAYLOAD_LIMIT)
        // Count before allocating an encoded copy; also reject unpaired literal surrogates.
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val c = text[i++]
            bytes += when {
                c.isHighSurrogate() -> {
                    if (i == text.length || !text[i++].isLowSurrogate()) fail(ProposalRejectionCode.INVALID_UNICODE)
                    4
                }
                c.isLowSurrogate() -> fail(ProposalRejectionCode.INVALID_UNICODE)
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                else -> 3
            }
            if (bytes > MAX_BYTES) fail(ProposalRejectionCode.PAYLOAD_LIMIT)
        }
        whitespace()
        val result = value(0)
        whitespace()
        if (position != text.length) fail()
        return result
    }

    private fun value(depth: Int): JsonValue {
        if (++nodes > maxNodes || depth > MAX_DEPTH) fail(ProposalRejectionCode.PROCESSING_LIMIT)
        whitespace()
        return when (peek()) {
            '{' -> {
                position++
                val fields = linkedMapOf<String, JsonValue>()
                whitespace()
                if (!consume('}')) do {
                    whitespace()
                    if (peek() != '"') fail()
                    val key = string()
                    if (fields.containsKey(key)) fail(ProposalRejectionCode.DUPLICATE_KEY)
                    whitespace(); expect(':')
                    fields[key] = value(depth + 1)
                    whitespace()
                    if (consume('}')) break
                    expect(',')
                } while (true)
                JsonObject(fields)
            }
            '[' -> {
                position++
                val values = arrayListOf<JsonValue>()
                whitespace()
                if (!consume(']')) do {
                    values += value(depth + 1)
                    whitespace()
                    if (consume(']')) break
                    expect(',')
                } while (true)
                JsonArray(values)
            }
            '"' -> JsonString(string())
            't' -> literal("true", JsonBoolean(true))
            'f' -> literal("false", JsonBoolean(false))
            'n' -> literal("null", JsonNull)
            '-', in '0'..'9' -> number()
            else -> fail()
        }
    }

    private fun string(): String {
        expect('"')
        val result = StringBuilder()
        while (true) {
            var c = peek() ?: fail()
            position++
            if (c == '"') break
            if (c.code < 32) fail()
            if (c == '\\') {
                c = peek() ?: fail(); position++
                c = when (c) {
                    '"', '\\', '/' -> c
                    'b' -> '\b'; 'f' -> '\u000c'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                    'u' -> {
                        if (position + 4 > text.length) fail()
                        val n = text.substring(position, position + 4).toIntOrNull(16) ?: fail()
                        // toIntOrNull accepts a sign; JSON hex escapes don't.
                        if (!(position until position + 4).all { text[it] in "0123456789abcdefABCDEF" }) fail()
                        position += 4; n.toChar()
                    }
                    else -> fail()
                }
            }
            result.append(c)
        }
        var index = 0
        while (index < result.length) {
            val c = result[index++]
            if (c.isHighSurrogate()) {
                if (index == result.length || !result[index++].isLowSurrogate()) fail(ProposalRejectionCode.INVALID_UNICODE)
            } else if (c.isLowSurrogate()) fail(ProposalRejectionCode.INVALID_UNICODE)
        }
        return result.toString()
    }

    private fun number(): JsonNumber {
        val start = position
        consume('-')
        if (!consume('0')) {
            if (peek() !in '1'..'9') fail()
            while (peek() in '0'..'9') position++
        }
        if (consume('.')) {
            if (peek() !in '0'..'9') fail()
            while (peek() in '0'..'9') position++
        }
        if (consume('e') || consume('E')) {
            if (!consume('+')) consume('-')
            if (peek() !in '0'..'9') fail()
            while (peek() in '0'..'9') position++
        }
        if (position - start > 128) fail(ProposalRejectionCode.PROCESSING_LIMIT)
        val number = try { BigDecimal(text.substring(start, position)) } catch (_: NumberFormatException) { fail() }
        if (number.scale() !in -1000..1000) fail(ProposalRejectionCode.PROCESSING_LIMIT)
        return JsonNumber(number.stripTrailingZeros())
    }

    private fun <T : JsonValue> literal(word: String, result: T): T {
        if (!text.startsWith(word, position)) fail()
        position += word.length
        return result
    }
    private fun whitespace() {
        while (true) when (peek()) { ' ', '\r', '\n', '\t' -> position++; else -> return }
    }
    private fun peek(): Char? = text.getOrNull(position)
    private fun consume(c: Char): Boolean = if (peek() == c) { position++; true } else false
    private fun expect(c: Char) { if (!consume(c)) fail() }
    private fun fail(code: ProposalRejectionCode = ProposalRejectionCode.MALFORMED_JSON): Nothing =
        throw ValidationAbort(code, offset = position)

    companion object { const val MAX_BYTES = 262144; const val MAX_DEPTH = 32 }
}
