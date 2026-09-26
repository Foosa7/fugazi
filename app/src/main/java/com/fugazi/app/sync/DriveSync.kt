package com.fugazi.app.sync

import android.app.PendingIntent
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Mirrors journal/ — thoughts, habits, events, signals, the AI's reflections and notes —
 * into a "fugazi" folder in your own Google Drive.
 *
 * One way, phone → Drive, and it never deletes anything there: a thought you clear on the
 * phone stays in Drive. Drive also keeps earlier revisions of each file, so an overwrite
 * can be undone from drive.google.com. Only files that changed since the last sync are sent.
 *
 * Access is the `drive.file` scope: fugazi can see only what it created, nothing else in
 * your Drive.
 */
object DriveSync {
    private const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    private const val ROOT = "fugazi"

    sealed interface Status {
        data object Off : Status
        data object Syncing : Status
        data class Synced(val atMs: Long, val files: Int) : Status
        data class Failed(val message: String, val lastOkMs: Long) : Status
        /** Drive already has a fugazi folder and this phone has never synced: restore or overwrite? */
        data class FoundBackup(val modified: String) : Status
    }

    private val mutex = Mutex()
    private val _status = MutableStateFlow<Status>(Status.Off)
    val status: StateFlow<Status> = _status

    private fun request() = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    /** Starts access. Returns a Google consent screen to show, or null if access was granted without one. */
    suspend fun connect(ctx: Context): PendingIntent? = withContext(Dispatchers.IO) {
        val r = Tasks.await(Identity.getAuthorizationClient(ctx).authorize(request()))
        if (r.hasResolution()) r.pendingIntent else { onAuthorized(ctx, r); null }
    }

    /** After the consent screen, or straight from [connect]. */
    suspend fun onAuthorized(ctx: Context, r: AuthorizationResult) = withContext(Dispatchers.IO) {
        val token = r.accessToken ?: return@withContext fail(ctx, "Google didn't grant Drive access")
        mutex.withLock {
            runCatching {
                val prefs = State(ctx)
                prefs.enabled = true
                val drive = Drive(token)
                // A fresh phone with a backup already in Drive: don't overwrite it with an empty journal.
                if (prefs.synced.isEmpty() && prefs.rootId == null) {
                    drive.findFolder(ROOT, "root")?.let { existing ->
                        prefs.rootId = existing.id
                        _status.value = Status.FoundBackup(existing.modified)
                        return@runCatching
                    }
                }
                push(ctx, drive, prefs)
            }.onFailure { fail(ctx, it.message ?: "Sync failed") }
        }
    }

    fun disconnect(ctx: Context) {
        State(ctx).enabled = false
        _status.value = Status.Off
    }

    fun refreshStatus(ctx: Context) {
        val p = State(ctx)
        if (_status.value is Status.FoundBackup || _status.value is Status.Syncing) return
        _status.value = when {
            !p.enabled -> Status.Off
            p.lastError != null -> Status.Failed(p.lastError!!, p.lastOkMs)
            else -> Status.Synced(p.lastOkMs, p.synced.size)
        }
    }

    /** Background sync. Quietly does nothing when not connected or when Google wants you to sign in again. */
    suspend fun sync(ctx: Context) = withContext(Dispatchers.IO) {
        val prefs = State(ctx)
        if (!prefs.enabled || _status.value is Status.FoundBackup) return@withContext
        mutex.withLock {
            runCatching {
                val r = Tasks.await(Identity.getAuthorizationClient(ctx).authorize(request()))
                if (r.hasResolution() || r.accessToken == null) error("Google wants you to reconnect — tap Connect")
                push(ctx, Drive(r.accessToken!!), prefs)
            }.onFailure { fail(ctx, it.message ?: "Sync failed") }
        }
    }

    /** "Keep this phone's journal": upload everything here, replacing the copies in Drive. */
    suspend fun overwriteBackup(ctx: Context) {
        // Files are matched by name and updated in place, so Drive keeps the old ones as revisions.
        _status.value = Status.Off
        sync(ctx)
    }

    /**
     * Replace this phone's journal with the one in Drive. The current one is kept beside it as
     * journal.before-restore-<time>, never deleted. The app has to restart afterwards.
     */
    suspend fun restore(ctx: Context): Result<Int> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                _status.value = Status.Syncing
                val r = Tasks.await(Identity.getAuthorizationClient(ctx).authorize(request()))
                val drive = Drive(r.accessToken ?: error("Reconnect to Google Drive first"))
                val prefs = State(ctx)
                val rootId = prefs.rootId ?: drive.findFolder(ROOT, "root")?.id ?: error("No fugazi folder in Drive")
                val tmp = File(ctx.filesDir, "journal.restoring").apply { deleteRecursively(); mkdirs() }
                val ids = mutableMapOf<String, String>()
                fun pull(folderId: String, into: File, prefix: String) {
                    drive.children(folderId).forEach { f ->
                        val path = if (prefix.isEmpty()) f.name else "$prefix/${f.name}"
                        if (f.folder) {
                            prefs.putFolder(path, f.id)
                            pull(f.id, File(into, f.name).apply { mkdirs() }, path)
                        } else {
                            File(into, f.name).writeBytes(drive.download(f.id))
                            ids[path] = f.id
                        }
                    }
                }
                pull(rootId, tmp, "")
                if (ids.isEmpty()) error("The fugazi folder in Drive is empty")

                val live = File(ctx.filesDir, "journal")
                if (live.exists()) live.renameTo(File(ctx.filesDir, "journal.before-restore-${System.currentTimeMillis()}"))
                tmp.renameTo(live)
                prefs.rootId = rootId
                prefs.enabled = true
                prefs.fileIds = ids
                // What's on the phone now is exactly what's in Drive.
                prefs.synced = ids.keys.associateWith { signature(File(live, it)) }
                prefs.lastOkMs = System.currentTimeMillis()
                prefs.lastError = null
                ids.size
            }.also { res ->
                _status.value = Status.Off
                res.onFailure { fail(ctx, it.message ?: "Restore failed") }
            }
        }
    }

    private fun push(ctx: Context, drive: Drive, prefs: State) {
        _status.value = Status.Syncing
        val base = File(ctx.filesDir, "journal")
        val local = base.walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(base).invariantSeparatorsPath to signature(it) }
        val synced = prefs.synced.toMutableMap()
        val ids = prefs.fileIds.toMutableMap()
        val rootId = prefs.rootId ?: drive.findFolder(ROOT, "root")?.id ?: drive.createFolder(ROOT, "root")
        prefs.rootId = rootId

        fun folderFor(dir: String): String {
            if (dir.isEmpty()) return rootId
            prefs.folder(dir)?.let { return it }
            val parent = folderFor(dir.substringBeforeLast('/', ""))
            val name = dir.substringAfterLast('/')
            val id = drive.findFolder(name, parent)?.id ?: drive.createFolder(name, parent)
            prefs.putFolder(dir, id)
            return id
        }

        changed(local, synced).forEach { path ->
            val file = File(base, path)
            val bytes = file.readBytes()
            val id = ids[path]
            if (id == null || !drive.update(id, bytes)) {
                val parent = folderFor(path.substringBeforeLast('/', ""))
                // A file of that name may already be there (another phone, a reinstall): update it, don't duplicate.
                val existing = drive.findFile(file.name, parent)?.id
                ids[path] = if (existing != null && drive.update(existing, bytes)) existing
                else drive.create(file.name, parent, bytes)
            }
            synced[path] = local.getValue(path)
            // Saved as it goes, so a dropped connection doesn't resend everything.
            prefs.fileIds = ids
            prefs.synced = synced
        }
        prefs.lastOkMs = System.currentTimeMillis()
        prefs.lastError = null
        _status.value = Status.Synced(prefs.lastOkMs, synced.size)
    }

    /** Files whose size or modified time differs from what was last sent. */
    fun changed(local: Map<String, String>, synced: Map<String, String>): List<String> =
        local.filter { (path, sig) -> synced[path] != sig }.keys.sorted()

    private fun signature(f: File) = "${f.length()}:${f.lastModified()}"

    private fun fail(ctx: Context, message: String) {
        val p = State(ctx)
        p.lastError = message
        _status.value = Status.Failed(message, p.lastOkMs)
    }

    /** What's been synced, where it lives in Drive, and whether sync is on. */
    private class State(ctx: Context) {
        private val prefs = ctx.getSharedPreferences("drive", Context.MODE_PRIVATE)
        var enabled: Boolean
            get() = prefs.getBoolean("enabled", false)
            set(v) = prefs.edit().putBoolean("enabled", v).apply()
        var rootId: String?
            get() = prefs.getString("root", null)
            set(v) = prefs.edit().putString("root", v).apply()
        var lastOkMs: Long
            get() = prefs.getLong("last_ok", 0L)
            set(v) = prefs.edit().putLong("last_ok", v).apply()
        var lastError: String?
            get() = prefs.getString("error", null)
            set(v) = prefs.edit().putString("error", v).apply()
        var synced: Map<String, String>
            get() = map("synced")
            set(v) = putMap("synced", v)
        var fileIds: Map<String, String>
            get() = map("ids")
            set(v) = putMap("ids", v)

        fun folder(path: String): String? = map("folders")[path]
        fun putFolder(path: String, id: String) = putMap("folders", map("folders") + (path to id))

        private fun map(key: String): Map<String, String> {
            val o = JSONObject(prefs.getString(key, "{}")!!)
            return o.keys().asSequence().associateWith { o.getString(it) }
        }

        private fun putMap(key: String, m: Map<String, String>) =
            prefs.edit().putString(key, JSONObject(m).toString()).apply()
    }
}
