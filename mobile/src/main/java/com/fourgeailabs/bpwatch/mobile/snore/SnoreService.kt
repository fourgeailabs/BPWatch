package com.fourgeailabs.bpwatch.mobile.snore

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.fourgeailabs.bpwatch.mobile.data.AppDatabase
import com.fourgeailabs.bpwatch.mobile.data.SnoreEvent
import com.fourgeailabs.bpwatch.mobile.monitoring.MonitoringPrefs
import com.fourgeailabs.bpwatch.mobile.notifications.NotificationHelper
import java.io.File
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Overnight foreground service (22:00–07:00) that listens for snoring with
 * the phone microphone and stores detected events with short WAV clips.
 *
 * Detection is a deliberately lightweight adaptive energy detector: 100 ms
 * frames, an adaptive ambient RMS floor, sustained elevation (~1.5 s) to
 * trigger, 1 s of pre-roll, ~10 s clip cap, and a 30 s cooldown between
 * events. It is a heuristic — loud non-snore sounds can trigger it — so the
 * clips are kept and playable for the user to review.
 *
 * Nothing here may throw: microphone missing, permission denied, or
 * initialisation/read failure disables the feature gracefully via
 * [SnoreState] rather than crashing or spamming the user.
 *
 * Platform note: on Android 14+ the OS refuses to start a microphone
 * foreground service from the background (SecurityException, even for
 * alarm-triggered starts). The 22:00 alarm therefore *attempts* the start —
 * it succeeds when the app is open or on older OS versions — and app opens
 * during the window plus the Settings "Start listening now" button cover the
 * rest. The 07:00 stop needs no exemption and always works.
 */
class SnoreService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        SnoreState.setListening(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            SnoreScheduler.ACTION_STOP -> {
                stopRecording()
                SnoreState.post(null)
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                // The stop action aside, every start means "begin listening".
                try {
                    startForeground(NOTIF_ID, NotificationHelper.snoreNotification(this))
                } catch (_: Exception) {
                    // Foreground start rejected (OS policy, revoked
                    // permission, ...): disable gracefully rather than crash.
                    fail("Couldn't start listening — the system refused the notification.")
                    return START_NOT_STICKY
                }
                startRecording()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRecording()
        isRunning = false
        SnoreState.setListening(false)
        // Prune off the main thread: it hits the DB and the filesystem.
        // Prune is suspend (Room DAO calls); bridge with runBlocking.
        Thread({
            try {
                kotlinx.coroutines.runBlocking {
                    SnoreStorage.prune(applicationContext)
                }
            } catch (_: Exception) {
            }
        }, "SnorePrune").apply { isDaemon = true }.start()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Recording
    // ------------------------------------------------------------------

    @Volatile
    private var recording = false
    private var recordThread: Thread? = null
    @Volatile
    private var audioRecord: AudioRecord? = null

    private fun startRecording() {
        if (recording) return
        recording = true
        recordThread = Thread({
            var ar: AudioRecord? = null
            try {
                if (!canRun(this)) {
                    fail("Microphone unavailable.")
                    return@Thread
                }
                val minBuf = try {
                    AudioRecord.getMinBufferSize(
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                    )
                } catch (_: Exception) {
                    AudioRecord.ERROR_BAD_VALUE
                }
                if (minBuf <= 0) {
                    fail("Microphone unavailable.")
                    return@Thread
                }
                ar = try {
                    AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        minBuf.coerceAtLeast(FRAME_BYTES * 4),
                    )
                } catch (_: Exception) {
                    null
                }
                audioRecord = ar
                if (ar == null || ar.state != AudioRecord.STATE_INITIALIZED) {
                    fail("Microphone unavailable.")
                    return@Thread
                }
                try {
                    ar.startRecording()
                } catch (_: Exception) {
                    fail("Couldn't start recording.")
                    return@Thread
                }
                runDetector(ar)
            } catch (_: Exception) {
                // Never throw out of the worker thread.
            } finally {
                try {
                    ar?.stop()
                } catch (_: Exception) {
                }
                try {
                    ar?.release()
                } catch (_: Exception) {
                }
                audioRecord = null
            }
        }, "SnoreRecord").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopRecording() {
        recording = false
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        val thread = recordThread
        recordThread = null
        try {
            thread?.join(2_000)
        } catch (_: Exception) {
        }
    }

    /** Posts a graceful failure: feature off, status line explains why. */
    private fun fail(message: String) {
        stopRecording()
        SnoreState.post(message)
        try {
            MonitoringPrefs(applicationContext).setSnoreDetection(false)
        } catch (_: Exception) {
        }
        try {
            SnoreScheduler.cancel(applicationContext)
        } catch (_: Exception) {
        }
        try {
            stopSelf()
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------
    // Adaptive energy detector
    // ------------------------------------------------------------------

    private fun runDetector(ar: AudioRecord) {
        val frame = ByteArray(FRAME_BYTES)
        val preroll = ArrayDeque<ByteArray>(PREROLL_FRAMES)
        var floor = 0.0
        var floorFrames = 0
        var hotFrames = 0
        var capturing: ArrayList<ByteArray>? = null
        var captureStartTs = 0L
        var quietFrames = 0
        var cooldownUntil = 0L

        while (recording) {
            val read = try {
                ar.read(frame, 0, FRAME_BYTES)
            } catch (_: Exception) {
                break
            }
            if (read != FRAME_BYTES) {
                if (read < 0) break // ERROR_INVALID_OPERATION / ERROR_BAD_VALUE / dead object
                continue
            }
            val rms = frameRms(frame)
            // Adaptive ambient floor: seeded quickly over the first ~2 s,
            // then a slow exponential moving average.
            if (floorFrames < FLOOR_SEED_FRAMES) {
                floor += (rms - floor) / (floorFrames + 1)
                floorFrames++
            } else {
                floor += (rms - floor) * FLOOR_ALPHA
            }
            val threshold = max(floor * FLOOR_RATIO, floor + FLOOR_ABSOLUTE)

            val now = System.currentTimeMillis()
            val current = capturing
            if (current == null) {
                preroll.addLast(frame.copyOf())
                if (preroll.size > PREROLL_FRAMES) preroll.removeFirst()
                if (now >= cooldownUntil && rms > threshold) {
                    hotFrames++
                    if (hotFrames >= TRIGGER_FRAMES) {
                        // Sustained elevation: start an event with the
                        // pre-roll so the onset isn't clipped.
                        capturing = ArrayList<ByteArray>(MAX_CLIP_FRAMES).apply { addAll(preroll) }
                        preroll.clear()
                        captureStartTs = now - PREROLL_FRAMES * FRAME_MS
                        quietFrames = 0
                        hotFrames = 0
                    }
                } else {
                    hotFrames = 0
                }
            } else {
                current.add(frame.copyOf())
                if (rms > threshold) quietFrames = 0 else quietFrames++
                val tooLong = current.size >= MAX_CLIP_FRAMES
                if (quietFrames >= END_QUIET_FRAMES || tooLong) {
                    saveEvent(current, captureStartTs)
                    capturing = null
                    cooldownUntil = System.currentTimeMillis() + COOLDOWN_MS
                }
            }
        }
    }

    private fun frameRms(frame: ByteArray): Double {
        var sum = 0.0
        var i = 0
        while (i < FRAME_BYTES) {
            val lo = frame[i].toInt() and 0xFF
            val hi = frame[i + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort().toDouble()
            sum += sample * sample
            i += 2
        }
        return sqrt(sum / FRAME_SAMPLES)
    }

    private fun saveEvent(frames: List<ByteArray>, startTs: Long) {
        try {
            if (frames.isEmpty()) return
            val dir = SnoreStorage.clipsDir(applicationContext)
            val file = File(dir, "$startTs.wav")
            WavWriter.writePcm16Mono16k(file, frames)
            val event = SnoreEvent(
                timestamp = startTs,
                durationMs = frames.size * FRAME_MS,
                clipPath = file.absolutePath,
            )
            val dao = AppDatabase.get(applicationContext).snoreDao()
            // We're on a worker thread: a nested runBlocking is the simplest
            // way to call the suspend DAO without a coroutine scope.
            kotlinx.coroutines.runBlocking { dao.insert(event) }
        } catch (_: Exception) {
            // A failed save loses one event; the detector keeps running.
        }
    }

    companion object {
        private const val NOTIF_ID = 2001

        // Audio: 16 kHz mono PCM 16-bit, VOICE_RECOGNITION, 100 ms frames.
        private const val SAMPLE_RATE = 16_000
        private const val FRAME_MS = 100
        private const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000 // 1,600
        private const val FRAME_BYTES = FRAME_SAMPLES * 2 // 3,200

        // Detector tuning.
        private const val FLOOR_SEED_FRAMES = 20 // ~2 s fast seed
        private const val FLOOR_ALPHA = 0.02 // slow ambient tracking
        private const val FLOOR_RATIO = 3.0 // trigger at 3x ambient RMS
        private const val FLOOR_ABSOLUTE = 300.0 // …or 300 units above it
        private const val PREROLL_FRAMES = 10 // 1 s pre-roll
        private const val TRIGGER_FRAMES = 15 // ~1.5 s sustained elevation
        private const val MAX_CLIP_FRAMES = 100 // ~10 s clip cap
        private const val END_QUIET_FRAMES = 10 // 1 s of quiet ends the clip
        private const val COOLDOWN_MS = 30_000L // 30 s between events

        /** Service-side view of "am I actually running". */
        @Volatile
        var isRunning = false
            private set

        /**
         * True only when the OS would let the service record right now:
         * runtime microphone permission granted AND microphone hardware
         * present. Used before every start attempt so failures degrade to a
         * status line instead of a crash.
         */
        fun canRun(context: Context): Boolean {
            val app = context.applicationContext
            val granted = ContextCompat.checkSelfPermission(
                app,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            val hasMic = try {
                app.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
            } catch (_: Exception) {
                false
            }
            return granted && hasMic
        }
    }
}
