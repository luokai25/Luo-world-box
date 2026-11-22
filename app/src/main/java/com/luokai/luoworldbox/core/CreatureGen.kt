package com.luokai.luoworldbox.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Detailed 24x24 pixel-art creatures, generated in code.
 * Shapes are shaded ellipses (light from the top-left), so bodies look round instead of boxy.
 * 4 walk frames per creature and a colour variant so a crowd of villagers is not made of clones.
 */
object CreatureGen {
    const val N = 24
    private const val OUT = 0x1A120E

    private fun c(rgb: Int): Int = rgb or (0xFF shl 24)

    fun lighten(rgb: Int, f: Float): Int {
        val r = min(255, ((rgb shr 16 and 255) * f).toInt())
        val g = min(255, ((rgb shr 8 and 255) * f).toInt())
        val b = min(255, ((rgb and 255) * f).toInt())
        return (r shl 16) or (g shl 8) or b
    }

    private class Px {
        val a = IntArray(N * N)

        fun set(x: Int, y: Int, rgb: Int) { if (x in 0 until N && y in 0 until N) a[y * N + x] = c(rgb) }

        fun rect(x: Int, y: Int, w: Int, h: Int, rgb: Int) {
            for (yy in y until y + h) for (xx in x until x + w) set(xx, yy, rgb)
        }

        fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, rgb: Int, shade: Boolean = true) {
            val x0 = (cx - rx).toInt() - 1; val x1 = (cx + rx).toInt() + 1
            val y0 = (cy - ry).toInt() - 1; val y1 = (cy + ry).toInt() + 1
            for (y in y0..y1) for (x in x0..x1) {
                val dx = (x + 0.5f - cx) / rx
                val dy = (y + 0.5f - cy) / ry
                val d = dx * dx + dy * dy
                if (d > 1f) continue
                var f = 1f
                if (shade) {
                    val lit = -(dx * 0.55f + dy * 0.85f)
                    f = if (lit > 0.5f) 1.2f else if (lit > 0.1f) 1.07f else if (lit < -0.45f) 0.74f else if (lit < -0.1f) 0.88f else 1f
                    if (d > 0.82f && lit < 0f) f *= 0.9f
                }
                set(x, y, lighten(rgb, f))
            }
        }

        /** thick line, used for legs, arms, tails, horns */
        fun line(x0: Float, y0: Float, x1: Float, y1: Float, rgb: Int, thick: Int = 1) {
            val steps = (max(abs(x1 - x0), abs(y1 - y0)) * 2).toInt().coerceAtLeast(1)
            for (i in 0..steps) {
                val t = i / steps.toFloat()
                val x = (x0 + (x1 - x0) * t).toInt()
                val y = (y0 + (y1 - y0) * t).toInt()
                for (ty in 0 until thick) for (tx in 0 until thick) set(x + tx, y + ty, rgb)
            }
        }

        fun outline() {
            val src = a.copyOf()
            for (y in 0 until N) for (x in 0 until N) {
                if (src[y * N + x] != 0) continue
                var near = false
                if (x > 0 && src[y * N + x - 1] != 0) near = true
                if (x < N - 1 && src[y * N + x + 1] != 0) near = true
                if (y > 0 && src[(y - 1) * N + x] != 0) near = true
                if (y < N - 1 && src[(y + 1) * N + x] != 0) near = true
                if (near) a[y * N + x] = c(OUT)
            }
        }

        fun flipped(): IntArray {
            val o = IntArray(N * N)
            for (y in 0 until N) for (x in 0 until N) o[y * N + (N - 1 - x)] = a[y * N + x]
            return o
        }
    }

    private fun swing(frame: Int, phase: Float): Float = sin((frame / 4f + phase) * 2f * PI.toFloat())

    // ------------------------------------------------------------------ people
    private fun person(
        p: Px, f: Int, variant: Int,
        skin: Int, hair: Int, tunic: Int, trim: Int, pants: Int, boots: Int,
        broad: Float = 1f, short: Boolean = false, longHair: Boolean = false
    ) {
        val sw = swing(f, 0f)
        val lift = (if (short) 2 else 0)
        val hipY = 14 + lift
        val footY = 22
        val bob = if (f == 1 || f == 3) 1 else 0
        // far leg + far arm (darker)
        val farFootX = 11f - sw * 2.4f
        val nearFootX = 13f + sw * 2.4f
        p.line(11f, hipY.toFloat(), farFootX, footY - (if (sw > 0.3f) 1 else 0).toFloat(), lighten(pants, 0.7f), 2)
        p.rect(farFootX.toInt() - 1, footY - 1, 4, 2, lighten(boots, 0.75f))
        if (longHair) p.ellipse(11.2f, 8f + lift, 3.4f, 5f, lighten(hair, 0.8f))
        p.line(8f, 9f + lift, 8f + sw * 2f, 15f + lift, lighten(tunic, 0.72f), 2)
        // torso
        p.ellipse(12f, 11.5f + lift - bob * 0.5f, 4.4f * broad, 4.6f, tunic)
        p.rect(8, hipY - 1, (8 * broad).toInt().coerceAtLeast(7), 1, lighten(trim, 0.85f))   // belt
        p.set(12, hipY - 1, 0xE0B040)
        p.rect(10, 7 + lift, 4, 1, trim)   // collar
        // near leg
        p.line(13f, hipY.toFloat(), nearFootX, footY - (if (sw < -0.3f) 1 else 0).toFloat(), pants, 2)
        p.rect(nearFootX.toInt() - 1, footY - 1, 4, 2, boots)
        // head
        val hy = 5f + lift - bob * 0.5f
        p.ellipse(13f, hy, 3.7f, 3.7f, skin)
        p.ellipse(12.2f, hy - 1.7f, 3.9f, 2.5f, hair)
        p.ellipse(14.3f, hy + 0.8f, 2.7f, 2.5f, skin, shade = false)   // face
        p.set(15, hy.toInt(), 0x1E1410); p.set(16, hy.toInt(), 0x1E1410)   // eye
        p.set(15, hy.toInt() - 1, lighten(hair, 0.7f))                     // brow
        p.set(15, hy.toInt() + 2, lighten(skin, 0.7f))                     // mouth
        p.set(17, hy.toInt() + 1, lighten(skin, 1.1f))                     // nose
        // near arm + hand
        p.line(16f, 9f + lift, 16f - sw * 2.2f, 15f + lift, tunic, 2)
        p.ellipse(16f - sw * 2.2f + 0.8f, 15.8f + lift, 1.3f, 1.3f, skin)
        p.ellipse(8f + sw * 2f + 0.8f, 15.8f + lift, 1.2f, 1.2f, lighten(skin, 0.8f))
    }

    private fun human(p: Px, f: Int, v: Int) {
        val tunics = intArrayOf(0x3B6FD6, 0xB83A3A, 0x5A8A3A, 0x8A5AA8)
        val hairs = intArrayOf(0x5A3A1E, 0x1E1612, 0xC8A050, 0x8A3A1E)
        val skins = intArrayOf(0xE8B88A, 0xC8946A, 0xF2CFAA, 0xA8744A)
        person(p, f, v, skins[v and 3], hairs[(v shr 1) and 3], tunics[v and 3], 0xF0E8D0, 0x4A3A2A, 0x3A2616)
    }

    private fun elf(p: Px, f: Int, v: Int) {
        val tunics = intArrayOf(0x3FA34D, 0x2E8A8A, 0x6A9A3A, 0x7A6AC8)
        val hairs = intArrayOf(0xE8D890, 0xE8E8F0, 0x9A6A3A, 0x4A6AA8)
        person(p, f, v, 0xF2D8BA, hairs[v and 3], tunics[v and 3], 0xD8C878, 0x5A4A2A, 0x4A3A22, broad = 0.82f, longHair = true)
        p.set(10, 5, 0xF2D8BA); p.set(9, 4, 0xF2D8BA); p.set(9, 3, 0xE8C8A8)   // pointed ear
        p.line(10f, 9f, 12f, 12f, 0x2E6A38, 1)   // cape clasp line
        p.set(12, 9, 0xE8E0A0)
    }

    private fun orc(p: Px, f: Int, v: Int) {
        val skins = intArrayOf(0x6FA84A, 0x5A9A4A, 0x7AB058, 0x88A04A)
        person(p, f, v, skins[v and 3], 0x1E1E1E, 0x7A4A2A, 0x3A2A1A, 0x3A2A1A, 0x2A1E14, broad = 1.25f)
        p.ellipse(8.2f, 8.6f, 2.0f, 1.6f, 0x8A8A92)    // pauldrons
        p.ellipse(15.8f, 8.6f, 2.0f, 1.6f, 0x9A9AA2)
        p.set(16, 7, 0xF4F0E0); p.set(17, 7, 0xF4F0E0)  // tusks
        p.set(15, 3, 0x1E1E1E); p.set(14, 2, 0x1E1E1E); p.set(13, 1, 0x1E1E1E)  // mohawk
        p.line(15f, 3f, 17f, 3f, 0x1E1E1E, 1)           // heavy brow
    }

    private fun dwarf(p: Px, f: Int, v: Int) {
        val beards = intArrayOf(0xD98A2B, 0x8A4A1E, 0xC8C8C8, 0x3A2A1E)
        val tunics = intArrayOf(0x8A5A3A, 0x7A3A3A, 0x4A6A8A, 0x6A6A3A)
        person(p, f, v, 0xE0A878, 0x909090, tunics[v and 3], 0xD0A030, 0x4A3A2A, 0x2E2018, broad = 1.15f, short = true)
        p.ellipse(13.2f, 10.2f, 3.4f, 3.6f, beards[(v shr 1) and 3])   // big beard
        p.ellipse(12.4f, 4.2f, 4.0f, 2.4f, 0xA8A8B0)                  // helmet
        p.rect(9, 5, 8, 1, 0x7A7A82)
        p.set(9, 3, 0xE8E0C0); p.set(8, 2, 0xE8E0C0); p.set(16, 3, 0xE8E0C0); p.set(17, 2, 0xE8E0C0)   // horns
    }

    private fun zombie(p: Px, f: Int, v: Int) {
        val sw = swing(f, 0f)
        val bob = if (f == 1 || f == 3) 1 else 0
        p.line(11f, 14f, 11f - sw * 2f, 22f, 0x3A3A44, 2); p.rect(10 - (sw * 2).toInt(), 21, 4, 2, 0x2A2A2E)
        p.line(8f, 10f, 17f, 10f, 0x6E8A5A, 2)                      // arm stretched forward
        p.ellipse(12f, 11.5f - bob * 0.5f, 4.2f, 4.6f, 0x56564A)
        p.set(10, 11, 0x2E2E26); p.set(14, 13, 0x2E2E26); p.set(12, 15, 0x2E2E26)        // rips
        p.rect(10, 12, 3, 1, 0xD8D0A8)                                                  // ribs
        p.line(13f, 14f, 13f + sw * 2f, 22f, 0x4A4A56, 2); p.rect(12 + (sw * 2).toInt(), 21, 4, 2, 0x2A2A2E)
        p.ellipse(13f, 5f - bob * 0.5f, 3.7f, 3.7f, 0x8AAE76)
        p.set(15, 4, 0xFF3030); p.set(16, 4, 0xFF6060)               // glowing eyes
        p.line(14f, 7f, 17f, 7f, 0x2E3A2A, 1)                        // mouth
        p.set(11, 1, 0x4A5A3A); p.set(12, 1, 0x3A4A2E)               // tufts of hair
        p.ellipse(18f, 10f, 1.4f, 1.4f, 0x8AAE76)                    // hand
    }

    // ------------------------------------------------------------------ four legged animals
    private class Q(
        val body: Int, val belly: Int, val head: Int, val snout: Int, val leg: Int, val hoof: Int, val tail: Int,
        val rx: Float = 6.3f, val ry: Float = 3.7f, val legLen: Float = 7f, val headR: Float = 2.5f,
        val neckUp: Float = 0f, val fat: Boolean = false
    )

    private fun legs(p: Px, f: Int, q: Q, y: Float) {
        // far legs darker, drawn first; near legs on top
        val order = arrayOf(floatArrayOf(14.5f, 0.5f, 0f), floatArrayOf(6.5f, 0.25f, 0f), floatArrayOf(16.2f, 0f, 1f), floatArrayOf(8.2f, 0.75f, 1f))
        for (o in order) {
            val hip = o[0]
            val s = swing(f, o[1])
            val near = o[2] > 0.5f
            val col = if (near) q.leg else lighten(q.leg, 0.72f)
            val foot = 22f - max(0f, -cos((f / 4f + o[1]) * 2f * PI.toFloat())) * 1.6f
            p.line(hip, y, hip + s * 2.4f, foot - 1f, col, 2)
            p.rect((hip + s * 2.4f).toInt(), foot.toInt() - 1, 2, 2, if (near) q.hoof else lighten(q.hoof, 0.75f))
        }
    }

    private fun quad(p: Px, f: Int, q: Q) {
        val bob = if (f == 1 || f == 3) 0.6f else 0f
        val by = 12.3f - bob
        legs(p, f, q, by + 2f)
        p.ellipse(11f, by, q.rx, q.ry, q.body)
        p.ellipse(7.2f, by + 0.2f, q.rx * 0.55f, q.ry * 1.02f, q.body)             // haunch
        p.ellipse(15f, by - 0.3f, q.rx * 0.55f, q.ry * 1.0f, q.body)               // shoulder
        p.ellipse(11f, by + q.ry * 0.62f, q.rx * 0.7f, q.ry * 0.38f, q.belly)       // belly
        p.ellipse(17.6f, by - 2.2f - q.neckUp, 2.3f, 3.1f, q.body)                   // neck
        val hx = 19.4f
        val hy = by - 3.6f - q.neckUp
        p.ellipse(hx, hy, q.headR, q.headR * 0.92f, q.head)
        p.ellipse(hx + q.headR * 0.8f, hy + 0.9f, q.headR * 0.65f, q.headR * 0.5f, q.snout)
        p.set((hx + 1).toInt(), (hy - 0.8f).toInt(), 0x120C08)
        p.set((hx + 1).toInt() + 1, (hy - 1.4f).toInt(), 0xFFFFFF)   // eye glint
        p.set((hx + q.headR * 1.3f).toInt(), (hy + 0.8f).toInt(), 0x201010)   // nostril
        p.line(4.2f, by - 1.4f, 2.2f, by - 0.5f + (if (f == 1) -1f else 0f), q.tail, 2)   // tail
    }

    private fun pointedEars(p: Px, q: Q, f: Int, col: Int) {
        val hy = 12.3f - 3.6f - q.neckUp
        p.line(18.4f, hy - 2f, 18.0f, hy - 4.6f, col, 1); p.line(20.6f, hy - 2f, 21.2f, hy - 4.4f, col, 1)
    }

    private fun sheep(p: Px, f: Int, v: Int) {
        quad(p, f, Q(0xF2F2F2, 0xDADADA, 0x3A3A40, 0x2A2A30, 0x3A3A40, 0x1E1E22, 0xF2F2F2, ry = 4.2f, legLen = 6f))
        val by = 12.3f - (if (f == 1 || f == 3) 0.6f else 0f)
        for ((ex, ey, r) in listOf(Triple(7.5f, -2.2f, 2.4f), Triple(10.5f, -3f, 2.6f), Triple(13.5f, -2.4f, 2.4f), Triple(15.2f, -0.5f, 2.2f), Triple(6.2f, 0.2f, 2.2f)))
            p.ellipse(ex, by + ey, r, r, 0xFFFFFF)
        p.ellipse(19.4f, by - 3.6f, 2.0f, 2.1f, 0x4A4A52)
        p.ellipse(18.0f, by - 6.0f, 1.2f, 0.8f, 0xFFFFFF)   // fluffy forehead
    }

    private fun cow(p: Px, f: Int, v: Int) {
        val patchy = intArrayOf(0x2A2A2E, 0x6A3A1E, 0x2A2A2E, 0xA8643A)
        quad(p, f, Q(0xF4EEE4, 0xF0C8C0, 0xF4EEE4, 0xF0A8A8, 0x3A3A3A, 0x2A2A2A, 0x3A3A3A, rx = 7f, ry = 4.4f, legLen = 7f))
        val by = 12.3f - (if (f == 1 || f == 3) 0.6f else 0f)
        p.ellipse(8f, by - 1.5f, 2.6f, 1.9f, patchy[v and 3], shade = false)
        p.ellipse(13.5f, by + 0.8f, 2.2f, 1.7f, patchy[v and 3], shade = false)
        p.ellipse(11f, by - 3.2f, 1.7f, 1.0f, patchy[v and 3], shade = false)
        p.set(18, (by - 8.0f).toInt(), 0xEAE4D0); p.set(21, (by - 8.0f).toInt(), 0xEAE4D0)   // horns
        p.set(17, (by - 6.6f).toInt(), patchy[v and 3])
    }

    private fun pig(p: Px, f: Int, v: Int) {
        quad(p, f, Q(0xF2B0B0, 0xE89A9A, 0xF2B0B0, 0xE88A8A, 0xE69090, 0x8A5A5A, 0xE69090, rx = 6.4f, ry = 4.1f, legLen = 5f, fat = true))
        val by = 12.3f - (if (f == 1 || f == 3) 0.6f else 0f)
        p.set(22, (by - 2.6f).toInt(), 0x9A4A4A); p.set(22, (by - 1.6f).toInt(), 0x9A4A4A)   // nostrils
        p.ellipse(18.4f, by - 6.2f, 1.4f, 1.1f, 0xE08080)   // floppy ear
        p.set(3, (by - 2f).toInt(), 0xE69090); p.set(2, (by - 3f).toInt(), 0xE69090); p.set(3, (by - 4f).toInt(), 0xE69090)  // curly tail
    }

    private fun deer(p: Px, f: Int, v: Int) {
        quad(p, f, Q(0xB27C4A, 0xEBD9B8, 0xB27C4A, 0xD8B890, 0x7A5030, 0x2A1E14, 0xEBD9B8, rx = 5.8f, ry = 3.3f, legLen = 9f, headR = 2.2f, neckUp = 1.2f))
        val by = 12.3f - (if (f == 1 || f == 3) 0.6f else 0f)
        for (i in 0 until 5) p.set(7 + i * 2, (by - 2.2f + (i % 2)).toInt(), 0xF2E6C8)   // spots
        val hy = by - 4.8f
        p.line(19f, hy - 2f, 18.4f, hy - 6.4f, 0x6A4A2A, 1); p.line(18.6f, hy - 4.4f, 16.6f, hy - 5.8f, 0x6A4A2A, 1); p.line(18.8f, hy - 5.6f, 20.4f, hy - 7.4f, 0x6A4A2A, 1)
        p.line(21f, hy - 2f, 21.8f, hy - 6f, 0x6A4A2A, 1); p.line(21.4f, hy - 4.2f, 23f, hy - 5.6f, 0x6A4A2A, 1)
    }

    private fun goat(p: Px, f: Int, v: Int) {
        quad(p, f, Q(0xEAE6DA, 0xD8D2C0, 0xEAE6DA, 0xCFC8B4, 0x8A8678, 0x2A2A24, 0xEAE6DA, rx = 5.6f, ry = 3.4f, legLen = 7f, headR = 2.2f, neckUp = 0.6f))
        val by = 12.3f - (if (f == 1 || f == 3) 0.6f else 0f)
        val hy = by - 4.2f
        p.line(18.4f, hy - 2f, 17f, hy - 4.6f, 0xB0A890, 1); p.line(20.4f, hy - 2f, 19.4f, hy - 4.8f, 0xB0A890, 1)
        p.line(21.2f, hy + 2.2f, 21f, hy + 4.4f, 0xF4F0E4, 1)   // beard
        p.set(4, (by - 2.4f).toInt(), 0xEAE6DA)
    }

    private fun dog(p: Px, f: Int, v: Int) {
        val coats = intArrayOf(0xB88A5A, 0x8A8A92, 0xE0C890, 0x3A2A22)
        val cc = coats[v and 3]
        quad(p, f, Q(cc, lighten(cc, 1.25f), cc, lighten(cc, 1.25f), lighten(cc, 0.8f), 0x2A1E14, cc, rx = 5.8f, ry = 3.3f, legLen = 7f, headR = 2.3f))
        val by = 12.3f - (if (f == 1 || f == 3) 0.6f else 0f)
        p.ellipse(18.4f, by - 5.6f, 1.2f, 2.0f, lighten(cc, 0.6f))   // floppy ear
        p.rect(16, (by - 3.4f).toInt(), 3, 1, 0xC83030)               // collar
        p.line(4f, by - 1.2f, 2.4f, by - 4f + (if (f % 2 == 0) 0f else 1.4f), cc, 2)   // wagging tail
    }

    private fun cat(p: Px, f: Int, v: Int) {
        val coats = intArrayOf(0xD89A52, 0x6A6A72, 0x2E2A2A, 0xEADCC8)
        val cc = coats[v and 3]
        quad(p, f, Q(cc, lighten(cc, 1.3f), cc, lighten(cc, 1.3f), lighten(cc, 0.85f), lighten(cc, 0.6f), cc, rx = 5.4f, ry = 3.0f, legLen = 7f, headR = 2.3f))
        val by = 12.3f - (if (f == 1 || f == 3) 0.6f else 0f)
        pointedEars(p, Q(cc, cc, cc, cc, cc, cc, cc, neckUp = 0f), f, lighten(cc, 0.8f))
        p.line(4f, by - 1f, 2f, by - 5f, cc, 2); p.set(2, (by - 6f).toInt(), lighten(cc, 0.8f))   // long tail
        for (i in 0 until 3) p.set(8 + i * 2, (by - 2.6f).toInt(), lighten(cc, 0.7f))                  // stripes
        p.set(22, (by - 3.4f).toInt(), 0xE8E0D0)    // whiskers
    }

    private fun fox(p: Px, f: Int, v: Int) {
        quad(p, f, Q(0xE8742A, 0xFAF0DE, 0xE8742A, 0xFAF0DE, 0x2E2018, 0x1E140E, 0xE8742A, rx = 5.6f, ry = 3.2f, legLen = 7f, headR = 2.3f))
        val by = 12.3f - (if (f == 1 || f == 3) 0.6f else 0f)
        pointedEars(p, Q(0, 0, 0, 0, 0, 0, 0), f, 0xE8742A)
        p.ellipse(2.4f, by - 1.2f, 3.0f, 2.0f, 0xE8742A)       // bushy tail
        p.ellipse(0.8f, by - 1.0f, 1.4f, 1.2f, 0xFFFFFF)       // white tip
    }

    private fun wolf(p: Px, f: Int, v: Int) {
        val coats = intArrayOf(0x8E8E98, 0x5A5A62, 0xB8B8C0)
        val cc = coats[v % 3]
        quad(p, f, Q(cc, lighten(cc, 1.3f), cc, lighten(cc, 1.25f), lighten(cc, 0.8f), 0x1E1E22, lighten(cc, 0.85f), rx = 6.4f, ry = 3.5f, legLen = 7f, headR = 2.5f))
        val by = 12.3f - (if (f == 1 || f == 3) 0.6f else 0f)
        pointedEars(p, Q(0, 0, 0, 0, 0, 0, 0), f, lighten(cc, 0.7f))
        p.ellipse(2.6f, by + 0.4f, 2.6f, 1.7f, lighten(cc, 0.85f))   // tail
    }

    private fun bear(p: Px, f: Int, v: Int) {
        val coats = intArrayOf(0x6B4A2E, 0x3A2E26, 0xB8946A)
        val cc = coats[v % 3]
        quad(p, f, Q(cc, lighten(cc, 0.85f), cc, lighten(cc, 1.35f), lighten(cc, 0.8f), 0x1E140E, cc, rx = 7.3f, ry = 5.0f, legLen = 6f, headR = 2.9f))
        val by = 12.3f - (if (f == 1 || f == 3) 0.6f else 0f)
        p.ellipse(18.4f, by - 6.9f, 1.3f, 1.3f, lighten(cc, 0.8f)); p.ellipse(21.0f, by - 6.7f, 1.3f, 1.3f, lighten(cc, 0.8f))   // round ears
    }

    // ------------------------------------------------------------------ other creatures
    private fun chicken(p: Px, f: Int, v: Int) {
        val feathers = intArrayOf(0xF5EAC8, 0xB8602E, 0x3A3036, 0xF2F2F2)
        val cc = feathers[v and 3]
        val bob = if (f % 2 == 1) 1 else 0
        val sw = swing(f, 0f)
        p.line(11f, 16f, 11f - sw * 1.5f, 22f, 0xE89A20, 1); p.line(14f, 16f, 14f + sw * 1.5f, 22f, 0xF0B030, 1)
        p.rect(9 - (sw * 1.5f).toInt(), 21, 4, 1, 0xE89A20); p.rect(13 + (sw * 1.5f).toInt(), 21, 4, 1, 0xF0B030)
        p.ellipse(12f, 13f - bob * 0.5f, 5.2f, 4.2f, cc)
        p.ellipse(8f, 11.5f - bob * 0.5f, 3f, 3.6f, lighten(cc, 0.9f)); p.ellipse(6.4f, 9.6f, 1.6f, 2.8f, lighten(cc, 0.78f))   // tail feathers
        p.ellipse(11.6f, 13.6f, 2.8f, 2.2f, lighten(cc, 0.82f))   // wing
        p.ellipse(16.5f, 8f - bob * 0.5f, 2.6f, 2.8f, cc)
        p.set(18, 7, 0x1A1210); p.set(19, 7, 0xFFFFFF)
        p.rect(19, 8, 3, 1, 0xF0A020); p.set(21, 9, 0xE89A20)   // beak
        p.set(16, 5, 0xE02828); p.set(17, 5, 0xE02828); p.set(16, 4, 0xC82020); p.set(17, 9, 0xE02828)   // comb and wattle
    }

    private fun rabbit(p: Px, f: Int, v: Int) {
        val coats = intArrayOf(0xD8C8B8, 0x8A7A6A, 0xF2F2F2, 0xB8946A)
        val cc = coats[v and 3]
        val hop = if (f == 1 || f == 2) 2 else 0
        p.ellipse(10f, 18.6f - hop, 3.2f, 2.4f, lighten(cc, 0.75f))   // haunch foot
        p.ellipse(15f, 20f - hop * 0.5f, 1.8f, 1.2f, lighten(cc, 0.8f))
        p.ellipse(11f, 15f - hop, 5.6f, 4.0f, cc)
        p.ellipse(7f, 15.2f - hop, 2.8f, 2.9f, lighten(cc, 1.05f))
        p.ellipse(5.2f, 14.6f - hop, 1.6f, 1.6f, 0xFFFFFF)   // cotton tail
        p.ellipse(16.4f, 11.4f - hop, 3.0f, 2.7f, cc)
        p.line(15.2f, 9.5f - hop, 14.4f, 2.5f - hop, cc, 2); p.line(17.4f, 9.4f - hop, 18.4f, 2.8f - hop, cc, 2)   // long ears
        p.line(15.6f, 8f - hop, 15f, 3.5f - hop, 0xE8A0A0, 1)
        p.set(18, 10 - hop, 0x1A1210); p.set(19, 10 - hop, 0xFFFFFF); p.set(19, 12 - hop, 0xE08888)
    }

    private fun crab(p: Px, f: Int, v: Int) {
        val shell = intArrayOf(0xE05A3A, 0xC84A2E, 0xE87A4A)
        val cc = shell[v % 3]
        val sw = swing(f, 0f)
        for (i in 0 until 3) {
            val lx = 6f + i * 2.4f
            p.line(lx, 15f, lx - 3f - sw, 20.5f, lighten(cc, 0.7f), 1)
            p.line(23f - lx, 15f, 23f - lx + 3f + sw, 20.5f, lighten(cc, 0.7f), 1)
        }
        p.ellipse(12f, 14f, 6.6f, 4.0f, cc)
        p.ellipse(12f, 15f, 4.2f, 2.0f, lighten(cc, 1.2f))
        p.line(6f, 12f, 3f, 7f + sw, cc, 2); p.line(18f, 12f, 21f, 7f - sw, cc, 2)
        p.ellipse(3f, 6f + sw, 2.6f, 2.2f, lighten(cc, 1.1f)); p.ellipse(21f, 6f - sw, 2.6f, 2.2f, lighten(cc, 1.1f))
        p.set(3, 5 + sw.toInt(), OUT); p.set(21, 5 - sw.toInt(), OUT)   // claw gaps
        p.line(9.5f, 10.5f, 9f, 8f, lighten(cc, 0.9f), 1); p.line(14.5f, 10.5f, 15f, 8f, lighten(cc, 0.9f), 1)
        p.set(9, 7, 0xFFFFFF); p.set(15, 7, 0xFFFFFF); p.set(9, 8, 0x1A1210); p.set(15, 8, 0x1A1210)   // eye stalks
    }

    private fun dragon(p: Px, f: Int, v: Int) {
        val flap = swing(f, 0f)
        val skin = 0xC0392B
        // far wing
        p.line(9f, 8f, 5f, 2f - flap * 3f, 0x6A1E18, 2); p.line(9f, 8f, 12f, 1f - flap * 3f, 0x6A1E18, 2)
        p.ellipse(8.5f, 5f - flap * 2f, 4.4f, 3.2f, 0x7A241C)
        // tail
        p.line(5f, 15f, 1f, 18f + flap, skin, 3); p.line(1f, 18f + flap, 0f, 16f, 0xA82E22, 2)
        // legs
        val sw = swing(f, 0.25f)
        p.line(9f, 16f, 9f + sw * 2f, 22f, 0x8A2A20, 2); p.line(15f, 16f, 15f - sw * 2f, 22f, 0x9A3024, 2)
        p.rect(8 + (sw * 2f).toInt(), 21, 4, 2, 0x2A1410); p.rect(14 - (sw * 2f).toInt(), 21, 4, 2, 0x2A1410)
        // body, belly plates
        p.ellipse(11.5f, 13f, 7f, 4.2f, skin)
        p.ellipse(12f, 15f, 5.5f, 1.8f, 0xE8B060)
        for (i in 0 until 4) p.set(7 + i * 3, 9, 0x8A2A20)                    // back spikes
        // neck + head
        p.line(16f, 11f, 19f, 7f, skin, 3)
        p.ellipse(20f, 6.2f, 3.2f, 2.6f, 0xD04A38)
        p.ellipse(22.2f, 7.2f, 1.6f, 1.3f, 0xE0705A)
        p.set(20, 5, 0xFFE070); p.set(21, 5, 0xFFC040)                       // eye
        p.set(18, 3, 0xEAE0C8); p.set(20, 3, 0xEAE0C8); p.set(17, 2, 0xEAE0C8)   // horns
        p.set(22, 8, 0xFFFFFF); p.set(21, 8, 0xFFFFFF)                       // teeth
        // near wing
        p.line(13f, 8f, 10f, 0f - flap * 3f, 0x9A2E24, 2); p.line(13f, 8f, 17f, 1f - flap * 3f, 0x9A2E24, 2)
        p.ellipse(13.4f, 4.6f - flap * 2f, 4.6f, 3.4f, 0xB03A2C)
    }

    // ------------------------------------------------------------------ public API
    /** facing right; frame 0..3 is the walk cycle; variant picks colours for sapients and some animals. */
    fun creature(sp: Species, frame: Int, faceRight: Boolean, variant: Int = 0): IntArray {
        val p = Px()
        val f = frame and 3
        when (sp.key) {
            "human" -> human(p, f, variant)
            "elf" -> elf(p, f, variant)
            "orc" -> orc(p, f, variant)
            "dwarf" -> dwarf(p, f, variant)
            "zombie" -> zombie(p, f, variant)
            "sheep" -> sheep(p, f, variant)
            "cow" -> cow(p, f, variant)
            "pig" -> pig(p, f, variant)
            "deer" -> deer(p, f, variant)
            "goat" -> goat(p, f, variant)
            "dog" -> dog(p, f, variant)
            "cat" -> cat(p, f, variant)
            "fox" -> fox(p, f, variant)
            "wolf" -> wolf(p, f, variant)
            "bear" -> bear(p, f, variant)
            "chicken" -> chicken(p, f, variant)
            "rabbit" -> rabbit(p, f, variant)
            "crab" -> crab(p, f, variant)
            "dragon" -> dragon(p, f, variant)
            else -> p.ellipse(12f, 13f, 6f, 6f, sp.color and 0xFFFFFF)
        }
        p.outline()
        return if (faceRight) p.a else p.flipped()
    }
}
