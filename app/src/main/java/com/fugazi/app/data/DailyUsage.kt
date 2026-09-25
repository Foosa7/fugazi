package com.fugazi.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One day's passively-collected screen signals. Keyed by epoch-day so a re-run
 * for the same day overwrites rather than duplicates.
 *
 * - [totalMinutes]: foreground time across the whole calendar day.
 * - [lateNightMinutes]: foreground time during that day's late-night window
 *   (the evening of this day rolling into the early hours of the next).
 */
@Entity(tableName = "daily_usage")
data class DailyUsage(
    @PrimaryKey val epochDay: Long,
    val totalMinutes: Long,
    val lateNightMinutes: Long,
    val computedAt: Long,
)
