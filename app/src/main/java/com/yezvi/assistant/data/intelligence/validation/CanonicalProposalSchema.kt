package com.yezvi.assistant.data.intelligence.validation

import com.yezvi.assistant.domain.intelligence.ProposalRejectionCode
import java.security.MessageDigest

internal data class SchemaViolation(val path: String)

/** Evaluates only the vocabulary used by our hash-pinned 1.0.0 artifact. Not a general schema SDK. */
internal class CanonicalProposalSchema private constructor(private val root: JsonObject) {
    private val patterns = mutableMapOf<String, Regex>()
    init { inspect(root) }

    fun violation(value: JsonValue): SchemaViolation? {
        var work = 0
        fun visit(instance: JsonValue, schema: JsonObject, path: String): SchemaViolation? {
            if (++work > 300000) throw ValidationAbort(ProposalRejectionCode.PROCESSING_LIMIT, path)
            fun bad() = SchemaViolation(path)
            schema["\$ref"]?.let {
                val ref = it.string()
                val target = root.o("\$defs")[ref.removePrefix("#/\$defs/")]!!.obj()
                visit(instance, target, path)?.let { failure -> return failure }
            }
            schema["type"]?.string()?.let { type ->
                val matches = when (type) {
                    "object" -> instance is JsonObject
                    "array" -> instance is JsonArray
                    "string" -> instance is JsonString
                    "number" -> instance is JsonNumber
                    "integer" -> instance is JsonNumber && instance.value.scale() <= 0
                    "boolean" -> instance is JsonBoolean
                    "null" -> instance === JsonNull
                    else -> false
                }
                if (!matches) return bad()
            }
            schema["const"]?.let { if (instance != it) return bad() }
            schema["enum"]?.let { if (instance !in it.array()) return bad() }
            if (instance is JsonString) {
                val count = instance.value.codePointCount(0, instance.value.length)
                if (schema["minLength"]?.integer()?.let { count < it } == true ||
                    schema["maxLength"]?.integer()?.let { count > it } == true) return bad()
                schema["pattern"]?.let { if (!patterns.getValue(it.string()).containsMatchIn(instance.value)) return bad() }
                // Format assertions are deliberately enabled, matching the desktop fixture checker.
                if (schema["format"] == JsonString("date") && !CalendarChecks.validDate(instance.value)) return bad()
            }
            if (instance is JsonNumber) {
                if (schema["minimum"]?.let { instance.value < (it as JsonNumber).value } == true ||
                    schema["maximum"]?.let { instance.value > (it as JsonNumber).value } == true) return bad()
            }
            if (instance is JsonObject) {
                val properties = (schema["properties"] as? JsonObject)?.fields.orEmpty()
                schema["required"]?.array()?.forEach { if (instance[it.string()] == null) return SchemaViolation("$path/${it.string()}") }
                if (schema["minProperties"]?.integer()?.let { instance.fields.size < it } == true) return bad()
                if (schema["additionalProperties"] == JsonBoolean(false) && instance.fields.keys.any { it !in properties }) return bad()
                for ((key, sub) in properties) instance[key]?.let { child ->
                    visit(child, sub.obj(), "$path/$key")?.let { return it }
                }
                (schema["dependentRequired"] as? JsonObject)?.fields?.forEach { (key, deps) ->
                    if (instance[key] != null && deps.array().any { instance[it.string()] == null }) return bad()
                }
            }
            if (instance is JsonArray) {
                if (schema["minItems"]?.integer()?.let { instance.values.size < it } == true ||
                    schema["maxItems"]?.integer()?.let { instance.values.size > it } == true) return bad()
                if (schema["uniqueItems"] == JsonBoolean(true) && instance.values.distinct().size != instance.values.size) return bad()
                schema["items"]?.obj()?.let { item ->
                    instance.values.forEachIndexed { index, child -> visit(child, item, "$path/$index")?.let { return it } }
                }
            }
            schema["allOf"]?.array()?.forEach { visit(instance, it.obj(), path)?.let { failure -> return failure } }
            schema["anyOf"]?.array()?.let { branches -> if (branches.none { visit(instance, it.obj(), path) == null }) return bad() }
            schema["oneOf"]?.array()?.let { branches -> if (branches.count { visit(instance, it.obj(), path) == null } != 1) return bad() }
            schema["not"]?.obj()?.let { if (visit(instance, it, path) == null) return bad() }
            schema["if"]?.obj()?.let { condition ->
                if (visit(instance, condition, path) == null) schema["then"]?.obj()?.let { visit(instance, it, path)?.let { failure -> return failure } }
            }
            return null
        }
        return visit(value, root, "")
    }

    private fun inspect(schema: JsonObject) {
        check(schema.fields.keys.all { it in KEYWORDS })
        schema["pattern"]?.let { patterns[it.string()] = Regex(it.string()) }
        schema["format"]?.let { check(it == JsonString("date")) }
        schema["\$ref"]?.let {
            check(it.string().startsWith("#/\$defs/"))
            check(root.o("\$defs")[it.string().removePrefix("#/\$defs/")] is JsonObject)
        }
        listOf("properties", "\$defs").forEach { key ->
            (schema[key] as? JsonObject)?.fields?.values?.forEach { inspect(it.obj()) }
        }
        listOf("allOf", "anyOf", "oneOf").forEach { key -> schema[key]?.array()?.forEach { inspect(it.obj()) } }
        listOf("items", "if", "then", "not").forEach { key -> schema[key]?.let { inspect(it.obj()) } }
    }

    companion object {
        const val SHA256 = "724680a219eade0de966a8ae33bf79531c5b7b3d440c4a21921055703f45d8a5"
        private val KEYWORDS = setOf("\$schema", "\$id", "title", "\$comment", "\$defs", "\$ref", "type", "const", "enum",
            "properties", "required", "additionalProperties", "minProperties", "dependentRequired", "items", "minItems", "maxItems",
            "uniqueItems", "minLength", "maxLength", "pattern", "format", "minimum", "maximum", "allOf", "anyOf", "oneOf", "if", "then", "not")
        val instance: CanonicalProposalSchema by lazy {
            val bytes = checkNotNull(CanonicalProposalSchema::class.java.getResourceAsStream("/proposals.schema.json"))
                .use { it.readBytes() }
            check(bytes.size <= BoundedJson.MAX_BYTES)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            check(hash == SHA256)
            CanonicalProposalSchema(BoundedJson(bytes.toString(Charsets.UTF_8)).parse().obj())
        }
    }
}
