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
