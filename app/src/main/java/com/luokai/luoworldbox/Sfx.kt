package com.luokai.luoworldbox

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Sound effects synthesised in code at start-up (no audio files shipped).
 * Every call is wrapped so that a device without audio can never crash the game.
 */
class Sfx(private val ctx: Context) {
    @Volatile var enabled = true
    private val rate = 22050
    private var pool: SoundPool? = null
    private val ids = HashMap<String, Int>()
    private val lastPlayed = HashMap<String, Long>()

    init {
        try {
            val p = SoundPool.Builder()
                .setMaxStreams(6)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                ).build()
            pool = p
            add("boom", boom())
            add("zap", zap())
            add("pop", pop())
            add("bless", bless())
            add("horn", horn())
        } catch (e: Throwable) {
            pool = null
        }
    }

    private fun add(name: String, pcm: ShortArray) {
        val f = File(ctx.cacheDir, "sfx_$name.wav")
        val buf = ByteBuffer.allocate(44 + pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()); buf.putInt(36 + pcm.size * 2); buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()); buf.putInt(16); buf.putShort(1); buf.putShort(1)
        buf.putInt(rate); buf.putInt(rate * 2); buf.putShort(2); buf.putShort(16)
        buf.put("data".toByteArray()); buf.putInt(pcm.size * 2)
        for (s in pcm) buf.putShort(s)
        f.writeBytes(buf.array())
        pool?.let { ids[name] = it.load(f.path, 1) }
    }

    fun play(name: String, volume: Float = 1f) {
        if (!enabled) return
        try {
            val now = System.currentTimeMillis()
            if (now - (lastPlayed[name] ?: 0L) < 120) return
            lastPlayed[name] = now
            val id = ids[name] ?: return
            pool?.play(id, volume, volume, 1, 0, 1f)
        } catch (e: Throwable) {
        }
    }

    fun release() {
        try { pool?.release() } catch (e: Throwable) { }
        pool = null
    }

    // ------------------------------------------------------------------ synthesis
    private fun s16(v: Double): Short = (v.coerceIn(-1.0, 1.0) * 30000).toInt().toShort()

    private fun boom(): ShortArray {
        val n = (rate * 0.8).toInt()
        val r = Random(1)
        var lp = 0.0
        return ShortArray(n) { i ->
            val t = i / rate.toDouble()
            lp += (r.nextDouble() * 2 - 1 - lp) * 0.12
            s16((lp * 2.2 * exp(-t * 5.0)) + sin(2 * PI * 52 * t) * exp(-t * 6.0) * 0.8)
        }
    }

    private fun zap(): ShortArray {
        val n = (rate * 0.28).toInt()
        val r = Random(2)
        var prev = 0.0
        return ShortArray(n) { i ->
            val t = i / rate.toDouble()
            val x = r.nextDouble() * 2 - 1
            val hp = x - prev; prev = x
            s16(hp * exp(-t * 12.0) * 0.9)
        }
    }

    private fun pop(): ShortArray {
        val n = (rate * 0.12).toInt()
        var ph = 0.0
        return ShortArray(n) { i ->
            val t = i / rate.toDouble()
            val f = 760 - 420 * (t / 0.12)
            ph += 2 * PI * f / rate
            s16(sin(ph) * exp(-t * 20.0) * 0.8)
        }
    }

    private fun bless(): ShortArray {
        val n = (rate * 0.42).toInt()
        val notes = doubleArrayOf(523.25, 659.25, 783.99, 1046.5)
        return ShortArray(n) { i ->
            val t = i / rate.toDouble()
            val k = (t / 0.1).toInt().coerceAtMost(3)
            val lt = t - k * 0.1
            s16(sin(2 * PI * notes[k] * t) * exp(-lt * 9.0) * 0.55)
        }
    }

    private fun horn(): ShortArray {
        val n = (rate * 0.65).toInt()
        return ShortArray(n) { i ->
            val t = i / rate.toDouble()
            val env = minOf(1.0, t / 0.08) * exp(-(t - 0.08).coerceAtLeast(0.0) * 3.0)
            val v = sin(2 * PI * 110 * t) + 0.5 * sin(2 * PI * 165 * t) + 0.25 * sin(2 * PI * 220 * t)
            s16(v * env * 0.45)
        }
    }
}
