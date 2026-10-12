package com.yezvi.assistant.ui.navigation

import android.net.Uri
import com.yezvi.assistant.R

internal sealed class Destination(val route: String, val labelRes: Int) {
    data object Today : Destination("today", R.string.nav_today)
    data object Memory : Destination("memory", R.string.nav_memory)
    data object Capture : Destination("capture", R.string.nav_capture)
    data object Settings : Destination("settings", R.string.nav_settings)
    data object EntryDetail : Destination("entry/{entryId}", R.string.entry_detail_title) {
        fun routeFor(entryId: String): String = "entry/${Uri.encode(entryId)}"
    }
}
