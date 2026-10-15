package com.yezvi.assistant.data.intelligence.deduplication

import com.yezvi.assistant.data.intelligence.validation.*
import java.security.MessageDigest

/** Private storage value: never print descriptors, source text or digests. */
internal class CanonicalProposal(val descriptor: String, val digest: String) {
    override fun toString() = "CanonicalProposal(version=${ProposalCanonicalizer.VERSION})"
}

/** Pinned schema 1.0.0 identity, canonical JSON v1; no string normalization or semantic guessing. */
internal class ProposalCanonicalizer(
    private val digest: (String) -> String = ::proposalDigest,
) {
    fun canonicalize(json: String, proposalId: String): CanonicalProposal {
        val root = BoundedJson(json).parse().obj()
        require(root.s("schemaVersion") == "1.0.0")
        val nodes = root.a("proposals").map { it.obj() }.associateBy { it.s("proposalId") }
        require(nodes.size == root.a("proposals").size && proposalId in nodes)
        var visited = 0
        fun expand(value: JsonValue, path: List<String>, ownNode: Boolean = false): JsonValue {
            require(++visited <= 24000 && path.size <= 32)
            return when (value) {
                is JsonObject -> JsonObject(value.fields.mapNotNull { (key, child) ->
                    when {
                        ownNode && key == "proposalId" -> null
                        key in setOf("exceptionId", "occurrenceId") -> null
                        key in setOf("proposalId", "targetProposalId") -> {
                            val id = child.string()
                            val ancestor = path.indexOf(id)
                            key to if (ancestor >= 0) {
                                JsonObject(mapOf("ancestorDepth" to JsonNumber((path.size - ancestor).toBigDecimal())))
                            } else {
                                expand(nodes[id] ?: error("Invalid reference"), path + id, true)
                            }
                        }
                        key == "affectedProposalIds" -> key to JsonArray(child.array().map { ref ->
                            val id = ref.string()
                            expand(nodes[id] ?: error("Invalid reference"), listOf(id), true)
                        })
                        else -> key to expand(child, path)
                    }
                }.toMap())
                is JsonArray -> JsonArray(value.values.map { expand(it, path) })
                else -> value
            }
        }
        val issues = root.a("issues").filter { issue ->
            val affected = issue.obj()["affectedProposalIds"] as? JsonArray
            affected == null || affected.values.any { it.string() == proposalId }
        }
        val proposal = expand(nodes.getValue(proposalId), listOf(proposalId), true)
        val expandedIssues = expand(JsonArray(issues), emptyList())
        val usedSources = mutableSetOf<String>()
        fun collect(value: JsonValue) {
            when (value) {
                is JsonObject -> {
                    if (value["sourceEntryId"] is JsonString && value["revision"] is JsonObject) {
                        usedSources += canonicalJson(JsonArray(listOf(value["sourceEntryId"]!!, value["revision"]!!)))
                    }
                    value.fields.values.forEach(::collect)
                }
                is JsonArray -> value.values.forEach(::collect)
                else -> Unit
            }
        }
        collect(proposal)
        collect(expandedIssues)
        val snapshots = root.a("sourceSnapshots").filter {
            val source = it.obj()
            canonicalJson(JsonArray(listOf(source["sourceEntryId"]!!, source["revision"]!!))) in usedSources
        }
        require(snapshots.size == usedSources.size && snapshots.isNotEmpty())
        val descriptor = canonicalJson(JsonObject(mapOf(
            "canonicalizationVersion" to JsonNumber(VERSION.toBigDecimal()),
            "serializationVersion" to JsonString("schema-1.0.0-json-v1"),
            "schemaVersion" to root["schemaVersion"]!!,
            "offsetUnit" to root["offsetUnit"]!!,
            "sourceSnapshots" to JsonArray(snapshots),
            "proposal" to proposal,
            "issues" to expandedIssues,
        )))
        require(descriptor.toByteArray(Charsets.UTF_8).size <= 262144)
        val hash = digest(descriptor)
        require(hash.matches(Regex("[0-9a-f]{64}")))
        return CanonicalProposal(descriptor, hash)
    }

    companion object { const val VERSION = 1 }
}

/** Arrays in schema 1.0.0 represent unordered collections; keep multiplicity, never distinct(). */
internal fun canonicalJson(value: JsonValue): String = when (value) {
    is JsonObject -> value.fields.toSortedMap().entries.joinToString(",", "{", "}") {
        "${canonicalJson(JsonString(it.key))}:${canonicalJson(it.value)}"
    }
    is JsonArray -> value.values.map(::canonicalJson).sorted().joinToString(",", "[", "]")
    is JsonString -> buildString {
        append('"')
        for (c in value.value) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            else -> if (c.code < 32) append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }
    is JsonNumber -> value.value.stripTrailingZeros().toPlainString()
    is JsonBoolean -> value.value.toString()
    JsonNull -> "null"
}

internal fun proposalDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
