package com.fourgeailabs.bpwatch.mobile.snore

import android.content.Context
import com.fourgeailabs.bpwatch.mobile.data.AppDatabase
import java.io.File

/**
 * Clip lifecycle for snore detection. Clips live in filesDir/snore_clips and
 * are pruned:
 * - older than 30 days (rows + files),
 * - whenever the total clips folder exceeds ~500 MB, oldest first.
 * Pruning runs when the service stops each morning and on every app start.
 * Blocking — call from a background thread or coroutine.
 */
object SnoreStorage {
    const val CLIPS_DIR = "snore_clips"
    private const val RETENTION_MS = 30L * 24 * 3_600_000
    private const val MAX_BYTES = 500L * 1024 * 1024

    fun clipsDir(context: Context): File = File(context.filesDir, CLIPS_DIR).apply { mkdirs() }

    suspend fun prune(context: Context) {
        try {
            val app = context.applicationContext
            val dao = AppDatabase.get(app).snoreDao()
            val dir = clipsDir(app)

            // 1) Age: rows older than 30 days, plus their files.
            val cutoff = System.currentTimeMillis() - RETENTION_MS
            val old = dao.eventsOlderThan(cutoff)
            for (event in old) {
                event.clipPath?.let { deleteQuietly(File(it)) }
            }
            dao.deleteOlderThan(cutoff)

            // 2) Size cap: oldest events first until the folder is ~500 MB.
            var total = dirSizeBytes(dir)
            if (total > MAX_BYTES) {
                for (event in dao.allOrdered()) {
                    if (total <= MAX_BYTES) break
                    val file = event.clipPath?.let(::File)
                    val size = if (file != null && file.exists()) file.length() else 0
                    if (file == null || deleteQuietly(file)) {
                        dao.deleteByTimestamp(event.timestamp)
                        total -= size
                    }
                }
            }

            // 3) Orphaned files with no DB row (crashed mid-write).
            val known = try {
                dao.allOrdered().mapNotNull { it.clipPath }.toSet()
            } catch (_: Exception) {
                emptySet()
            }
            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.absolutePath !in known && file.name.endsWith(".wav")) {
                    deleteQuietly(file)
                }
            }
        } catch (_: Exception) {
            // Pruning is housekeeping; it must never throw.
        }
    }

    private fun dirSizeBytes(dir: File): Long {
        var total = 0L
        try {
            dir.walkTopDown().forEach { if (it.isFile) total += it.length() }
        } catch (_: Exception) {
        }
        return total
    }

    private fun deleteQuietly(file: File): Boolean =
        try {
            !file.exists() || file.delete()
        } catch (_: Exception) {
            false
        }
}
