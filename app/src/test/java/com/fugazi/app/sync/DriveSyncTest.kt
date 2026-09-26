package com.fugazi.app.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class DriveSyncTest {
    @Test fun onlyNewAndChangedFilesAreSent() {
        val local = mapOf("events.jsonl" to "10:2", "thoughts/2026-09-26.md" to "900:5", "habits.jsonl" to "40:1")
        val synced = mapOf("events.jsonl" to "8:1", "habits.jsonl" to "40:1", "gone.md" to "1:1")
        assertEquals(listOf("events.jsonl", "thoughts/2026-09-26.md"), DriveSync.changed(local, synced))
    }
}
