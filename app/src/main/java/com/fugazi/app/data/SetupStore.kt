package com.fugazi.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** The user's own words, written on a good day. Shown verbatim at the bottom of the curve. */
const val DEFAULT_MESSAGE = "Phone out of the bedroom. Clothes off the floor. That's the whole climb."
const val DEFAULT_KEYSTONE = "Phone out of the bedroom."

private val Context.dataStore by preferencesDataStore(name = "fugazi_setup")

/**
 * The only configuration this app has. Written once on a good day; read by the
 * background radar. There is intentionally no settings screen.
 */
class SetupStore(private val context: Context) {

    private object Keys {
        val SETUP_DONE = booleanPreferencesKey("setup_done")
        val MESSAGE = stringPreferencesKey("intervention_message")
        val KEYSTONE = stringPreferencesKey("keystone_action")
        val LATE_START = intPreferencesKey("late_start_hour")
        val LATE_END = intPreferencesKey("late_end_hour")
        val LAST_TRIP = longPreferencesKey("last_trip_epoch_day")
    }

    val config: Flow<SetupConfig> = context.dataStore.data.map { p ->
        SetupConfig(
            setupDone = p[Keys.SETUP_DONE] ?: false,
            message = p[Keys.MESSAGE] ?: DEFAULT_MESSAGE,
            keystone = p[Keys.KEYSTONE] ?: DEFAULT_KEYSTONE,
            lateStartHour = p[Keys.LATE_START] ?: 23,
            lateEndHour = p[Keys.LATE_END] ?: 3,
            lastTripEpochDay = p[Keys.LAST_TRIP] ?: -1L,
        )
    }

    suspend fun read(): SetupConfig = config.first()

    suspend fun saveSetup(message: String, keystone: String, lateStartHour: Int, lateEndHour: Int) {
        context.dataStore.edit { p ->
            p[Keys.MESSAGE] = message.trim()
            p[Keys.KEYSTONE] = keystone.trim()
            p[Keys.LATE_START] = lateStartHour
            p[Keys.LATE_END] = lateEndHour
            p[Keys.SETUP_DONE] = true
        }
    }

    suspend fun markTrip(epochDay: Long) {
        context.dataStore.edit { it[Keys.LAST_TRIP] = epochDay }
    }
}

data class SetupConfig(
    val setupDone: Boolean,
    val message: String,
    val keystone: String,
    val lateStartHour: Int,
    val lateEndHour: Int,
    val lastTripEpochDay: Long,
)
