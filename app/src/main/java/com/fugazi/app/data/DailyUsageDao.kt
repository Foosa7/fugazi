package com.fugazi.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DailyUsageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: DailyUsage)

    @Query("SELECT * FROM daily_usage ORDER BY epochDay DESC LIMIT :n")
    suspend fun recent(n: Int): List<DailyUsage>

    @Query("SELECT * FROM daily_usage ORDER BY epochDay DESC LIMIT 1")
    suspend fun latest(): DailyUsage?
}
