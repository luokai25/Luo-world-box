package com.luokai.luoworldbox.core

/** Procedural 16x16 pixel-art sprites. Pure Kotlin: every pixel is ARGB, 0 = transparent. */
object SpriteGen {
    const val N = 16

    private fun c(rgb: Int): Int = rgb or (0xFF shl 24)

    private class Px {
        val a = IntArray(N * N)
        fun set(x: Int, y: Int, col: Int) { if (x in 0 until N && y in 0 until N) a[y * N + x] = c(col) }
        fun rect(x: Int, y: Int, w: Int, h: Int, col: Int) { for (yy in y until y + h) for (xx in x until x + w) set(xx, yy, col) }
        fun flipped(): IntArray {
            val o = IntArray(N * N)
            for (y in 0 until N) for (x in 0 until N) o[y * N + (N - 1 - x)] = a[y * N + x]
            return o
        }
        /** adds a 1px dark outline around opaque pixels (drawn into transparent neighbours) */
        fun outline(col: Int) {
            val src = a.copyOf()
            for (y in 0 until N) for (x in 0 until N) {
                if (src[y * N + x] != 0) continue
                var near = false
                if (x > 0 && src[y * N + x - 1] != 0) near = true
                if (x < N - 1 && src[y * N + x + 1] != 0) near = true
                if (y > 0 && src[(y - 1) * N + x] != 0) near = true
                if (y < N - 1 && src[(y + 1) * N + x] != 0) near = true
                if (near) a[y * N + x] = c(col)
            }
        }
    }

    private const val DARK = 0x201814

    private fun humanoid(p: Px, f: Int, skin: Int, hair: Int, tunic: Int, pants: Int, wide: Boolean = false, short: Boolean = false) {
        val top = if (short) 4 else 2
        p.rect(6, top, 4, 4, skin)
        p.rect(6, top - 1, 4, 2, hair)
        p.set(9, top + 1, DARK)
        val by = top + 4
        if (wide) p.rect(4, by, 8, 5, tunic) else p.rect(5, by, 6, 5, tunic)
        val ax = if (wide) 3 else 4
        p.rect(ax, by, 1, 4, skin); p.rect(if (wide) 12 else 11, by, 1, 4, skin)
        val ly = by + 5
        if (f == 0) { p.rect(6, ly, 2, 15 - ly, pants); p.rect(8, ly, 2, 15 - ly, pants) }
        else { p.rect(5, ly, 2, 15 - ly, pants); p.rect(9, ly, 2, 15 - ly - 1, pants) }
        p.rect(if (f == 0) 6 else 5, 15, 2, 1, DARK); p.rect(if (f == 0) 8 else 9, 15, 2, 1, DARK)
    }

    private fun quad(p: Px, f: Int, body: Int, belly: Int, head: Int, legs: Int, tail: Int, big: Boolean = false) {
        val y0 = if (big) 5 else 6
        p.rect(3, y0, 9, if (big) 6 else 5, body)
        p.rect(4, y0 + (if (big) 4 else 3), 7, 2, belly)
        p.rect(11, y0 - 2, 4, 4, head)
        p.set(13, y0 - 1, DARK); p.set(14, y0, DARK)
        val ly = y0 + (if (big) 6 else 5)
        if (f == 0) { p.rect(4, ly, 1, 15 - ly, legs); p.rect(6, ly, 1, 14 - ly, legs); p.rect(9, ly, 1, 14 - ly, legs); p.rect(11, ly, 1, 15 - ly, legs) }
        else { p.rect(4, ly, 1, 14 - ly, legs); p.rect(6, ly, 1, 15 - ly, legs); p.rect(9, ly, 1, 15 - ly, legs); p.rect(11, ly, 1, 14 - ly, legs) }
        p.rect(2, y0, 1, 2, tail)
    }

    /** facing right. frame 0/1 = walk cycle. */
    fun creature(sp: Species, frame: Int, faceRight: Boolean): IntArray {
        val p = Px()
        val f = frame and 1
        when (sp.key) {
            "human" -> humanoid(p, f, 0xE8B88A, 0x5A3A1E, 0x3B6FD6, 0x4A3A2A)
            "elf" -> { humanoid(p, f, 0xF2D2B0, 0xE8D890, 0x3FA34D, 0x6A4A2A); p.set(5, 3, 0xF2D2B0); p.set(10, 3, 0xF2D2B0); p.set(5, 2, 0xF2D2B0) }
            "orc" -> { humanoid(p, f, 0x6FA84A, 0x2A2A2A, 0x7A4A2A, 0x3A2A1A, wide = true); p.set(8, 5, 0xFFFFFF); p.set(9, 5, 0xFFFFFF) }
            "dwarf" -> { humanoid(p, f, 0xE0A878, 0x909090, 0x8A5A3A, 0x4A3A2A, wide = true, short = true); p.rect(6, 7, 4, 3, 0xD98A2B); p.rect(6, 2, 4, 1, 0xB0B0B0) }
            "zombie" -> { humanoid(p, f, 0x7FA06A, 0x3A4A3A, 0x5A5A4A, 0x3A3A4A); p.rect(11, 6, 3, 1, 0x7FA06A); p.set(9, 4, 0xD03030) }
            "sheep" -> { quad(p, f, 0xF4F4F4, 0xE0E0E0, 0x3A3A3A, 0x3A3A3A, 0xF4F4F4, big = true); p.rect(2, 5, 11, 1, 0xFFFFFF); p.rect(3, 4, 4, 1, 0xFFFFFF) }
            "cow" -> { quad(p, f, 0xF0E8DC, 0xE8D0C0, 0xF0E8DC, 0x3A3A3A, 0x3A3A3A, big = true); p.rect(4, 6, 3, 3, 0x2A2A2A); p.rect(9, 8, 2, 2, 0x2A2A2A); p.set(12, 3, 0xDDDDDD); p.set(14, 3, 0xDDDDDD) }
            "pig" -> { quad(p, f, 0xF0A8A8, 0xE89090, 0xF0A8A8, 0xE08080, 0xE08080); p.rect(14, 6, 1, 2, 0xD87070); p.set(12, 3, 0xE89090) }
            "dog" -> { quad(p, f, 0xB88A5A, 0xD8B888, 0xB88A5A, 0x8A6A3A, 0xB88A5A); p.set(12, 3, 0x6A4A2A); p.set(14, 3, 0x6A4A2A) }
            "cat" -> { quad(p, f, 0xD89A52, 0xF0C890, 0xD89A52, 0xB07A3A, 0xD89A52); p.set(12, 3, 0xD89A52); p.set(14, 3, 0xD89A52); p.rect(1, 4, 1, 3, 0xD89A52) }
            "fox" -> { quad(p, f, 0xE8742A, 0xF8E8D0, 0xE8742A, 0x3A2A1A, 0xF8F0E0); p.set(12, 3, 0xE8742A); p.set(14, 3, 0xE8742A); p.rect(1, 6, 2, 2, 0xF8F0E0) }
            "wolf" -> { quad(p, f, 0x8E8E96, 0xB8B8C0, 0x8E8E96, 0x5A5A62, 0x6E6E76); p.set(12, 3, 0x6E6E76); p.set(14, 3, 0x6E6E76) }
            "bear" -> { quad(p, f, 0x6B4A2E, 0x5A3A22, 0x6B4A2E, 0x4A2E1A, 0x6B4A2E, big = true); p.set(12, 2, 0x6B4A2E); p.set(14, 2, 0x6B4A2E); p.set(14, 5, 0xD0A070) }
            "deer" -> { quad(p, f, 0xB07A4A, 0xE8D0A8, 0xB07A4A, 0x7A5030, 0xE8D0A8); p.rect(12, 1, 1, 2, 0x6A4A2A); p.rect(14, 1, 1, 2, 0x6A4A2A); p.set(11, 0, 0x6A4A2A); p.set(15, 0, 0x6A4A2A) }
            "goat" -> { quad(p, f, 0xE8E4D8, 0xF8F4E8, 0xE8E4D8, 0x7A7A70, 0xE8E4D8); p.set(12, 3, 0xA09A80); p.set(14, 3, 0xA09A80); p.rect(14, 7, 1, 2, 0xD8D0B8) }
            "chicken" -> {
                p.rect(5, 7, 6, 5, 0xF5EAC0); p.rect(10, 4, 3, 4, 0xF5EAC0); p.set(13, 5, 0xE89A20); p.set(11, 3, 0xD03030); p.set(12, 5, DARK)
                p.rect(4, 6, 2, 3, 0xE0D090)
                if (f == 0) { p.rect(7, 12, 1, 3, 0xE89A20); p.rect(9, 12, 1, 3, 0xE89A20) } else { p.rect(6, 12, 1, 3, 0xE89A20); p.rect(10, 12, 1, 2, 0xE89A20) }
            }
            "rabbit" -> {
                p.rect(5, 8, 6, 4, 0xD8C8B8); p.rect(10, 6, 3, 3, 0xD8C8B8); p.rect(10, 2, 1, 4, 0xD8C8B8); p.rect(12, 2, 1, 4, 0xD8C8B8)
                p.set(12, 7, DARK); p.set(4, 9, 0xFFFFFF)
                if (f == 0) { p.rect(6, 12, 2, 2, 0xB8A898); p.rect(9, 12, 2, 2, 0xB8A898) } else { p.rect(5, 12, 2, 2, 0xB8A898); p.rect(10, 12, 2, 2, 0xB8A898) }
            }
            "crab" -> {
                p.rect(4, 8, 8, 4, 0xE05A3A); p.rect(2, 5, 3, 3, 0xE05A3A); p.rect(11, 5, 3, 3, 0xE05A3A); p.set(6, 7, 0xFFFFFF); p.set(9, 7, 0xFFFFFF)
                p.set(6, 6, DARK); p.set(9, 6, DARK)
                val o = if (f == 0) 0 else 1
                p.rect(3 + o, 12, 1, 2, 0xB03A20); p.rect(6 - o, 12, 1, 2, 0xB03A20); p.rect(9 + o, 12, 1, 2, 0xB03A20); p.rect(12 - o, 12, 1, 2, 0xB03A20)
            }
            "dragon" -> {
                val wing = if (f == 0) 1 else 3
                p.rect(4, wing, 5, 5, 0x8A2A22); p.rect(3, wing + 2, 2, 3, 0x8A2A22)
                p.rect(2, 6, 10, 5, 0xC0392B); p.rect(11, 3, 4, 4, 0xC0392B); p.rect(14, 5, 2, 2, 0xE05A3A)
                p.set(13, 4, 0xFFE070); p.set(12, 2, 0xE8D8C0); p.set(14, 2, 0xE8D8C0)
                p.rect(0, 8, 3, 2, 0xC0392B); p.rect(3, 9, 8, 2, 0xE8A050)
                p.rect(4, 11, 2, 3, 0x8A2A22); p.rect(9, 11, 2, 3, 0x8A2A22)
            }
            else -> p.rect(4, 4, 8, 8, sp.color)
        }
        p.outline(DARK)
        return if (faceRight) p.a else p.flipped()
    }

    /** kind 0 = pine, 1 = oak, 2 = bush */
    fun tree(kind: Int): IntArray {
        val p = Px()
        when (kind) {
            0 -> {
                p.rect(7, 11, 2, 4, 0x5B3A1E)
                p.rect(4, 9, 8, 3, 0x1F5A14); p.rect(5, 6, 6, 3, 0x2A6E1C); p.rect(6, 3, 4, 3, 0x368A24); p.rect(7, 1, 2, 2, 0x47A02E)
                p.set(6, 10, 0x2E7420); p.set(8, 7, 0x3A9A28)
            }
            1 -> {
                p.rect(7, 9, 2, 6, 0x5B3A1E)
                p.rect(3, 3, 10, 7, 0x2A6E1C); p.rect(4, 2, 8, 1, 0x2A6E1C); p.rect(5, 1, 6, 1, 0x368A24)
                p.rect(4, 3, 3, 2, 0x47A02E); p.rect(8, 4, 3, 2, 0x3A9A28); p.set(6, 7, 0x1F5A14); p.set(10, 8, 0x1F5A14)
            }
            else -> {
                p.rect(4, 9, 8, 5, 0x2A6E1C); p.rect(5, 8, 6, 1, 0x368A24); p.rect(5, 10, 2, 2, 0x47A02E); p.rect(9, 11, 2, 2, 0x3A9A28)
                p.set(6, 9, 0xD04050); p.set(10, 10, 0xD04050)
            }
        }
        p.outline(0x103008)
        return p.a
    }

    /** tier 0 hut, 1 house, 2 big house. roof is the kingdom colour. */
    fun house(tier: Int, roof: Int): IntArray {
        val p = Px()
        val wall = 0xE8D2A0
        val dark = scaleRgb(roof and 0xFFFFFF, 0.7f)
        when (tier) {
            0 -> {
                p.rect(4, 9, 8, 6, wall); p.rect(3, 6, 10, 3, roof); p.rect(5, 4, 6, 2, roof); p.rect(3, 8, 10, 1, dark)
                p.rect(7, 11, 2, 4, 0x5A3A1E)
            }
            1 -> {
                p.rect(2, 8, 12, 7, wall); p.rect(1, 5, 14, 3, roof); p.rect(3, 3, 10, 2, roof); p.rect(1, 7, 14, 1, dark)
                p.rect(11, 1, 2, 3, 0x8A6A5A); p.rect(7, 11, 2, 4, 0x5A3A1E); p.rect(3, 10, 2, 2, 0x7AB8E0); p.rect(11, 10, 2, 2, 0x7AB8E0)
            }
            else -> {
                p.rect(1, 6, 14, 9, wall); p.rect(0, 3, 16, 3, roof); p.rect(2, 1, 12, 2, roof); p.rect(0, 5, 16, 1, dark)
                p.rect(7, 11, 2, 4, 0x5A3A1E); p.rect(2, 8, 2, 2, 0x7AB8E0); p.rect(12, 8, 2, 2, 0x7AB8E0); p.rect(5, 8, 2, 2, 0x7AB8E0); p.rect(9, 8, 2, 2, 0x7AB8E0)
                p.rect(2, 12, 2, 2, 0x7AB8E0); p.rect(12, 12, 2, 2, 0x7AB8E0); p.rect(7, 0, 2, 2, 0xE8E8E8)
            }
        }
        p.outline(DARK)
        return p.a
    }

    /** House under construction; progress 0..6 raises the walls. */
    fun site(progress: Int): IntArray {
        val p = Px()
        p.rect(2, 13, 12, 2, 0x9A9A8E)
        p.rect(2, 5, 1, 9, 0x7A5A32); p.rect(13, 5, 1, 9, 0x7A5A32)
        val h = (progress.coerceIn(0, 6) * 1.5f).toInt()
        if (h > 0) p.rect(3, 13 - h, 10, h, 0xC8A870)
        if (progress >= 3) p.rect(2, 4, 12, 1, 0x7A5A32)
        if (progress >= 5) p.rect(3, 3, 10, 1, 0xB8402F)
        p.outline(DARK)
        return p.a
    }

    private fun scaleRgb(rgb: Int, f: Float): Int {
        val r = ((rgb shr 16 and 255) * f).toInt()
        val g = ((rgb shr 8 and 255) * f).toInt()
        val b = ((rgb and 255) * f).toInt()
        return (r shl 16) or (g shl 8) or b
    }
}
