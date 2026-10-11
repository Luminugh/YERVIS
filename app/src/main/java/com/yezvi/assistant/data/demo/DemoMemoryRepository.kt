package com.yezvi.assistant.data.demo

import com.yezvi.assistant.domain.memory.MemoryEntry

/** Read-only source of synthetic memory entries for previews and demo screens. */
internal object DemoMemoryRepository {
    fun getEntries(): List<MemoryEntry> = DemoMemoryFixtures.entries

    fun getEntry(id: String): MemoryEntry? =
        DemoMemoryFixtures.entries.firstOrNull { it.id == id }
}
