package me.laumss.notipal

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.text.TextUtils
import android.util.Log
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.hypot

internal object TwoFingerDoubleTap {
    private const val TAG = "NotipalTwoFinger"

    private const val TAP_MAX_MS = 300L
    private const val DOUBLE_TAP_GAP_MS = 400L
    private const val TAP_MOVE_SLOP_DP = 24f
    private const val DOUBLE_TAP_DIST_DP = 120f

    private const val EV_SYN = 0
    private const val EV_KEY = 1
    private const val EV_ABS = 3
    private const val SYN_REPORT = 0
    private const val BTN_TOUCH = 330
    private const val ABS_MT_SLOT = 47
    private const val ABS_MT_POSITION_X = 53
    private const val ABS_MT_POSITION_Y = 54
    private const val ABS_MT_TRACKING_ID = 57
    private const val MAX_SLOTS = 10
    private const val INPUT_EVENT_SIZE_64 = 24
    private const val INPUT_EVENT_SIZE_32 = 16
    private const val PEN_BATCH_EVENTS = 64
    private const val PEN_BATCH_SLEEP_MS = 15L

    private val isA5X2 = TextUtils.equals(Build.BOARD, "A5X2")
    private val main = Handler(Looper.getMainLooper())

    private class Devices(val touch: String, val touchName: String, val pen: String?)

    @Volatile private var devices: Devices? = null

    @Volatile private var generation = 0
    @Volatile private var running = false
    @Volatile private var penTip = false

    fun start(context: Context, onDoubleTap: () -> Unit) {
        if (running) return
        running = true
        val gen = ++generation
        val metrics = context.resources.displayMetrics
        val portraitW = minOf(metrics.widthPixels, metrics.heightPixels)
        val portraitH = maxOf(metrics.widthPixels, metrics.heightPixels)
        val density = metrics.density
        Thread({
            val found = devices ?: scanDevices().also { devices = it }
            if (gen == generation) {
                found.pen?.let { pen -> Thread({ readPen(pen, gen) }, "$TAG-pen").apply { isDaemon = true }.start() }
                TouchLoop(found, gen, portraitW, portraitH, density) {
                    main.post { if (gen == generation) onDoubleTap() }
                }.run()
            }
        }, "$TAG-touch").apply { isDaemon = true }.start()
        Log.i(TAG, "started")
    }

    fun stop() {
        if (!running) return
        running = false
        generation++
        penTip = false
        Log.i(TAG, "stopped")
    }

    private val inputEventSize: Int
        get() = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) INPUT_EVENT_SIZE_64 else INPUT_EVENT_SIZE_32

    private class TouchLoop(
        private val devices: Devices,
        private val gen: Int,
        private val portraitW: Int,
        private val portraitH: Int,
        private val density: Float,
        private val onDoubleTap: () -> Unit,
    ) {
        private val isPtMt = devices.touchName.contains("pt_mt", ignoreCase = true)
        private val trackingId = IntArray(MAX_SLOTS) { -1 }
        private val rawX = IntArray(MAX_SLOTS) { -1 }
        private val rawY = IntArray(MAX_SLOTS) { -1 }
        private val downX = FloatArray(MAX_SLOTS) { Float.NaN }
        private val downY = FloatArray(MAX_SLOTS) { Float.NaN }
        private val point = FloatArray(2)
        private var slot = 0
        private var fingers = 0

        private var episodeStartMs = 0L
        private var episodeMaxFingers = 0
        private var episodeMoved = false
        private var episodeCx = 0f
        private var episodeCy = 0f
        private var lastTapMs = 0L
        private var lastTapX = 0f
        private var lastTapY = 0f

        fun run() {
            val size = inputEventSize
            val buffer = ByteArray(size)
            val bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
            try {
                FileInputStream(devices.touch).use { fis ->
                    Log.i(TAG, "touch reader opened ${devices.touch} (${devices.touchName})")
                    val pollFd = StructPollfd().apply { fd = fis.fd; events = OsConstants.POLLIN.toShort() }
                    while (gen == generation) {
                        if (Os.poll(arrayOf(pollFd), 200) == 0) continue
                        var offset = 0
                        while (offset < size) {
                            val n = fis.read(buffer, offset, size - offset)
                            if (n < 0) {
                                Log.w(TAG, "EOF on ${devices.touch}")
                                return
                            }
                            offset += n
                        }
                        if (gen != generation) return
                        bb.position(size - 8)
                        val type = bb.short.toInt() and 0xFFFF
                        val code = bb.short.toInt() and 0xFFFF
                        val value = bb.int
                        handle(type, code, value)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "touch reader error on ${devices.touch}", e)
            }
        }

        private fun handle(type: Int, code: Int, value: Int) {
            when (type) {
                EV_ABS -> when (code) {
                    ABS_MT_SLOT -> slot = value.coerceIn(0, MAX_SLOTS - 1)
                    ABS_MT_TRACKING_ID -> {
                        trackingId[slot] = value
                        rawX[slot] = -1
                        rawY[slot] = -1
                        downX[slot] = Float.NaN
                        downY[slot] = Float.NaN
                    }
                    ABS_MT_POSITION_X -> rawX[slot] = value
                    ABS_MT_POSITION_Y -> rawY[slot] = value
                }
                EV_SYN -> if (code == SYN_REPORT) frame(SystemClock.uptimeMillis())
            }
        }

        private fun frame(now: Long) {
            val before = fingers
            var count = 0
            var landed = false
            var sumX = 0f
            var sumY = 0f
            for (i in 0 until MAX_SLOTS) {
                if (trackingId[i] < 0 || rawX[i] < 0 || rawY[i] < 0) continue
                count++
                toPx(rawX[i], rawY[i])
                sumX += point[0]
                sumY += point[1]
                if (downX[i].isNaN()) {
                    downX[i] = point[0]
                    downY[i] = point[1]
                    landed = true
                } else if (abs(point[0] - downX[i]) > TAP_MOVE_SLOP_DP * density ||
                    abs(point[1] - downY[i]) > TAP_MOVE_SLOP_DP * density) {
                    episodeMoved = true
                }
            }
            fingers = count
            if (before == 0 && count > 0) {
                episodeStartMs = now
                episodeMaxFingers = 0
                episodeMoved = false
            }
            if (count > 0) {
                if (landed && penTip) episodeMoved = true
                episodeMaxFingers = maxOf(episodeMaxFingers, count)
                if (count == 2 && before < 2) {
                    episodeCx = sumX / 2f
                    episodeCy = sumY / 2f
                }
            } else if (before > 0) {
                val qualifies = episodeMaxFingers == 2 && !episodeMoved && now - episodeStartMs <= TAP_MAX_MS
                if (qualifies) onTap(now)
            }
        }

        private fun onTap(now: Long) {
            val gap = now - lastTapMs
            val near = hypot(episodeCx - lastTapX, episodeCy - lastTapY) <= DOUBLE_TAP_DIST_DP * density
            if (lastTapMs != 0L && gap <= DOUBLE_TAP_GAP_MS && near) {
                Log.i(TAG, "double tap gap=${gap}ms")
                lastTapMs = 0L
                onDoubleTap()
            } else {
                lastTapMs = now
                lastTapX = episodeCx
                lastTapY = episodeCy
            }
        }

        private fun toPx(x: Int, y: Int) {
            if (isA5X2) {
                if (isPtMt) {
                    point[0] = x * portraitW.toFloat() / portraitH
                    point[1] = y * portraitH.toFloat() / portraitW
                } else {
                    point[0] = x.toFloat()
                    point[1] = y.toFloat()
                }
            } else {
                point[0] = portraitW - y.toFloat()
                point[1] = x.toFloat()
            }
        }
    }

    private fun readPen(path: String, gen: Int) {
        val size = inputEventSize
        val buffer = ByteArray(size * PEN_BATCH_EVENTS)
        val bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
        try {
            FileInputStream(path).use { fis ->
                Log.i(TAG, "pen reader opened $path")
                val pollFd = StructPollfd().apply { fd = fis.fd; events = OsConstants.POLLIN.toShort() }
                while (gen == generation) {
                    if (Os.poll(arrayOf(pollFd), 200) == 0) continue
                    val read = fis.read(buffer)
                    if (read < 0) return
                    var offset = 0
                    while (offset + size <= read) {
                        bb.position(offset + size - 8)
                        val type = bb.short.toInt() and 0xFFFF
                        val code = bb.short.toInt() and 0xFFFF
                        val value = bb.int
                        if (type == EV_KEY && code == BTN_TOUCH && gen == generation) penTip = value == 1
                        offset += size
                    }
                    Thread.sleep(PEN_BATCH_SLEEP_MS)
                }
            }
        } catch (e: InterruptedException) {
            Log.i(TAG, "pen reader interrupted: $path")
        } catch (e: Exception) {
            Log.e(TAG, "pen reader error on $path", e)
        }
    }

    private fun scanDevices(): Devices {
        var touch: String? = null
        var touchName = ""
        var pen: String? = null
        try {
            val process = ProcessBuilder("getevent", "-lp").redirectErrorStream(true).start()
            val content = process.inputStream.bufferedReader().readText()
            process.waitFor()
            for (block in content.split("add device")) {
                if (block.isBlank()) continue
                val path = Regex("/dev/input/event\\d+").find(block)?.value ?: continue
                val name = Regex("name:\\s*\"(.+?)\"").find(block)?.groupValues?.get(1) ?: continue
                when {
                    name.contains("Wacom", true) || name.contains("Digitizer", true) -> pen = path
                    name.contains("Atmel", true) || name.contains("maXTouch", true) ||
                        name.contains("pt_mt", true) || name.contains("fts_ts", true) ||
                        name.contains("Touchscreen", true) -> {
                        touch = path
                        touchName = name
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "device scan failed", e)
        }
        Log.i(TAG, "devices touch=$touch ($touchName) pen=$pen")
        return Devices(touch ?: "/dev/input/event1", touchName, pen ?: "/dev/input/event0")
    }
}
