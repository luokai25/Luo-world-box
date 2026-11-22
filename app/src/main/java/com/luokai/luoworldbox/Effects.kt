package com.luokai.luoworldbox

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import com.luokai.luoworldbox.core.Act
import com.luokai.luoworldbox.core.Fx
import com.luokai.luoworldbox.core.Job
import com.luokai.luoworldbox.core.Tile
import com.luokai.luoworldbox.core.World
import com.luokai.luoworldbox.core.Zone
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Struct-of-arrays particle pool. Positions are tile coordinates on the ground plane (x, y) plus a
 * height z, so smoke can rise and sparks can arc and fall. Everything is drawn from one place.
 */
class Particles(private val cap: Int = 7000) {
    val x = FloatArray(cap); val y = FloatArray(cap); val z = FloatArray(cap)
    val vx = FloatArray(cap); val vy = FloatArray(cap); val vz = FloatArray(cap)
    val life = FloatArray(cap); val maxLife = FloatArray(cap)
    val size = FloatArray(cap); val grow = FloatArray(cap)
    val grav = FloatArray(cap); val drag = FloatArray(cap)
    val col0 = IntArray(cap); val col1 = IntArray(cap)
    val kind = ByteArray(cap)
    var n = 0

    fun emit(
        px: Float, py: Float, pz: Float, pvx: Float, pvy: Float, pvz: Float,
        lifeSec: Float, sz: Float, c0: Int, c1: Int, k: Int,
        g: Float = 0f, dr: Float = 0f, gr: Float = 0f
    ) {
        if (n >= cap) return
        val i = n++
        x[i] = px; y[i] = py; z[i] = pz; vx[i] = pvx; vy[i] = pvy; vz[i] = pvz
        life[i] = lifeSec; maxLife[i] = lifeSec; size[i] = sz; grow[i] = gr
        grav[i] = g; drag[i] = dr; col0[i] = c0; col1[i] = c1; kind[i] = k.toByte()
    }

    fun update(dt: Float) {
        var i = 0
        while (i < n) {
            life[i] -= dt
            if (life[i] <= 0f) {
                n--
                if (i != n) copy(n, i)
                continue
            }
            val d = 1f - min(0.95f, drag[i] * dt)
            vx[i] *= d; vy[i] *= d; vz[i] = vz[i] * d - grav[i] * dt
            x[i] += vx[i] * dt; y[i] += vy[i] * dt; z[i] += vz[i] * dt
            if (kind[i].toInt() == 2 && z[i] < 0f) { z[i] = 0f; vz[i] = -vz[i] * 0.3f; vx[i] *= 0.5f; vy[i] *= 0.5f }
            i++
        }
    }

    private fun copy(from: Int, to: Int) {
        x[to] = x[from]; y[to] = y[from]; z[to] = z[from]
        vx[to] = vx[from]; vy[to] = vy[from]; vz[to] = vz[from]
        life[to] = life[from]; maxLife[to] = maxLife[from]
        size[to] = size[from]; grow[to] = grow[from]
        grav[to] = grav[from]; drag[to] = drag[from]
        col0[to] = col0[from]; col1[to] = col1[from]; kind[to] = kind[from]
    }
}

/**
 * All the visual effects of the game: explosions with shockwaves and debris, branching lightning,
 * rain clouds, tornado funnels, black-hole accretion disks, volcano eruptions, meteors with fiery
 * trails, flames and smoke on burning land, spawn sparkles, work sparks and brush-specific dust.
 * Runs on the game thread together with the renderer.
 */
class Effects(private val dp: Float) {
    val ps = Particles()
    private val rnd = java.util.Random()
    private val add = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val addLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val oval = RectF()

    var shake = 0f
    private var flash = 0f
    private var flashColor = 0xFFFFFF
    private var clock = 0f

    // view window, set every frame
    private var vw = 1080f; private var vh = 1920f
    private var s = 1f
    private var wx0 = 0f; private var wy0 = 0f; private var wx1 = 0f; private var wy1 = 0f

    fun begin(width: Int, height: Int, scale: Float, x0: Float, y0: Float, x1: Float, y1: Float) {
        vw = width.toFloat(); vh = height.toFloat(); s = scale
        wx0 = x0; wy0 = y0; wx1 = x1; wy1 = y1
    }

    private fun rf() = rnd.nextFloat()
    private fun rs() = rnd.nextFloat() * 2f - 1f
    private fun vis(x: Float, y: Float, m: Float = 6f) = x > wx0 - m && x < wx1 + m && y > wy0 - m && y < wy1 + m
    private fun argb(a: Int, rgb: Int) = (a shl 24) or (rgb and 0xFFFFFF)

    private fun lerpC(a: Int, b: Int, t: Float): Int {
        val aa = ((a ushr 24) * (1 - t) + (b ushr 24) * t).toInt()
        val r = ((a shr 16 and 255) * (1 - t) + (b shr 16 and 255) * t).toInt()
        val g = ((a shr 8 and 255) * (1 - t) + (b shr 8 and 255) * t).toInt()
        val bl = ((a and 255) * (1 - t) + (b and 255) * t).toInt()
        return (aa shl 24) or (r shl 16) or (g shl 8) or bl
    }

    private fun addShake(amount: Float) { shake = min(shake + amount * dp, 26f * dp) }
    private fun addFlash(a: Float, rgb: Int) { if (a > flash) { flash = a; flashColor = rgb } }

    // ------------------------------------------------------------------ per frame update
    fun update(w: World, dt: Float) {
        if (dt <= 0f) return
        val d = min(dt, 0.05f)
        clock += d
        shake *= exp(-d * 6f)
        flash = max(0f, flash - d * 2.4f)
        ps.update(d)
        for (f in w.fxs()) if (!f.emitted) { f.emitted = true; if (vis(f.x, f.y, 40f)) onFx(f) }
        for (z in w.zones) zoneTick(w, z, d)
        fireTick(w, d)
        workTick(w)
    }

    // ------------------------------------------------------------------ events from the world
    private fun onFx(f: Fx) {
        when (f.type) {
            Fx.EXPLOSION -> explosion(f)
            Fx.LIGHTNING -> {
                addFlash(0.4f, 0xE8F0FF); addShake(4f)
                for (i in 0 until 22) {
                    val a = rf() * 6.283f; val sp = 4f + rf() * 10f
                    ps.emit(f.x, f.y, 0.2f, cos(a) * sp, sin(a) * sp * 0.6f, 3f + rf() * 6f, 0.25f + rf() * 0.4f, 0.07f,
                        0xFFE8F4FF.toInt(), 0x006080FF, 3, g = 24f)
                }
                ps.emit(f.x, f.y, 0.3f, 0f, 0f, 0f, 0.35f, 1.6f, 0xAAB8D8FF.toInt(), 0x005070FF, 1, gr = 1.5f)
                for (i in 0 until 5) ps.emit(f.x + rs() * 0.5f, f.y, 0.2f, rs() * 0.4f, rs() * 0.2f, 1f + rf(), 1.4f, 0.4f,
                    0x88605850.toInt(), 0x00302828, 0, gr = 0.6f)
            }
            Fx.RING -> {
                val c = if (f.color != 0) f.color else 0xFFFFD27A.toInt()
                for (i in 0 until 26) {
                    val a = rf() * 6.283f; val rr = f.r * (0.2f + rf() * 0.8f)
                    ps.emit(f.x + cos(a) * rr, f.y + sin(a) * rr * 0.7f, 0f, 0f, 0f, 1.2f + rf() * 1.6f, 0.8f + rf() * 0.8f, 0.15f,
                        c or (0xFF shl 24), argb(0, c), 1, dr = 0.5f)
                }
            }
            Fx.SPAWN -> {
                val c = if (f.color != 0) f.color else 0xFFFFFFFF.toInt()
                ps.emit(f.x, f.y, 0.4f, 0f, 0f, 0f, 0.3f, 0.7f, 0xCCFFFFFF.toInt(), 0x00FFFFFF, 1, gr = 1.6f)
                for (i in 0 until 18) {
                    val a = rf() * 6.283f; val sp = 1f + rf() * 2.2f
                    ps.emit(f.x, f.y, 0.2f, cos(a) * sp, sin(a) * sp * 0.6f, 1.5f + rf() * 2.5f, 0.6f + rf() * 0.5f, 0.14f,
                        lerpC(c or (0xFF shl 24), 0xFFFFFFFF.toInt(), 0.5f), argb(0, c), 1, g = 3f, dr = 1.5f, gr = 0.1f)
                }
            }
            Fx.POOF -> {
                for (i in 0 until 5) ps.emit(f.x + rs() * 0.3f, f.y + rs() * 0.2f, 0.2f, rs() * 0.6f, rs() * 0.3f, 0.5f + rf(), 0.6f + rf() * 0.4f, 0.2f,
                    0x88D8D8D8.toInt(), 0x00B0B0B0, 0, dr = 1.2f, gr = 0.4f)
                val c = f.color
                if (c != 0) for (i in 0 until 3) ps.emit(f.x, f.y, 0.3f, rs() * 0.8f, rs() * 0.4f, 1.5f + rf() * 1.5f, 0.6f, 0.06f,
                    c or (0xFF shl 24), argb(0, c), 1, g = 4f)
            }
            Fx.QUAKE -> {
                addShake(11f)
                for (i in 0 until 60) {
                    val a = rf() * 6.283f; val rr = f.r * sqrt(rf())
                    ps.emit(f.x + cos(a) * rr, f.y + sin(a) * rr * 0.8f, 0f, rs() * 0.5f, rs() * 0.3f, 0.8f + rf() * 2f, 1.0f + rf() * 1.2f, 0.28f,
                        0x99907458.toInt(), 0x00604830, 0, dr = 1f, gr = 0.5f)
                }
                for (i in 0 until 14) {
                    val a = rf() * 6.283f; val rr = f.r * rf()
                    ps.emit(f.x + cos(a) * rr, f.y + sin(a) * rr, 0f, rs() * 2f, rs() * 1.5f, 3f + rf() * 4f, 1.2f, 0.08f,
                        0xFF6A5A48.toInt(), 0xFF4A3A2C.toInt(), 2, g = 18f)
                }
            }
            Fx.CURSE -> {
                for (i in 0 until 34) {
                    val a = rf() * 6.283f; val rr = f.r * (0.3f + rf() * 0.7f)
                    ps.emit(f.x + cos(a) * rr, f.y + sin(a) * rr * 0.7f, 0f, -sin(a) * 0.8f, cos(a) * 0.5f, 1.4f + rf() * 1.8f, 0.9f + rf() * 0.8f, 0.16f,
                        0xDD8A3AE0.toInt(), 0x00381870, 0, dr = 0.4f)
                }
            }
        }
    }

    private fun explosion(f: Fx) {
        val r = f.r; val x = f.x; val y = f.y
        addFlash(min(0.32f, r / 45f + 0.05f), 0xFFE0A0)
        addShake(min(24f, r * 1.7f))
        ps.emit(x, y, 0.5f, 0f, 0f, 0f, 0.28f, r * 0.3f, 0xBBFFF4C8.toInt(), 0x00FF8A20, 1, gr = r * 0.9f)
        ps.emit(x, y, 0.3f, 0f, 0f, 0f, 0.55f, r * 0.3f, 0x88FFA838.toInt(), 0x00C02808, 1, gr = r * 0.8f)
        val nf = (10 + r * 3f).toInt().coerceAtMost(70)
        for (i in 0 until nf) {
            val a = rf() * 6.283f; val sp = (0.3f + rf()) * r * 1.8f
            ps.emit(x + cos(a) * r * 0.1f, y + sin(a) * r * 0.1f, 0.3f + rf() * 0.5f, cos(a) * sp, sin(a) * sp * 0.75f, rf() * 3.5f,
                0.45f + rf() * 0.8f, r * (0.06f + rf() * 0.09f), 0xFFFFD868.toInt(), 0x00B82008, 1, dr = 2.4f, gr = -r * 0.05f)
        }
        val ns = (14 + r * 2.5f).toInt().coerceAtMost(80)
        for (i in 0 until ns) {
            val a = rf() * 6.283f; val sp = 6f + rf() * (6f + r * 1.2f)
            ps.emit(x, y, 0.4f, cos(a) * sp, sin(a) * sp * 0.7f, 4f + rf() * (6f + r), 0.5f + rf() * 0.9f, 0.06f,
                0xFFFFE8A0.toInt(), 0x00FF5018, 3, g = 22f, dr = 0.4f)
        }
        val nm = (6 + r * 1.3f).toInt().coerceAtMost(36)
        for (i in 0 until nm) {
            val a = rf() * 6.283f; val rr = rf() * r * 0.6f
            ps.emit(x + cos(a) * rr, y + sin(a) * rr * 0.7f, 0.4f, cos(a) * 0.9f, sin(a) * 0.5f, 1.2f + rf() * (2f + r * 0.2f),
                1.2f + rf() * 1.5f, r * (0.08f + rf() * 0.07f), 0x884A4038.toInt(), 0x00201A18, 0, dr = 0.6f, gr = r * 0.08f)
        }
        val nd = (8 + r * 1.2f).toInt().coerceAtMost(36)
        for (i in 0 until nd) {
            val a = rf() * 6.283f; val sp = 1.5f + rf() * (3f + r * 0.5f)
            ps.emit(x, y, 0.3f, cos(a) * sp, sin(a) * sp * 0.7f, 5f + rf() * (7f + r * 0.4f), 0.9f + rf() * 0.7f, 0.07f + rf() * 0.06f,
                if (rf() < 0.5f) 0xFF6A5A48.toInt() else 0xFF55575C.toInt(), 0xFF3A3028.toInt(), 2, g = 24f)
        }
    }

    // ------------------------------------------------------------------ brush powers
    fun onPower(id: String, x: Float, y: Float, r: Int) {
        if (!vis(x, y, 10f)) return
        val rr = r + 0.6f
        fun inDisc(): FloatArray { val a = rf() * 6.283f; val d = sqrt(rf()) * rr; return floatArrayOf(x + cos(a) * d, y + sin(a) * d) }
        when (id) {
            "fire" -> repeat(3 + r) {
                val p = inDisc()
                ps.emit(p[0], p[1] + 0.3f, 0.1f, rs() * 0.4f, rs() * 0.2f, 1.6f + rf() * 2f, 0.5f + rf() * 0.5f, 0.2f + rf() * 0.15f,
                    0xFFFFC050.toInt(), 0x00E02010, 1, gr = -0.1f)
            }
            "heat" -> repeat(2 + r) {
                val p = inDisc()
                ps.emit(p[0], p[1], 0f, rs() * 0.3f, 0f, 1.5f + rf() * 1.5f, 0.9f + rf() * 0.8f, 0.13f, 0xAAFF9A40.toInt(), 0x00FF4010, 1, dr = 0.3f)
                if (rf() < 0.35f) ps.emit(p[0], p[1], 0f, rs() * 0.2f, 0f, 1.2f, 1.1f, 0.5f, 0x22FFE0C0, 0x00FFE0C0, 0, gr = 0.5f)
            }
            "cold" -> repeat(3 + r) {
                val p = inDisc()
                ps.emit(p[0], p[1], 2.2f + rf() * 1.2f, rs() * 0.6f, rs() * 0.2f, -0.9f - rf() * 0.8f, 1.3f + rf() * 0.8f, 0.07f,
                    0xFFFFFFFF.toInt(), 0x66BFE8FF, 0)
                if (rf() < 0.4f) ps.emit(p[0], p[1], 0.3f, rs() * 0.3f, rs() * 0.2f, 0.8f, 0.8f, 0.09f, 0xFFA8E8FF.toInt(), 0x0060B0FF, 1)
            }
            "lava" -> repeat(2 + r) {
                val p = inDisc()
                ps.emit(p[0], p[1], 0.2f, rs() * 0.8f, rs() * 0.4f, 2f + rf() * 3f, 0.6f + rf() * 0.5f, 0.08f, 0xFFFFB030.toInt(), 0x00E03010, 1, g = 8f)
            }
            "deep", "shallow" -> repeat(3 + r) {
                val p = inDisc()
                val c = if (id == "deep") 0xFF2A5AB8.toInt() else 0xFF7AB8F0.toInt()
                ps.emit(p[0], p[1], 0.1f, rs() * 1.2f, rs() * 0.6f, 2.5f + rf() * 3f, 0.5f + rf() * 0.4f, 0.07f, c, argb(0, c), 1, g = 14f)
            }
            "sand", "soil", "grass", "forest", "hills", "mountain", "snow", "pickaxe", "sickle", "sponge", "eraser" -> {
                val c = when (id) {
                    "sand" -> 0xFFE0C878.toInt(); "soil" -> 0xFF8B5E34.toInt(); "grass" -> 0xFF5CB030.toInt()
                    "forest" -> 0xFF2F7A22.toInt(); "hills" -> 0xFF8A8A70.toInt(); "mountain" -> 0xFF66686E.toInt()
                    "snow" -> 0xFFF4F8FF.toInt(); "sponge" -> 0xFF9AD0F8.toInt(); "eraser" -> 0xFFE8E8E8.toInt()
                    else -> 0xFF8A8A8A.toInt()
                }
                repeat(2 + r) {
                    val p = inDisc()
                    ps.emit(p[0], p[1], 0.1f, rs() * 0.9f, rs() * 0.5f, 1.2f + rf() * 2f, 0.5f + rf() * 0.4f, 0.09f + rf() * 0.06f, c, argb(0, c), 2, g = 12f)
                    if (rf() < 0.5f) ps.emit(p[0], p[1], 0f, rs() * 0.4f, rs() * 0.2f, 0.6f, 0.7f, 0.22f, argb(110, c), argb(0, c), 0, gr = 0.5f)
                }
            }
            "bless" -> repeat(4 + r) {
                val p = inDisc()
                ps.emit(p[0], p[1], 0f, rs() * 0.2f, 0f, 1.8f + rf() * 1.6f, 0.9f + rf() * 0.6f, 0.09f, 0xFFFFE890.toInt(), 0x00FFB030, 1)
            }
            "friendship" -> repeat(3 + r) {
                val p = inDisc()
                ps.emit(p[0], p[1], 0f, rs() * 0.3f, 0f, 1.5f + rf() * 1.4f, 1f + rf() * 0.6f, 0.12f, 0xFFFF9AC8.toInt(), 0x00FF4080, 1)
            }
            "spite" -> repeat(3 + r) {
                val p = inDisc()
                ps.emit(p[0], p[1], 0.2f, rs() * 1.2f, rs() * 0.6f, 1f + rf() * 3f, 0.5f + rf() * 0.4f, 0.07f, 0xFFFF5040.toInt(), 0x00801008, 3, g = 6f)
            }
            "madness" -> repeat(3 + r) {
                val p = inDisc()
                val c = intArrayOf(0xFFFF60D0.toInt(), 0xFF60FFE0.toInt(), 0xFFFFE060.toInt(), 0xFFA070FF.toInt())[rnd.nextInt(4)]
                ps.emit(p[0], p[1], 0.2f, rs() * 1.5f, rs() * 0.8f, rs() * 2f + 1f, 0.7f, 0.08f, c, argb(0, c), 1, dr = 1f)
            }
            "plague" -> repeat(3 + r) {
                val p = inDisc()
                ps.emit(p[0], p[1], 0.1f, rs() * 0.6f, rs() * 0.3f, 0.7f + rf(), 1.4f + rf(), 0.16f, 0x8878D040.toInt(), 0x00305810, 0, dr = 0.3f, gr = 0.2f)
                if (rf() < 0.5f) ps.emit(p[0], p[1], 0.1f, rs() * 0.5f, rs() * 0.3f, 1f + rf(), 0.9f, 0.05f, 0xFFB8FF60.toInt(), 0x0050A020, 1)
            }
            "magnet" -> repeat(3 + r) {
                val a = rf() * 6.283f; val d = (rr + 5f) * (0.7f + rf() * 0.5f)
                ps.emit(x + cos(a) * d, y + sin(a) * d * 0.8f, 0.2f, -cos(a) * d * 1.6f, -sin(a) * d * 1.3f, 0f, 0.6f, 0.07f, 0x00A0C8FF, 0xFFFFFFFF.toInt(), 1)
            }
            "feast" -> repeat(3 + r) {
                val p = inDisc()
                val c = if (rf() < 0.5f) 0xFF90E860.toInt() else 0xFFFFE070.toInt()
                ps.emit(p[0], p[1], 0f, rs() * 0.3f, 0f, 1.4f + rf() * 1.4f, 1f, 0.09f, c, argb(0, c), 1)
            }
            "finger" -> {
                for (i in 0 until 16) ps.emit(x + rs() * 0.15f, y, 0f + i * 0.5f, 0f, 0f, 0f, 0.35f, 0.14f, 0xFFFFF8D0.toInt(), 0x00FFE070, 1)
                repeat(6) { ps.emit(x, y, 0.2f, rs() * 2f, rs(), 2f + rf() * 3f, 0.6f, 0.06f, 0xFFFFE890.toInt(), 0x00FFB030, 3, g = 10f) }
            }
        }
    }

    // ------------------------------------------------------------------ continuous effects
    private fun zoneTick(w: World, z: Zone, d: Float) {
        if (!vis(z.x, z.y, z.r + 14f)) return
        when (z.type) {
            Zone.RAIN -> {
                val n = (z.r * z.r * 4.5f * d).toInt() + (if (rf() < (z.r * z.r * 4.5f * d) % 1f) 1 else 0)
                repeat(min(n, 40)) {
                    val a = rf() * 6.283f; val dd = sqrt(rf()) * z.r
                    val h = 7f + rf() * 4f
                    ps.emit(z.x + cos(a) * dd, z.y + sin(a) * dd, h, 1.2f, 0f, -17f, h / 17f, 0.035f, 0xCCB4D8FF.toInt(), 0x558CB8F0, 3)
                }
                repeat(min((n * 0.5f).toInt() + 1, 20)) {
                    val a = rf() * 6.283f; val dd = sqrt(rf()) * z.r
                    ps.emit(z.x + cos(a) * dd, z.y + sin(a) * dd, 0.05f, 0f, 0f, 0.8f, 0.2f, 0.07f, 0xAADCEEFF.toInt(), 0x00B4D8FF, 1, g = 6f, gr = 0.2f)
                }
                if (rf() < 0.12f) {
                    val a = rf() * 6.283f; val dd = sqrt(rf()) * z.r
                    ps.emit(z.x + cos(a) * dd, z.y + sin(a) * dd, 0f, 0.4f, 0f, 0.1f, 1.8f, 0.7f, 0x228AA8C8, 0x008AA8C8, 0, gr = 0.4f)
                }
            }
            Zone.ACID -> {
                repeat(2) {
                    val a = rf() * 6.283f; val dd = sqrt(rf()) * z.r
                    ps.emit(z.x + cos(a) * dd, z.y + sin(a) * dd, 0f, rs() * 0.2f, rs() * 0.1f, 0.9f + rf(), 1.0f + rf() * 0.6f, 0.07f + rf() * 0.07f, 0xCCA8FF50.toInt(), 0x0050A010, 1)
                }
                repeat(3) {
                    val a = rf() * 6.283f; val dd = sqrt(rf()) * z.r
                    val h = 6f + rf() * 3f
                    ps.emit(z.x + cos(a) * dd, z.y + sin(a) * dd, h, 0f, 0f, -13f, h / 13f, 0.04f, 0xCCC0FF70.toInt(), 0x4470C020, 3)
                }
                if (rf() < 0.14f) {
                    val a = rf() * 6.283f; val dd = sqrt(rf()) * z.r
                    ps.emit(z.x + cos(a) * dd, z.y + sin(a) * dd, 0.1f, 0.3f, 0f, 0.4f, 1.8f, 0.55f, 0x3390D040, 0x0090D040, 0, gr = 0.4f)
                }
            }
            Zone.TORNADO -> {
                repeat(3) {
                    val a = rf() * 6.283f; val h = rf() * 7f
                    val rad = 0.5f + h * 0.4f
                    val px = z.x + cos(a) * rad; val py = z.y + sin(a) * rad * 0.5f
                    val sp = 6f
                    val c = if (rf() < 0.5f) 0xFF7A5A32.toInt() else 0xFF4C9A2A.toInt()
                    ps.emit(px, py, h * 0.6f, -sin(a) * sp, cos(a) * sp * 0.5f, 2f + rf() * 2f, 0.7f + rf() * 0.5f, 0.07f + rf() * 0.06f, c, argb(0, c), 2, dr = 0.2f)
                }
                if (rf() < 0.6f) ps.emit(z.x + rs() * 1.5f, z.y + rs() * 0.8f, 0.1f, rs() * 1.5f, rs() * 0.8f, 0.8f, 1.3f, 0.5f, 0x99A08A6A.toInt(), 0x00706048, 0, gr = 0.6f)
            }
            Zone.BLACKHOLE -> {
                repeat(5) {
                    val a = rf() * 6.283f; val dd = z.r * (1.8f + rf() * 1.8f)
                    val px = z.x + cos(a) * dd; val py = z.y + sin(a) * dd * 0.55f
                    val sp = dd * 2.4f
                    val c = if (rf() < 0.5f) 0xFFC08AFF.toInt() else 0xFFFFB060.toInt()
                    ps.emit(px, py, 0f, -cos(a) * sp * 0.7f - sin(a) * sp * 0.5f, -sin(a) * sp * 0.4f + cos(a) * sp * 0.3f, 0f, 0.55f, 0.07f, c, argb(0, c), 1)
                }
            }
            Zone.VOLCANO -> {
                val rate = (30f * d * (0.6f + z.r * 0.15f))
                repeat(rate.toInt() + (if (rf() < rate % 1f) 1 else 0)) {
                    val a = rf() * 6.283f; val dd = rf() * z.r * 0.3f
                    val sp = 0.8f + rf() * 2.2f
                    ps.emit(z.x + cos(a) * dd, z.y + sin(a) * dd * 0.7f, 0.4f, cos(a) * sp, sin(a) * sp * 0.6f, 9f + rf() * 11f,
                        1.1f + rf() * 0.8f, 0.07f + rf() * 0.07f, 0xFFFFE070.toInt(), 0x00E02808, 1, g = 16f)
                }
                if (rf() < 0.22f) ps.emit(z.x + rs() * 1.2f, z.y + rs() * 0.8f, 1f, rs() * 1.2f, rs() * 0.6f, 2.5f + rf() * 2f, 2.6f + rf() * 1.2f, 0.4f,
                    0x663A322E, 0x00201C1A, 0, dr = 0.3f, gr = 0.45f)
                if (rf() < 0.1f) ps.emit(z.x, z.y, 0.5f, rs() * 5f, rs() * 3f, 8f + rf() * 6f, 1.6f, 0.16f, 0xFFFFA030.toInt(), 0x00C02008, 1, g = 14f)
            }
            Zone.METEOR -> {
                val fall = (z.life / 0.9f).coerceIn(0f, 1f)
                val hx = z.x + fall * 6f
                val hy = z.y - fall * 22f
                repeat(4) {
                    ps.emit(hx + rs() * 0.2f, hy + rs() * 0.2f, 0f, rs() * 1.5f, rs() * 1.5f, rs(), 0.5f + rf() * 0.4f, 0.28f + rf() * 0.2f,
                        0xFFFFD060.toInt(), 0x00B01808, 1, dr = 1.5f)
                }
                if (rf() < 0.3f) ps.emit(hx, hy, 0f, rs() * 0.6f, rs() * 0.6f, 0.2f, 1.1f, 0.3f, 0x66403838, 0x00201818, 0, gr = 0.45f)
            }
        }
    }

    private fun fireTick(w: World, d: Float) {
        if (s < 2.5f) return
        val list = w.burningTiles()
        val n = list.size
        if (n == 0) return
        val stride = max(1, n / 450)
        var i = rnd.nextInt(stride)
        val k = d * 60f
        while (i < n) {
            val idx = list[i]
            val x = (idx % w.w).toFloat()
            val y = (idx / w.w).toFloat()
            if (vis(x, y, 2f)) {
                if (rf() < 0.10f * k) {
                    val sz = 0.17f + rf() * 0.1f
                    ps.emit(x + 0.2f + rf() * 0.6f, y + 0.55f + rf() * 0.3f, 0.05f, rs() * 0.25f, rs() * 0.1f, 1.3f + rf() * 1.4f, 0.5f + rf() * 0.4f,
                        sz, 0xFFFFC858.toInt(), 0x00D82010, 1, gr = -sz * 1.1f)
                }
                if (rf() < 0.012f * k) {
                    ps.emit(x + 0.5f, y + 0.4f, 0.5f, rs() * 0.3f, rs() * 0.1f, 1.0f + rf() * 0.8f, 1.4f + rf() * 0.8f, 0.2f,
                        0x55403A36, 0x00201C1A, 0, dr = 0.3f, gr = 0.35f)
                }
                if (rf() < 0.018f * k) {
                    ps.emit(x + 0.5f, y + 0.5f, 0.3f, rs() * 1.2f, rs() * 0.6f, 3f + rf() * 3f, 0.9f, 0.05f, 0xFFFFD070.toInt(), 0x00FF4010, 3, g = 6f)
                }
            }
            i += stride
        }
    }

    private fun workTick(w: World) {
        if (s < 6f) return
        for (c in w.creatures) {
            if (!c.alive || c.inside || c.act != Act.WORK || !vis(c.x, c.y, 2f)) continue
            if (sin(w.time * 10.0 + c.id) < 0.9 || rf() > 0.35f) continue
            val t = c.workTile
            val x = if (t >= 0) (t % w.w) + 0.5f else c.x
            val y = if (t >= 0) (t / w.w) + 0.7f else c.y
            when (c.job) {
                Job.WOOD -> repeat(3) {
                    val col = if (rf() < 0.6f) 0xFFC89A5A.toInt() else 0xFF4C9A2A.toInt()
                    ps.emit(x, y, 0.5f, rs() * 1.8f, rs() * 0.8f, 2f + rf() * 2.5f, 0.6f, 0.05f + rf() * 0.04f, col, argb(0, col), 2, g = 14f)
                }
                Job.FARM -> repeat(3) {
                    ps.emit(x + rs() * 0.3f, y, 0.1f, rs() * 0.7f, rs() * 0.4f, 0.8f + rf() * 1.2f, 0.7f, 0.12f, 0xAA8B6A3A.toInt(), 0x008B6A3A, 0, gr = 0.3f)
                }
                Job.MINE -> {
                    repeat(4) { ps.emit(x, y, 0.5f, rs() * 2.5f, rs() * 1.2f, 1.5f + rf() * 3f, 0.4f, 0.05f, 0xFFFFE890.toInt(), 0x00FF8030, 3, g = 16f) }
                    ps.emit(x, y, 0.2f, rs() * 0.5f, rs() * 0.3f, 0.8f, 0.9f, 0.2f, 0x88A0A0A8.toInt(), 0x00606068, 0, gr = 0.4f)
                }
                Job.BUILD -> repeat(3) {
                    ps.emit(x, y, 0.5f, rs() * 1.5f, rs() * 0.7f, 1.5f + rf() * 2f, 0.45f, 0.04f, 0xFFFFE8A0.toInt(), 0x00FF9030, 3, g = 12f)
                }
            }
        }
    }

    // ------------------------------------------------------------------ drawing
    private fun jag(x0: Float, y0: Float, x1: Float, y1: Float, rng: java.util.Random, disp: Float, levels: Int): FloatArray {
        var pts = floatArrayOf(x0, y0, x1, y1)
        var d = disp
        for (l in 0 until levels) {
            val out = FloatArray(pts.size * 2 - 2)
            var o = 0
            for (i in 0 until pts.size / 2 - 1) {
                val ax = pts[i * 2]; val ay = pts[i * 2 + 1]; val bx = pts[i * 2 + 2]; val by = pts[i * 2 + 3]
                out[o++] = ax; out[o++] = ay
                out[o++] = (ax + bx) / 2f + (rng.nextFloat() - 0.5f) * d
                out[o++] = (ay + by) / 2f + (rng.nextFloat() - 0.5f) * d * 0.3f
            }
            out[o++] = pts[pts.size - 2]; out[o++] = pts[pts.size - 1]
            pts = out.copyOf(o)
            d *= 0.55f
        }
        return pts
    }

    private fun strokePts(canvas: Canvas, pts: FloatArray, paint: Paint) {
        for (i in 0 until pts.size / 2 - 1) canvas.drawLine(pts[i * 2], pts[i * 2 + 1], pts[i * 2 + 2], pts[i * 2 + 3], paint)
    }

    private fun drawBolt(canvas: Canvas, f: Fx, ox: Float, oy: Float, p: Float) {
        val tx = ox + f.x * s
        val ty = oy + f.y * s
        val seed = (f.x * 1000f).toLong() * 31L + (f.y * 1000f).toLong()
        val rng = java.util.Random(seed)
        val topY = min(ty - 40f * s, ty - vh * 0.6f)
        val topX = tx + (rng.nextFloat() - 0.5f) * 14f * s
        val pts = jag(topX, topY, tx, ty, rng, 12f * s + 30f * dp, 6)
        val a = ((1f - p) * (if (p < 0.12f) 1f else 0.85f) * 255).toInt().coerceIn(0, 255)
        addLine.color = argb((a * 0.35f).toInt(), 0x6A8CFF); addLine.strokeWidth = 14f * dp
        strokePts(canvas, pts, addLine)
        addLine.color = argb((a * 0.7f).toInt(), 0xB8D0FF); addLine.strokeWidth = 6f * dp
        strokePts(canvas, pts, addLine)
        line.color = argb(a, 0xFFFFFF); line.strokeWidth = 2.5f * dp
        strokePts(canvas, pts, line)
        // branches
        for (b in 0 until 3) {
            val k = (pts.size / 2) * (b + 1) / 4
            val bx = pts[k * 2]; val by = pts[k * 2 + 1]
            val ex = bx + (rng.nextFloat() - 0.5f) * 10f * s - 20f * dp
            val ey = by + (6f + rng.nextFloat() * 8f) * s * 0.5f + 30f * dp
            val bp = jag(bx, by, ex, ey, rng, 6f * s + 14f * dp, 3)
            addLine.color = argb((a * 0.4f).toInt(), 0x8AA8FF); addLine.strokeWidth = 5f * dp
            strokePts(canvas, bp, addLine)
            line.color = argb((a * 0.85f).toInt(), 0xF0F4FF); line.strokeWidth = 1.6f * dp
            strokePts(canvas, bp, line)
        }
        // ground glow
        add.color = argb((a * 0.22f).toInt(), 0x90B0FF)
        canvas.drawCircle(tx, ty, 1.1f * s + 8f * dp, add)
    }

    private fun drawCloud(canvas: Canvas, cx: Float, cy: Float, rad: Float, rgb: Int, alpha: Int) {
        for (layer in 0 until 2) {
            for (i in 0 until 9) {
                val ang = i * 0.7f + clock * 0.12f
                val px = cx + cos(ang) * rad * 0.62f * (0.5f + 0.5f * sin(i * 1.3f))
                val py = cy + sin(ang * 1.3f) * rad * 0.2f - layer * rad * 0.08f
                val pr = rad * (0.34f + 0.14f * sin(i * 2.1f)) * (if (layer == 0) 1f else 0.7f)
                fill.color = argb(if (layer == 0) alpha else (alpha * 0.8f).toInt(),
                    if (layer == 0) rgb else (min(255, (rgb shr 16 and 255) + 38) shl 16) or (min(255, (rgb shr 8 and 255) + 38) shl 8) or min(255, (rgb and 255) + 38))
                canvas.drawCircle(px, py - (if (layer == 1) pr * 0.25f else 0f), pr, fill)
            }
        }
    }

    /** Clouds, funnels, disks, shockwaves, cracks and bolts. Drawn above creatures, below the night tint. */
    fun drawShapes(canvas: Canvas, w: World, ox: Float, oy: Float) {
        // fx
        for (f in w.fxs()) {
            val p = (f.t / f.life).coerceIn(0f, 1f)
            val cx = ox + f.x * s
            val cy = oy + f.y * s
            if (cx < -300 || cx > vw + 300 || cy < -300 || cy > vh + 300) continue
            when (f.type) {
                Fx.EXPLOSION -> {
                    val rad = f.r * s * (0.35f + p * 1.5f)
                    line.color = argb(((1f - p) * 230).toInt(), 0xFFE8B0); line.strokeWidth = max(2f * dp, f.r * s * 0.12f * (1f - p))
                    canvas.drawCircle(cx, cy, rad, line)
                    line.color = argb(((1f - p) * 120).toInt(), 0xFF8A30); line.strokeWidth = max(1f * dp, f.r * s * 0.05f)
                    canvas.drawCircle(cx, cy, rad * 0.75f, line)
                    add.color = argb(((1f - p) * (1f - p) * 75).toInt(), 0xFF7A20)
                    canvas.drawCircle(cx, cy, f.r * s * (0.35f + p * 0.4f), add)
                }
                Fx.RING, Fx.CURSE -> {
                    val c = if (f.type == Fx.CURSE) 0xB868FF else if (f.color != 0) (f.color and 0xFFFFFF) else 0xFFD27A
                    line.color = argb(((1f - p) * 220).toInt(), c); line.strokeWidth = 3f * dp
                    canvas.drawCircle(cx, cy, f.r * s * (0.25f + p * 0.85f), line)
                    addLine.color = argb(((1f - p) * 90).toInt(), c); addLine.strokeWidth = 9f * dp
                    canvas.drawCircle(cx, cy, f.r * s * (0.25f + p * 0.85f), addLine)
                }
                Fx.SPAWN -> {
                    line.color = argb(((1f - p) * 200).toInt(), 0xFFFFFF); line.strokeWidth = 2f * dp
                    canvas.drawCircle(cx, cy, f.r * s * (0.2f + p), line)
                }
                Fx.QUAKE -> {
                    val rng = java.util.Random((f.x * 100f).toLong() * 7L + (f.y * 100f).toLong())
                    line.color = argb(((1f - p) * 230).toInt(), 0x1E140C); line.strokeWidth = max(1.5f * dp, s * 0.12f)
                    for (k in 0 until 9) {
                        val a = k * 0.70f + rng.nextFloat() * 0.4f
                        val reach = f.r * s * min(1f, p * 3f) * (0.5f + rng.nextFloat() * 0.6f)
                        val pts = jag(cx, cy, cx + cos(a) * reach, cy + sin(a) * reach * 0.8f, rng, reach * 0.35f, 4)
                        strokePts(canvas, pts, line)
                    }
                    line.color = argb(((1f - p) * 150).toInt(), 0xC8A070); line.strokeWidth = 3f * dp
                    canvas.drawCircle(cx, cy, f.r * s * (0.3f + p * 1.0f), line)
                }
                Fx.LIGHTNING -> drawBolt(canvas, f, ox, oy, p)
            }
        }
        // zones
        for (z in w.zones) {
            val cx = ox + z.x * s
            val cy = oy + z.y * s
            val rr = z.r * s
            if (cx + rr * 3 < 0 || cx - rr * 3 > vw || cy + rr * 4 < 0 || cy - rr * 6 > vh) continue
            when (z.type) {
                Zone.RAIN -> {
                    fill.color = 0x2A2A4A78; canvas.drawCircle(cx, cy, rr, fill)
                    drawCloud(canvas, cx, cy - z.r * 0.95f * s, rr * 1.25f, 0x58667A, 215)
                }
                Zone.ACID -> {
                    fill.color = 0x3470B018; canvas.drawCircle(cx, cy, rr, fill)
                    drawCloud(canvas, cx, cy - z.r * 0.95f * s, rr * 1.2f, 0x5C7A38, 215)
                }
                Zone.TORNADO -> {
                    val height = 9f * s
                    val layers = 16
                    for (k in 0 until layers) {
                        val t = k / (layers - 1f)
                        val yy = cy - t * height
                        val ring = (0.55f + t * 3.2f) * s
                        val sway = sin(clock * 3f + t * 4f) * s * (0.25f + t * 0.7f)
                        oval.set(cx + sway - ring, yy - ring * 0.28f, cx + sway + ring, yy + ring * 0.28f)
                        fill.color = argb((150 - t * 60).toInt(), 0xCFCFD8)
                        canvas.drawOval(oval, fill)
                        line.color = argb(190, 0x4C4C58); line.strokeWidth = max(1.5f * dp, s * 0.1f)
                        canvas.drawArc(oval, (clock * 400f + k * 47f) % 360f, 110f, false, line)
                    }
                    drawCloud(canvas, cx, cy - height - s * 0.6f, s * 4.2f, 0x3A3F4A, 230)
                }
                Zone.BLACKHOLE -> {
                    val big = max(rr, 2f * s) * 1.7f
                    for (k in 0 until 6) {
                        val ring = big * (1f - k * 0.13f)
                        oval.set(cx - ring, cy - ring * 0.42f, cx + ring, cy + ring * 0.42f)
                        val c = when (k) { 0 -> 0x6030C0; 1 -> 0x8A40D8; 2 -> 0xC06AE0; 3 -> 0xFF8A50; 4 -> 0xFFC070; else -> 0xFFF0C0 }
                        addLine.color = argb(120 + k * 14, c); addLine.strokeWidth = big * 0.11f
                        canvas.drawOval(oval, addLine)
                        addLine.color = argb(210, 0xFFFFFF); addLine.strokeWidth = max(1.5f * dp, big * 0.035f)
                        canvas.drawArc(oval, (clock * (200f + k * 60f) + k * 70f) % 360f, 55f, false, addLine)
                    }
                    fill.color = 0xFF000000.toInt()
                    canvas.drawCircle(cx, cy, rr * 0.8f + 2f * dp, fill)
                    addLine.color = argb(200, 0xFFE8FF); addLine.strokeWidth = 2f * dp
                    canvas.drawCircle(cx, cy, rr * 0.8f + 4f * dp, addLine)
                }
                Zone.VOLCANO -> {
                    val pulse = 0.7f + 0.3f * sin(clock * 5f)
                    for (k in 0 until 3) {
                        add.color = argb((70 * pulse / (k + 1)).toInt(), 0xFF6A18)
                        canvas.drawCircle(cx, cy - s * 0.4f, rr * (1.9f - k * 0.5f), add)
                    }
                }
                Zone.METEOR -> {
                    val fall = (z.life / 0.9f).coerceIn(0f, 1f)
                    fill.color = argb(((1f - fall) * 110).toInt(), 0x000000)
                    canvas.drawCircle(cx, cy, (1f - fall) * 3.5f * s + 4f * dp, fill)          // shadow growing on the ground
                    val hx = cx + fall * 6f * s
                    val hy = cy - fall * 22f * s
                    add.color = argb(110, 0xFF8A30); canvas.drawCircle(hx, hy, 2.4f * s + 14f * dp, add)
                    add.color = argb(220, 0xFFE8A0); canvas.drawCircle(hx, hy, 1.1f * s + 7f * dp, add)
                    add.color = argb(255, 0xFFFFFF); canvas.drawCircle(hx, hy, 0.5f * s + 3.5f * dp, add)
                }
            }
        }
    }

    /** Additive glow, sparks, smoke, debris. Drawn above the night tint so fire lights up the dark. */
    fun drawParticles(canvas: Canvas, ox: Float, oy: Float) {
        for (i in 0 until ps.n) {
            val sx = ox + ps.x[i] * s
            val sy = oy + (ps.y[i] - ps.z[i]) * s
            if (sx < -40f || sx > vw + 40f || sy < -40f || sy > vh + 40f) continue
            val age = ps.maxLife[i] - ps.life[i]
            val t = age / ps.maxLife[i]
            val col = lerpC(ps.col0[i], ps.col1[i], t)
            if ((col ushr 24) < 3) continue
            val rad = max(0.8f * dp, (ps.size[i] + ps.grow[i] * age) * s)
            when (ps.kind[i].toInt()) {
                0 -> {
                    val a = col ushr 24
                    val base = col and 0x00FFFFFF
                    if (rad < 3f * dp) { fill.color = col; canvas.drawCircle(sx, sy, rad, fill) }
                    else {
                        fill.color = base or ((a * 0.45f).toInt() shl 24); canvas.drawCircle(sx, sy, rad, fill)
                        fill.color = base or ((a * 0.45f).toInt() shl 24); canvas.drawCircle(sx, sy, rad * 0.72f, fill)
                        fill.color = base or ((a * 0.45f).toInt() shl 24); canvas.drawCircle(sx, sy, rad * 0.45f, fill)
                    }
                }
                1 -> {
                    add.color = col
                    canvas.drawCircle(sx, sy, rad, add)
                    if (rad > 3f * dp) { add.color = (col and 0x00FFFFFF) or (((col ushr 24) / 4) shl 24); canvas.drawCircle(sx, sy, rad * 2.1f, add) }
                }
                2 -> { fill.color = col; canvas.drawRect(sx - rad, sy - rad, sx + rad, sy + rad, fill) }
                3 -> {
                    addLine.color = col
                    addLine.strokeWidth = max(1f * dp, ps.size[i] * s)
                    val dx = ps.vx[i] * s * 0.06f
                    val dy = (ps.vy[i] - ps.vz[i]) * s * 0.06f
                    canvas.drawLine(sx - dx, sy - dy, sx, sy, addLine)
                }
            }
        }
    }

    fun drawScreen(canvas: Canvas) {
        if (flash > 0.01f) {
            fill.color = argb((flash * 255).toInt().coerceIn(0, 255), flashColor)
            canvas.drawRect(0f, 0f, vw, vh, fill)
        }
    }
}
