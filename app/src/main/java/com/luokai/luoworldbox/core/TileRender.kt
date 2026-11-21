package com.luokai.luoworldbox.core

/** Pure-Kotlin tile colouring (no Android imports) so it can be previewed and tested headless. */
object TileRender {
    private fun rank(t: Int): Int = when (t) {
        Tile.DEEP -> 0
        Tile.SHALLOW -> 1
        Tile.SAND -> 2
        Tile.HILLS -> 4
        Tile.MOUNTAIN, Tile.SNOW -> 5
        else -> 3
    }

    private fun rankAt(w: World, x: Int, y: Int): Int {
        val cx = x.coerceIn(0, w.w - 1)
        val cy = y.coerceIn(0, w.h - 1)
        return rank(w.tiles[cy * w.w + cx].toInt())
    }

    fun hash(x: Int, y: Int): Int = (((x * 73856093) xor (y * 19349663)) * 2654435761L.toInt() ushr 8) and 0xFF

    fun scale(c: Int, f: Float): Int {
        val r = minOf(255, maxOf(0, ((c shr 16 and 255) * f).toInt()))
        val g = minOf(255, maxOf(0, ((c shr 8 and 255) * f).toInt()))
        val b = minOf(255, maxOf(0, ((c and 255) * f).toInt()))
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun mix(a: Int, b: Int, t: Float): Int {
        val r = ((a shr 16 and 255) * (1 - t) + (b shr 16 and 255) * t).toInt()
        val g = ((a shr 8 and 255) * (1 - t) + (b shr 8 and 255) * t).toInt()
        val bl = ((a and 255) * (1 - t) + (b and 255) * t).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    fun color(w: World, i: Int, x: Int, y: Int, frame: Int = 0): Int {
        val t = w.tiles[i].toInt()
        val hsh = hash(x, y)
        var c = Tile.COLORS[t]
        var f = 0.95f + hsh / 255f * 0.10f

        when (t) {
            Tile.GRASS -> if (hsh < 22) f *= 1.12f else if (hsh > 235) f *= 0.88f
            Tile.FOREST -> f *= if (hsh and 1 == 0) 0.92f else 1.06f
            Tile.FIELD -> c = if (y and 1 == 0) 0xFFC9B24A.toInt() else 0xFFA8923A.toInt()
            Tile.LAVA -> f = 0.85f + ((hsh + frame * 5) and 63) / 63f * 0.5f
            Tile.DEEP -> {
                val calm = rankAt(w, x - 1, y) == 0 && rankAt(w, x + 1, y) == 0 && rankAt(w, x, y - 1) == 0 && rankAt(w, x, y + 1) == 0
                if (calm) f *= 0.9f
                if (hsh > 246) f *= 1.25f
            }
        }

        val r = rank(t)
        if (r >= 2) {
            // light from the north-west: slopes rising to the south-east face the light
            val d = rankAt(w, x + 1, y + 1) - rankAt(w, x - 1, y - 1)
            f += (d * 0.10f).coerceIn(-0.22f, 0.22f)
        }
        if (t == Tile.SHALLOW) {
            val shore = rankAt(w, x - 1, y) >= 2 || rankAt(w, x + 1, y) >= 2 || rankAt(w, x, y - 1) >= 2 || rankAt(w, x, y + 1) >= 2
            if (shore) c = mix(c, 0xFFFFFFFF.toInt(), 0.38f)
            else if (rankAt(w, x - 1, y) == 0 || rankAt(w, x, y - 1) == 0) f *= 0.93f
        }
        c = scale(c, f)

        val fire = w.fire[i].toInt() and 0xFF
        if (fire != 0) {
            c = if (((hsh + frame) and 3) < 2) 0xFFFF8A1F.toInt() else 0xFFE03A0A.toInt()
        } else if (w.ore[i].toInt() != 0 && (t == Tile.MOUNTAIN || t == Tile.HILLS)) {
            c = scale(c, 1.5f)
        }
        return c
    }
}
