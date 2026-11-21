package com.luokai.luoworldbox

import android.graphics.Bitmap
import com.luokai.luoworldbox.core.Species
import com.luokai.luoworldbox.core.SpriteGen

/** Lazily builds and caches Bitmaps from the procedural pixel sprites. Used from the game thread only. */
class SpriteBank {
    private val creatures = HashMap<Int, Bitmap>()
    private val houses = HashMap<Int, Bitmap>()
    private val trees = arrayOfNulls<Bitmap>(3)
    private val sites = arrayOfNulls<Bitmap>(7)

    private fun make(px: IntArray): Bitmap =
        Bitmap.createBitmap(px, SpriteGen.N, SpriteGen.N, Bitmap.Config.ARGB_8888)

    fun creature(sp: Species, frame: Int, faceRight: Boolean): Bitmap {
        val key = sp.id * 4 + (frame and 1) * 2 + (if (faceRight) 1 else 0)
        return creatures.getOrPut(key) { make(SpriteGen.creature(sp, frame and 1, faceRight)) }
    }

    fun tree(kind: Int): Bitmap {
        val k = kind.coerceIn(0, 2)
        var b = trees[k]
        if (b == null) { b = make(SpriteGen.tree(k)); trees[k] = b }
        return b
    }

    fun site(progress: Int): Bitmap {
        val k = progress.coerceIn(0, 6)
        var b = sites[k]
        if (b == null) { b = make(SpriteGen.site(k)); sites[k] = b }
        return b
    }

    fun house(tier: Int, roof: Int): Bitmap {
        val key = tier * 31 + roof
        return houses.getOrPut(key) { make(SpriteGen.house(tier, roof)) }
    }
}
