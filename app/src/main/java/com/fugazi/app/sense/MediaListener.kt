package com.fugazi.app.sense

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import java.time.LocalDate

/**
 * Counts how music is listened to: tracks started, tracks skipped, and long-form plays
 * (podcasts, mixes). Fewer skips and more letting-it-play is a sign of a mindful stretch.
 *
 * It's a notification listener only because that's what Android requires to see media
 * sessions from other apps. It never reads notifications, and it doesn't record what you
 * listen to — only counts.
 */
class MediaListener : NotificationListenerService() {

    private val trackers = mutableMapOf<MediaSession.TokenKey, Tracker>()
    private var msm: MediaSessionManager? = null
    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list -> sync(list.orEmpty()) }

    override fun onListenerConnected() {
        val m = getSystemService(MediaSessionManager::class.java)
        msm = m
        val me = ComponentName(this, MediaListener::class.java)
        runCatching {
            m.addOnActiveSessionsChangedListener(sessionsChanged, me)
            sync(m.getActiveSessions(me))
        }
    }

    override fun onListenerDisconnected() {
        runCatching { msm?.removeOnActiveSessionsChangedListener(sessionsChanged) }
        trackers.values.forEach { it.release() }
        trackers.clear()
    }

    private fun sync(controllers: List<MediaController>) {
        val live = controllers.associateBy { MediaSession.TokenKey(it.sessionToken.hashCode(), it.packageName) }
        (trackers.keys - live.keys).forEach { trackers.remove(it)?.release() }
        live.forEach { (k, c) -> if (k !in trackers) trackers[k] = Tracker(this, c) }
    }

    private object MediaSession {
        data class TokenKey(val token: Int, val pkg: String)
    }

    /**
     * Follows one player. On a track change it decides whether the previous track was
     * skipped: it counts as a skip when playback got no closer than [SKIP_MARGIN_MS] to
     * the end. Position is tracked as the furthest point reached, so a state update that
     * belongs to the *next* track (position 0) can't make a finished track look skipped.
     */
    private class Tracker(private val ctx: Context, private val c: MediaController) : MediaController.Callback() {
        private var key: String? = null
        private var durationMs = 0L
        private var furthestMs = 0L
        private var state: PlaybackState? = null

        init {
            // Adopt whatever is already playing without counting it: the listener reconnects
            // whenever the process restarts, and that isn't you starting a track.
            key = c.metadata?.let(::keyOf)
            durationMs = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            state = c.playbackState
            c.registerCallback(this)
        }

        fun release() = c.unregisterCallback(this)

        override fun onPlaybackStateChanged(s: PlaybackState?) {
            advance()
            state = s
        }

        override fun onMetadataChanged(m: MediaMetadata?) {
            val newKey = m?.let(::keyOf)
            if (newKey == null || newKey == key) return
            advance()
            key?.let { finishPrevious() }
            key = newKey
            durationMs = m.getLong(MediaMetadata.METADATA_KEY_DURATION)
            furthestMs = 0L
            MediaLog.add(ctx, LocalDate.now(), started = 1, longForm = if (durationMs >= LONG_FORM_MS) 1 else 0)
        }

        private fun keyOf(m: MediaMetadata) =
            listOf(m.getString(MediaMetadata.METADATA_KEY_TITLE), m.getString(MediaMetadata.METADATA_KEY_ARTIST))
                .joinToString("|")

        /** Push [furthestMs] forward to where the last known playing state would be now. */
        private fun advance() {
            val s = state ?: return
            val pos = if (s.state == PlaybackState.STATE_PLAYING) {
                s.position + ((SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed).toLong()
            } else s.position
            if (pos > furthestMs) furthestMs = pos
        }

        private fun finishPrevious() {
            // Unknown or very short durations (ads, previews) can't be judged.
            if (durationMs < MIN_JUDGED_MS) return
            if (furthestMs < durationMs - SKIP_MARGIN_MS) MediaLog.add(ctx, LocalDate.now(), skipped = 1)
        }
    }

    companion object {
        const val SKIP_MARGIN_MS = 20_000L
        const val MIN_JUDGED_MS = 60_000L
        const val LONG_FORM_MS = 20 * 60_000L

        fun component(ctx: Context) = ComponentName(ctx, MediaListener::class.java)
    }
}

/** Per-day listening counts, in SharedPreferences until the day's signals are written out. */
object MediaLog {
    private const val PREFS = "media_log"

    data class Day(val started: Int, val skipped: Int, val longForm: Int)

    @Synchronized
    fun add(ctx: Context, day: LocalDate, started: Int = 0, skipped: Int = 0, longForm: Int = 0) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = day.toString()
        p.edit()
            .putInt("$d:started", p.getInt("$d:started", 0) + started)
            .putInt("$d:skipped", p.getInt("$d:skipped", 0) + skipped)
            .putInt("$d:long", p.getInt("$d:long", 0) + longForm)
            .apply()
    }

    fun read(ctx: Context, day: LocalDate): Day? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = day.toString()
        if (!p.contains("$d:started")) return null
        return Day(p.getInt("$d:started", 0), p.getInt("$d:skipped", 0), p.getInt("$d:long", 0))
    }

    /** Whether Android has granted notification access, which is what media sessions need. */
    fun hasAccess(ctx: Context): Boolean =
        androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
}
