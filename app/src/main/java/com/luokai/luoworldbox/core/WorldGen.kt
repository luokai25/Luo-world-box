package com.luokai.luoworldbox.core

import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class Climate { TEMPERATE, FROZEN, DESERT, LUSH }

class WorldConfig(
    var sizeIndex: Int = 2,
    var randomShapes: Boolean = true,
    var noiseMain: Int = 5,
    var noiseDetails: Int = 5,
    var noiseBonus: Int = 5,
    var randomBiomes: Boolean = true,
    var mountainEdges: Boolean = false,
    var spawnVegetation: Boolean = true,
    var spawnResources: Boolean = true,
    var centerLake: Boolean = false,
    var centerLand: Boolean = true,
    var roundEdges: Boolean = true,
    var squareEdges: Boolean = false,
    var treeRing: Boolean = false,
    var lowGround: Boolean = false,
    var highGround: Boolean = false,
    var removeMountains: Boolean = false,
    var climate: Climate = Climate.TEMPERATE
) {
    fun copyConfig() = WorldConfig(
        sizeIndex, randomShapes, noiseMain, noiseDetails, noiseBonus, randomBiomes, mountainEdges,
        spawnVegetation, spawnResources, centerLake, centerLand, roundEdges, squareEdges, treeRing,
        lowGround, highGround, removeMountains, climate
    )

    companion object {
        val SIZES = intArrayOf(64, 96, 128, 192, 256, 384, 512)
        val SIZE_NAMES = arrayOf("Tiny", "Small", "Standard", "Large", "Huge", "Giant", "Colossal")
    }
}

class Template(val name: String, val cfg: WorldConfig)

object Templates {
    val ALL: List<Template> = listOf(
        Template("Continent", WorldConfig()),
        Template("Islands", WorldConfig(centerLand = false, noiseMain = 8, noiseDetails = 6, lowGround = true)),
        Template("Archipelago", WorldConfig(centerLand = false, noiseMain = 9, noiseDetails = 8, lowGround = true, roundEdges = false)),
        Template("Pangaea", WorldConfig(centerLand = true, noiseMain = 3, highGround = true, roundEdges = false, squareEdges = false)),
        Template("Atoll", WorldConfig(centerLand = false, centerLake = true, noiseMain = 4, treeRing = true)),
        Template("Highlands", WorldConfig(highGround = true, noiseMain = 6, noiseBonus = 8)),
        Template("Lowlands", WorldConfig(removeMountains = true, noiseMain = 4, noiseDetails = 3)),
        Template("Square Land", WorldConfig(roundEdges = false, squareEdges = true, centerLand = false, mountainEdges = true)),
        Template("Frozen North", WorldConfig(climate = Climate.FROZEN)),
        Template("Desert", WorldConfig(climate = Climate.DESERT, spawnVegetation = false)),
        Template("Jungle", WorldConfig(climate = Climate.LUSH, noiseDetails = 7)),
        Template("Open Sea", WorldConfig(centerLand = false, lowGround = true, noiseMain = 3, noiseDetails = 2, noiseBonus = 0))
    )
}

/** Smooth value noise. */
class Noise(private val seed: Long) {
    private fun hash(x: Int, y: Int): Float {
        var h = x * 374761393 + y * 668265263 + seed.toInt() * 1442695041 + (seed shr 32).toInt() * 747796405
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 16777215f
    }

    fun value(x: Float, y: Float): Float {
        val xi = Math.floor(x.toDouble()).toInt()
        val yi = Math.floor(y.toDouble()).toInt()
        val fx = x - xi
        val fy = y - yi
        val sx = fx * fx * (3 - 2 * fx)
        val sy = fy * fy * (3 - 2 * fy)
        val a = hash(xi, yi)
        val b = hash(xi + 1, yi)
        val c = hash(xi, yi + 1)
        val d = hash(xi + 1, yi + 1)
        val top = a + (b - a) * sx
        val bot = c + (d - c) * sx
        return top + (bot - top) * sy
    }

    fun fbm(x: Float, y: Float, octaves: Int): Float {
        var amp = 0.5f
        var freq = 1f
        var sum = 0f
        var norm = 0f
        for (i in 0 until octaves) {
            sum += value(x * freq, y * freq) * amp
            norm += amp
            amp *= 0.5f
            freq *= 2f
        }
        return sum / norm
    }
}

object WorldGen {
    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    fun generate(cfg: WorldConfig, seed: Long, progress: (String) -> Unit = {}): World {
        val size = WorldConfig.SIZES[cfg.sizeIndex.coerceIn(0, WorldConfig.SIZES.size - 1)]
        val w = size
        val h = size
        val world = World(w, h, seed)
        val rnd = Random(seed)
        val nMain = Noise(seed)
        val nDet = Noise(seed * 31 + 7)
        val nBonus = Noise(seed * 17 + 3)
        val nBio = Noise(seed + 99)
        val nVeg = Noise(seed + 1234)
        val nWarp = Noise(seed * 13 + 5)

        progress("Raising mountains...")
        val fm = 1.5f + cfg.noiseMain * 0.35f
        val ox = rnd.nextFloat() * 200f
        val oy = rnd.nextFloat() * 200f
        val elev = FloatArray(w * h)

        for (y in 0 until h) {
            for (x in 0 until w) {
                var u = x / w.toFloat()
                var v = y / h.toFloat()
                if (cfg.randomShapes) {
                    u += (nWarp.fbm(u * 3f + 40f, v * 3f, 2) - 0.5f) * 0.25f
                    v += (nWarp.fbm(u * 3f, v * 3f + 80f, 2) - 0.5f) * 0.25f
                }
                var e = nMain.fbm(u * fm + ox, v * fm + oy, 4)
                e = (e - 0.5f) * 2.1f + 0.56f
                e += (nDet.fbm(u * fm * 4f + ox, v * fm * 4f + oy, 2) - 0.5f) * cfg.noiseDetails * 0.05f
                e += (nBonus.value(u * 9f, v * 9f) - 0.5f) * cfg.noiseBonus * 0.04f

                val dx = (x / w.toFloat() - 0.5f) * 2f
                val dy = (y / h.toFloat() - 0.5f) * 2f
                val r = sqrt(dx * dx + dy * dy)
                val sq = max(abs(dx), abs(dy))
                if (cfg.roundEdges) e -= smooth(0.62f, 1.05f, r) * 0.62f
                if (cfg.squareEdges) e -= smooth(0.78f, 1.0f, sq) * 0.7f
                if (cfg.centerLand) e += (1f - smooth(0f, 0.6f, r)) * 0.22f
                if (cfg.centerLake) e -= (1f - smooth(0f, 0.28f, r)) * 0.6f
                if (cfg.lowGround) e -= 0.10f
                if (cfg.highGround) e += 0.12f
                if (cfg.removeMountains) e = min(e, 0.70f)
                if (cfg.mountainEdges && sq > 0.93f) e = 0.9f
                elev[y * w + x] = e
            }
        }

        progress("Filling oceans...")
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val e = elev[i]
                val u = x / w.toFloat()
                val v = y / h.toFloat()
                val dx = (u - 0.5f) * 2f
                val dy = (v - 0.5f) * 2f
                val r = sqrt(dx * dx + dy * dy)
                val moisture = if (cfg.randomBiomes) nBio.fbm(u * 4f + 10f, v * 4f + 10f, 3) else 0.5f
                var temp = 0.62f + (nBio.fbm(u * 2f + 70f, v * 2f + 70f, 2) - 0.5f) * 0.4f - (e - 0.45f) * 0.6f
                val polar = min(v, 1f - v)
                if (polar < 0.07f) temp -= (0.07f - polar) * 6f
                when (cfg.climate) {
                    Climate.FROZEN -> temp -= 0.45f
                    Climate.DESERT -> temp += 0.1f
                    else -> {}
                }

                val t: Int = when {
                    e < 0.30f -> Tile.DEEP
                    e < 0.40f -> Tile.SHALLOW
                    e < 0.44f -> Tile.SAND
                    e < 0.76f -> {
                        if (temp < 0.22f) Tile.SNOW
                        else if (cfg.climate == Climate.DESERT && moisture < 0.62f) Tile.SAND
                        else if (cfg.randomBiomes && moisture < 0.38f) Tile.SOIL
                        else if (cfg.randomBiomes && moisture < 0.43f && cfg.climate == Climate.TEMPERATE) Tile.SAND
                        else Tile.GRASS
                    }
                    e < 0.85f -> if (temp < 0.18f) Tile.SNOW else Tile.HILLS
                    e < 0.97f -> Tile.MOUNTAIN
                    else -> Tile.SNOW
                }
                world.tiles[i] = t.toByte()
                if (cfg.treeRing && (t == Tile.GRASS || t == Tile.SOIL) && r in 0.62f..0.72f) {
                    world.tiles[i] = Tile.FOREST.toByte()
                }
            }
        }

        progress("Planting trees...")
        if (cfg.spawnVegetation) {
            val thr = when (cfg.climate) {
                Climate.LUSH -> 0.38f
                Climate.FROZEN -> 0.62f
                else -> 0.52f
            }
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    if (world.tiles[i].toInt() == Tile.GRASS) {
                        val vv = nVeg.fbm(x / w.toFloat() * 7f, y / h.toFloat() * 7f, 3)
                        if (vv > thr && rnd.nextFloat() < 0.85f) world.tiles[i] = Tile.FOREST.toByte()
                        else if (rnd.nextFloat() < 0.01f) world.tiles[i] = Tile.FOREST.toByte()
                    }
                }
            }
        }

        progress("Digging for ore...")
        if (cfg.spawnResources) {
            for (i in 0 until w * h) {
                val t = world.tiles[i].toInt()
                if ((t == Tile.MOUNTAIN || t == Tile.HILLS) && rnd.nextFloat() < 0.05f) world.ore[i] = 1
            }
        }
        world.markAllDirty()
        progress("Splitting atoms...")
        return world
    }
}
