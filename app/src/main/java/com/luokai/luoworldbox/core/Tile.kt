package com.luokai.luoworldbox.core

/** Terrain tile ids. Pure Kotlin so the simulation can be tested without Android. */
object Tile {
    const val DEEP = 0
    const val SHALLOW = 1
    const val SAND = 2
    const val SOIL = 3
    const val GRASS = 4
    const val FOREST = 5
    const val HILLS = 6
    const val MOUNTAIN = 7
    const val SNOW = 8
    const val LAVA = 9
    const val WASTE = 10

    private fun c(rgb: Int): Int = rgb or (0xFF shl 24)

    val COLORS = intArrayOf(
        c(0x1B4A9E), // deep
        c(0x3A86D6), // shallow
        c(0xE0C878), // sand
        c(0x8B5E34), // soil
        c(0x4C9A2A), // grass
        c(0x2B6B1C), // forest
        c(0x7C7B64), // hills
        c(0x55575C), // mountain
        c(0xF0F4F8), // snow
        c(0xE8480C), // lava
        c(0x4A4038)  // wasteland (burnt)
    )

    fun isWater(t: Int) = t == DEEP || t == SHALLOW
    fun isFlammable(t: Int) = t == GRASS || t == FOREST
}
