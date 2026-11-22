package com.luokai.luoworldbox.core

import java.io.Serializable
import java.util.Random
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

const val YEAR_SEC = 12f
const val MONTH_SEC = 1f
const val MOONS_PER_AGE = 600
const val STEP = 0.25f

const val DAY_SEC = 60f
private const val CELL = 8

private val MONTHS = arrayOf(
    "Monolith", "Musch", "Greguary", "Thulmin", "Orvel", "Sunreach",
    "Ashfall", "Harvend", "Duskmoon", "Frostwane", "Emberly", "Yulend"
)

private val KINGDOM_COLORS = intArrayOf(
    0xFF3B82F6.toInt(), 0xFFEF4444.toInt(), 0xFF22C55E.toInt(), 0xFFEAB308.toInt(),
    0xFFA855F7.toInt(), 0xFF06B6D4.toInt(), 0xFFF97316.toInt(), 0xFFEC4899.toInt(),
    0xFF84CC16.toInt(), 0xFF14B8A6.toInt(), 0xFFF43F5E.toInt(), 0xFF8B5CF6.toInt()
)

class World(val w: Int, val h: Int, val seed: Long) : Serializable {
    val tiles = ByteArray(w * h)
    val fire = ByteArray(w * h)
    val ore = ByteArray(w * h)
    val bld = IntArray(w * h)            // village id + 1 when a house stands here

    val creatures = ArrayList<Creature>()
    val villages = ArrayList<Village>()
    val kingdoms = ArrayList<Kingdom>()
    val zones = ArrayList<Zone>()
    val wars = HashSet<Long>()
    val laws = Laws()
    val history = ArrayList<String>()
    val popHistory = ArrayList<Int>()
    private var lastAge = -1
    private var accPop = 0f
    @Transient private var toastList: ArrayList<String>? = null
    @Transient private var soundList: ArrayList<String>? = null
    private val burning = ArrayList<Int>()
    private val rnd = Random(seed xor 0x5DEECE66DL)

    var time = 0.0
    var totalDeaths = 0
    private var nextCreatureId = 1
    private var nextVillageId = 1
    private var nextKingdomId = 1
    private var accSlow = 0f
    @Transient private var night = false
    private val maxSapients: Int get() = (w * h / 200).coerceIn(900, 4500)
    private val maxAnimals: Int get() = (w * h / 250).coerceIn(1000, 4000)
    private val maxMonsters: Int get() = 300
    private var accKingdom = 0f

    // renderer bookkeeping (not saved)
    @Transient var dirtyMin = 0
    @Transient var dirtyMax = -1
    @Transient private var grid: Array<ArrayList<Creature>>? = null
    @Transient private var fxList: ArrayList<Fx>? = null
    private var gw = 0
    private var gh = 0

    // ---------------------------------------------------------------- time
    val moon: Int get() = (time / MONTH_SEC).toInt()
    val year: Int get() = moon / 12 + 1
    val monthName: String get() = MONTHS[moon % 12]
    val ageIdx: Int get() = (moon / MOONS_PER_AGE) % WorldAges.ALL.size
    val age: AgeDef get() = WorldAges.ALL[ageIdx]
    val moonsLeft: Int get() = MOONS_PER_AGE - moon % MOONS_PER_AGE

    /** 0 = full daylight, 1 = deep night. A day lasts DAY_SEC seconds of simulated time. */
    fun nightLevel(): Float {
        val p = ((time % DAY_SEC) / DAY_SEC).toFloat()
        return when {
            p < 0.60f -> 0f
            p < 0.72f -> (p - 0.60f) / 0.12f
            p < 0.92f -> 1f
            else -> ((1f - p) / 0.08f).coerceIn(0f, 1f)
        }
    }

    fun skipToNextAge() {
        time = ((moon / MOONS_PER_AGE + 1) * MOONS_PER_AGE).toDouble() * MONTH_SEC
    }

    // ---------------------------------------------------------------- dirty tracking
    fun markAllDirty() { dirtyMin = 0; dirtyMax = h - 1 }
    private fun dirtyRow(y: Int) {
        if (dirtyMax < dirtyMin) { dirtyMin = y; dirtyMax = y } else {
            if (y < dirtyMin) dirtyMin = y
            if (y > dirtyMax) dirtyMax = y
        }
    }
    fun clearDirty() { dirtyMin = h; dirtyMax = -1 }

    fun burningTiles(): List<Int> = burning

    fun fxs(): ArrayList<Fx> {
        var f = fxList
        if (f == null) { f = ArrayList(); fxList = f }
        return f
    }

    /** Writes an event to the world history and queues an on-screen toast. */
    fun log(msg: String) {
        history.add("Year $year, $monthName: $msg")
        if (history.size > 300) history.removeAt(0)
        var t = toastList
        if (t == null) { t = ArrayList(); toastList = t }
        if (t.size < 12) t.add(msg)
    }

    fun sound(name: String) {
        var l = soundList
        if (l == null) { l = ArrayList(); soundList = l }
        if (l.size < 8) l.add(name)
    }

    fun drainSounds(): List<String> {
        val l = soundList ?: return emptyList()
        if (l.isEmpty()) return emptyList()
        val out = ArrayList(l.distinct())
        l.clear()
        return out
    }

    fun drainToasts(): List<String> {
        val t = toastList ?: return emptyList()
        if (t.isEmpty()) return emptyList()
        val out = ArrayList(t)
        t.clear()
        return out
    }

    fun tileAt(x: Int, y: Int): Int = tiles[y * w + x].toInt()
    fun inBounds(x: Int, y: Int) = x in 0 until w && y in 0 until h

    fun setTile(i: Int, t: Int) {
        if (tiles[i].toInt() == t) return
        tiles[i] = t.toByte()
        val y = i / w
        if (y > 0) dirtyRow(y - 1)
        dirtyRow(y)
        if (y < h - 1) dirtyRow(y + 1)
    }

    // ---------------------------------------------------------------- spatial grid
    private fun rebuildGrid() {
        gw = w / CELL + 1
        gh = h / CELL + 1
        var g = grid
        if (g == null || g.size != gw * gh) {
            g = Array(gw * gh) { ArrayList<Creature>() }
            grid = g
        }
        for (l in g) l.clear()
        for (c in creatures) {
            if (!c.alive) continue
            val cx = (c.x.toInt() / CELL).coerceIn(0, gw - 1)
            val cy = (c.y.toInt() / CELL).coerceIn(0, gh - 1)
            g[cy * gw + cx].add(c)
        }
    }

    fun nearest(x: Float, y: Float, r: Float, self: Creature?, f: (Creature) -> Boolean): Creature? {
        val g = grid ?: return null
        val c0 = ((x - r).toInt() / CELL).coerceIn(0, gw - 1)
        val c1 = ((x + r).toInt() / CELL).coerceIn(0, gw - 1)
        val r0 = ((y - r).toInt() / CELL).coerceIn(0, gh - 1)
        val r1 = ((y + r).toInt() / CELL).coerceIn(0, gh - 1)
        var best: Creature? = null
        var bd = r * r
        for (cy in r0..r1) for (cx in c0..c1) {
            for (o in g[cy * gw + cx]) {
                if (o === self || !o.alive || o.inside) continue
                val dx = o.x - x
                val dy = o.y - y
                val d = dx * dx + dy * dy
                if (d < bd && f(o)) { bd = d; best = o }
            }
        }
        return best
    }

    fun forEachNear(x: Float, y: Float, r: Float, f: (Creature) -> Unit) {
        // grid may be stale between steps; use the creature list when the radius is large
        val r2 = r * r
        if (r > 24f || grid == null) {
            for (c in creatures) if (c.alive) {
                val dx = c.x - x; val dy = c.y - y
                if (dx * dx + dy * dy <= r2) f(c)
            }
            return
        }
        val g = grid!!
        val c0 = ((x - r).toInt() / CELL).coerceIn(0, gw - 1)
        val c1 = ((x + r).toInt() / CELL).coerceIn(0, gw - 1)
        val r0 = ((y - r).toInt() / CELL).coerceIn(0, gh - 1)
        val r1 = ((y + r).toInt() / CELL).coerceIn(0, gh - 1)
        for (cy in r0..r1) for (cx in c0..c1) {
            val cell = g[cy * gw + cx]
            for (k in 0 until cell.size) {
                val o = cell[k]
                if (!o.alive) continue
                val dx = o.x - x; val dy = o.y - y
                if (dx * dx + dy * dy <= r2) f(o)
            }
        }
    }

    fun creatureAt(x: Float, y: Float, r: Float = 2f): Creature? {
        var best: Creature? = null
        var bd = r * r
        for (c in creatures) {
            if (!c.alive || c.inside) continue
            val dx = c.x - x; val dy = c.y - y
            val d = dx * dx + dy * dy
            if (d < bd) { bd = d; best = c }
        }
        return best
    }

    // ---------------------------------------------------------------- main step
    fun step(dt: Float) {
        time += dt
        night = nightLevel() > 0.5f
        rebuildGrid()
        stepCreatures(dt)
        creatures.removeAll { !it.alive }
        stepFire()
        stepZones(dt)
        stepNature()
        val ai = ageIdx
        if (lastAge < 0) lastAge = ai
        else if (ai != lastAge) { lastAge = ai; log("${WorldAges.ALL[ai].name} has begun") }
        accPop += dt
        if (accPop >= 5f) {
            accPop = 0f
            popHistory.add(population())
            if (popHistory.size > 400) popHistory.removeAt(0)
        }
        accSlow += dt
        if (accSlow >= 1f) { accSlow -= 1f; stepVillages(1f); stepSapientsOnce() }
        accKingdom += dt
        if (accKingdom >= 8f) { accKingdom -= 8f; stepDiplomacy() }
        val f = fxs()
        var k = 0
        while (k < f.size) {
            val fx = f[k]
            fx.t += dt
            if (fx.t >= fx.life) f.removeAt(k) else k++
        }
    }

    // ---------------------------------------------------------------- creatures
    private fun countKind(): IntArray {
        val n = IntArray(4)
        for (c in creatures) if (c.alive) n[c.sp.kind.ordinal]++
        return n
    }

    fun population(): Int = creatures.count { it.alive && it.sp.kind == Kind.SAPIENT }

    fun spawn(sp: Species, x: Float, y: Float, adult: Boolean = true): Creature? {
        val n = countKind()
        when (sp.kind) {
            Kind.SAPIENT -> if (n[0] >= maxSapients) return null
            Kind.MONSTER -> if (n[3] >= maxMonsters) return null
            else -> if (n[1] + n[2] >= maxAnimals) return null
        }
        val xi = x.toInt(); val yi = y.toInt()
        if (!inBounds(xi, yi)) return null
        val c = Creature(nextCreatureId++, sp.id, x, y, rnd.nextBoolean())
        c.lifeFactor = 0.8f + rnd.nextFloat() * 0.4f
        c.age = if (adult) sp.adultAge + rnd.nextFloat() * min(sp.maxAge * 0.25f, 20f) else 0f
        if (sp.kind == Kind.SAPIENT) c.name = Names.make(rnd)
        c.hunger = 0.7f + rnd.nextFloat() * 0.3f
        c.breedCd = 5f + rnd.nextFloat() * 10f
        creatures.add(c)
        return c
    }

    private fun canEnter(c: Creature, x: Float, y: Float): Boolean {
        if (x < 0f || y < 0f || x >= w - 0.01f || y >= h - 0.01f) return false
        val t = tiles[y.toInt() * w + x.toInt()].toInt()
        val sp = c.sp
        if (sp.flies) return true
        return when (t) {
            Tile.DEEP -> sp.swims
            Tile.MOUNTAIN -> sp.climbs
            Tile.LAVA -> false
            else -> true
        }
    }

    private fun terrainSlow(t: Int): Float = when (t) {
        Tile.SHALLOW -> 0.6f
        Tile.FOREST -> 0.8f
        Tile.HILLS -> 0.7f
        Tile.SNOW -> 0.75f
        Tile.MOUNTAIN -> 0.5f
        else -> 1f
    }

    fun damage(c: Creature, amt: Float, by: Creature?) {
        if (!c.alive) return
        c.hp -= amt
        if (by != null && c.hp > 0f) {
            val k = c.sp.kind
            if (k == Kind.SAPIENT || k == Kind.CARNIVORE || k == Kind.MONSTER) {
                if (c.chase == null || !c.chase!!.alive) c.chase = by
            } else if (k == Kind.HERBIVORE) {
                c.fleeing = true
                c.think = 0f
            }
        }
        if (c.hp <= 0f) {
            kill(c)
            if (by != null) {
                by.kills++
                by.hunger = min(1f, by.hunger + 0.6f)
                if (by.sp.kind == Kind.SAPIENT && c.sp.kind == Kind.HERBIVORE) by.carry = 4
            }
        }
    }

    fun kill(c: Creature) {
        if (!c.alive) return
        c.alive = false
        totalDeaths++
        if (laws.exploding) explode(c.x, c.y, 3f, 0.3f, true, 60f)
        else if (!c.inside) { val f = fxs(); if (f.size < 80) f.add(Fx(Fx.POOF, c.x, c.y, 0.8f, 1f, c.sp.color)) }
    }

    private fun stepCreatures(dt: Float) {
        val ageFx = age
        val n = creatures.size
        for (idx in 0 until n) {
            val c = creatures[idx]
            if (!c.alive) continue
            val sp = c.sp
            val ix = c.x.toInt().coerceIn(0, w - 1)
            val iy = c.y.toInt().coerceIn(0, h - 1)
            val ti = iy * w + ix
            val t = tiles[ti].toInt()

            // ageing
            if (laws.aging && sp.kind != Kind.MONSTER || (laws.aging && sp === Sp.DRAGON)) c.age += dt / YEAR_SEC
            if (!laws.immortal && c.age > sp.maxAge * c.lifeFactor) { kill(c); continue }

            // hunger
            if (laws.hunger && sp !== Sp.ZOMBIE) {
                c.hunger -= dt * sp.hungerRate
                if (c.hunger <= 0f) { c.hunger = 0f; damage(c, 3f * dt, null); if (!c.alive) continue }
            }
            if (sp === Sp.ZOMBIE) { damage(c, 0.4f * dt, null); if (!c.alive) continue }

            // terrain hazards
            if (!sp.flies) {
                if (t == Tile.DEEP && !sp.swims) { damage(c, 25f * dt, null); if (!c.alive) continue }
                if (t == Tile.LAVA) { damage(c, 200f * dt, null); if (!c.alive) continue }
            }
            if (fire[ti].toInt() != 0 && !sp.flies) c.burning = 6f
            if (c.burning > 0f) {
                c.burning -= dt
                if (t == Tile.SHALLOW || t == Tile.DEEP) c.burning = 0f
                damage(c, 5f * dt, null)
                if (!c.alive) continue
                if (Tile.isFlammable(t) || bld[ti] > 0) ignite(ti)
            }

            // infection
            if (c.infected >= 0f) {
                c.infected -= dt
                if (c.infected <= 0f) {
                    c.spId = Sp.ZOMBIE.id
                    c.hp = Sp.ZOMBIE.hp
                    c.infected = -1f
                    c.kingdomId = -1
                    c.villageId = -1
                    c.chase = null
                }
            }

            if (c.mad > 0f) c.mad -= dt
            if (c.atkCd > 0f) c.atkCd -= dt
            if (c.breedCd > 0f) c.breedCd -= dt

            if (c.inside) { updateInside(c, dt); continue }
            if (sp.kind == Kind.SAPIENT) workStep(c, dt)

            c.think -= dt
            if (c.think <= 0f) {
                decide(c, ageFx)
                c.think = 0.4f + rnd.nextFloat() * 0.5f
            }
            combat(c)
            move(c, dt)
        }
        // newborn / spawned creatures were appended during the loop; nothing else to do
    }

    private fun isEnemy(a: Creature, b: Creature): Boolean {
        val ka = a.sp.kind
        val kb = b.sp.kind
        if (a.mad > 0f) return true
        if (ka == Kind.MONSTER) return kb != Kind.MONSTER
        if (ka == Kind.CARNIVORE) {
            if (kb == Kind.HERBIVORE) return b.sp.hp <= a.sp.hp * 1.1f && b.sp !== Sp.DOG
            if (kb == Kind.SAPIENT) return a.sp.hp >= 110f
            return false
        }
        if (ka == Kind.SAPIENT) {
            if (kb == Kind.MONSTER) return true
            if (kb == Kind.SAPIENT && a.kingdomId >= 0 && b.kingdomId >= 0 && a.kingdomId != b.kingdomId) {
                return laws.wars && atWar(a.kingdomId, b.kingdomId)
            }
            if (kb == Kind.CARNIVORE) return b.sp.hp < 100f
        }
        return false
    }

    private fun decide(c: Creature, ageFx: AgeDef) {
        val sp = c.sp
        c.fleeing = false

        // keep chasing a valid target
        val ch = c.chase
        if (ch != null) {
            val dx = ch.x - c.x; val dy = ch.y - c.y
            if (!ch.alive || dx * dx + dy * dy > 20f * 20f) c.chase = null
        }

        when (sp.kind) {
            Kind.HERBIVORE -> {
                val pred = nearest(c.x, c.y, 6f, c) { it.sp.kind == Kind.CARNIVORE || it.sp.kind == Kind.MONSTER ||
                        (it.mad > 0f) }
                if (pred != null && sp !== Sp.DOG) {
                    c.fleeing = true
                    val ang = atan2(c.y - pred.y, c.x - pred.x)
                    setTarget(c, c.x + cos(ang) * 8f, c.y + sin(ang) * 8f)
                } else if (c.mad > 0f) {
                    c.chase = nearest(c.x, c.y, 8f, c) { true }
                } else {
                    graze(c)
                }
            }
            Kind.CARNIVORE, Kind.MONSTER -> {
                if (c.chase == null) {
                    val vis = sp.vision * (if (sp.kind == Kind.MONSTER) 1.3f else 1f)
                    val hungry = c.hunger < 0.85f || sp.kind == Kind.MONSTER
                    if (hungry || c.mad > 0f) c.chase = nearest(c.x, c.y, vis, c) { isEnemy(c, it) }
                }
                if (c.chase == null) wander(c, 10f)
            }
            Kind.SAPIENT -> decideSapient(c)
        }
        tryBreed(c, ageFx)
    }

    private fun graze(c: Creature) {
        val ix = c.x.toInt(); val iy = c.y.toInt()
        if (c.hunger < 0.7f) {
            val t = tileAt(ix.coerceIn(0, w - 1), iy.coerceIn(0, h - 1))
            if (t == Tile.GRASS) {
                c.hunger = min(1f, c.hunger + 0.35f)
                if (rnd.nextFloat() < 0.25f) setTile(iy.coerceIn(0, h - 1) * w + ix.coerceIn(0, w - 1), Tile.SOIL)
                return
            }
            // look for grass nearby
            repeat(6) {
                val nx = ix + rnd.nextInt(13) - 6
                val ny = iy + rnd.nextInt(13) - 6
                if (inBounds(nx, ny) && tileAt(nx, ny) == Tile.GRASS) { setTarget(c, nx + 0.5f, ny + 0.5f); return }
            }
        }
        wander(c, 6f)
    }

    private fun wander(c: Creature, radius: Float) {
        val ang = rnd.nextFloat() * 6.2831855f
        val d = 2f + rnd.nextFloat() * radius
        setTarget(c, c.x + cos(ang) * d, c.y + sin(ang) * d)
    }

    private fun setTarget(c: Creature, x: Float, y: Float) {
        c.tx = x.coerceIn(0.5f, w - 1.5f)
        c.ty = y.coerceIn(0.5f, h - 1.5f)
        c.hasTarget = true
    }

    private fun decideSapient(c: Creature) {
        val sp = c.sp
        // fight / flee
        if (c.chase == null) {
            c.chase = nearest(c.x, c.y, sp.vision, c) { isEnemy(c, it) }
        }
        if (c.chase != null) {
            c.act = Act.IDLE
            if (!c.isAdult) {
                c.fleeing = true
                val e = c.chase!!
                val ang = atan2(c.y - e.y, c.x - e.x)
                setTarget(c, c.x + cos(ang) * 8f, c.y + sin(ang) * 8f)
                c.chase = null
            }
            return
        }
        val v = villageById(c.villageId)
        if (v != null) {
            if (c.act != Act.IDLE) return
            if (c.homeIdx < 0 || bld[c.homeIdx] != v.id + 1) c.homeIdx = pickHome(v, c)
            if (night || c.carry != 0 || (c.hunger < 0.45f && v.food >= 1f)) {
                if (goHome(c, v)) return
            }
            if (!c.isAdult) { wanderAround(c, v.cx, v.cy, v.radius * 0.8f); return }
            if (c.hunger < 0.45f && v.food < 1f) {
                val prey = nearest(c.x, c.y, 12f, c) { it.sp.kind == Kind.HERBIVORE && it.sp !== Sp.DOG }
                if (prey != null) { c.chase = prey; c.job = Job.HUNT; return }
                val t = tileAt(c.x.toInt().coerceIn(0, w - 1), c.y.toInt().coerceIn(0, h - 1))
                if (t == Tile.FOREST || t == Tile.GRASS) c.hunger = min(1f, c.hunger + 0.12f)
            }
            val job = pickJob(c, v)
            if (startJob(c, v, job) || startJob(c, v, Job.WOOD) || startJob(c, v, Job.FARM)) return
            wanderAround(c, v.cx, v.cy, v.radius * 0.9f)
            return
        }
        // homeless: eat what the land offers, then join or found a village
        if (c.hunger < 0.5f) {
            val prey = nearest(c.x, c.y, 10f, c) { it.sp.kind == Kind.HERBIVORE && it.sp !== Sp.DOG }
            if (prey != null && c.isAdult) { c.chase = prey; return }
            val t = tileAt(c.x.toInt().coerceIn(0, w - 1), c.y.toInt().coerceIn(0, h - 1))
            if (t == Tile.FOREST || t == Tile.GRASS) c.hunger = min(1f, c.hunger + 0.12f)
        }
        if (c.isAdult) {
            var near: Village? = null
            var bd = 45f * 45f
            for (vv in villages) {
                if (vv.speciesId != c.spId) continue
                val dx = vv.cx - c.x; val dy = vv.cy - c.y
                val d = dx * dx + dy * dy
                if (d < bd) { bd = d; near = vv }
            }
            if (near != null) {
                c.villageId = near.id
                c.kingdomId = near.kingdomId
                setTarget(c, near.cx, near.cy)
                return
            }
            val mates = nearest(c.x, c.y, 7f, c) { it.spId == c.spId && it.isAdult && it.villageId < 0 }
            val t = tileAt(c.x.toInt().coerceIn(0, w - 1), c.y.toInt().coerceIn(0, h - 1))
            if (mates != null && (t == Tile.GRASS || t == Tile.SOIL || t == Tile.SAND || t == Tile.FOREST)) {
                foundVillage(c, mates)
                return
            }
        }
        wander(c, 14f)
    }

    // ---------------------------------------------------------------- daily life of villagers
    private fun wanderAround(c: Creature, cx: Float, cy: Float, r: Float) {
        val ang = rnd.nextFloat() * 6.2831855f
        val d = rnd.nextFloat() * r
        setTarget(c, cx + cos(ang) * d, cy + sin(ang) * d)
    }

    private fun pickHome(v: Village, c: Creature): Int =
        if (v.houses.isEmpty()) -1 else v.houses[(c.id * 7 + 3) % v.houses.size]

    private fun pickJob(c: Creature, v: Village): Int {
        if (v.site >= 0 && c.id % 3 == 0) return if (v.wood >= 1) Job.BUILD else Job.WOOD
        return when (c.id % 8) {
            0, 1, 2 -> if (v.fields.isNotEmpty()) Job.FARM else Job.WOOD
            3, 4, 7 -> Job.WOOD
            5 -> Job.MINE
            else -> Job.HUNT
        }
    }

    private fun findTile(v: Village, radius: Float, tries: Int, pred: (Int) -> Boolean): Int {
        for (k in 0 until tries) {
            val ang = rnd.nextFloat() * 6.2831855f
            val d = sqrt(rnd.nextFloat()) * radius
            val x = (v.cx + cos(ang) * d).toInt()
            val y = (v.cy + sin(ang) * d).toInt()
            if (!inBounds(x, y)) continue
            val i = y * w + x
            if (pred(i)) return i
        }
        return -1
    }

    private fun startJob(c: Creature, v: Village, job: Int): Boolean {
        var tile = -1
        when (job) {
            Job.WOOD -> {
                tile = findTile(v, 18f, 14) { tiles[it].toInt() == Tile.FOREST && fire[it].toInt() == 0 }
                if (tile < 0) tile = findTile(v, 34f, 22) { tiles[it].toInt() == Tile.FOREST && fire[it].toInt() == 0 }
            }
            Job.FARM -> if (v.fields.isNotEmpty()) tile = v.fields[rnd.nextInt(v.fields.size)]
            Job.MINE -> tile = findTile(v, 40f, 30) {
                val t = tiles[it].toInt()
                (t == Tile.HILLS || t == Tile.MOUNTAIN) && (ore[it].toInt() != 0 || rnd.nextFloat() < 0.25f)
            }
            Job.BUILD -> tile = v.site
            Job.HUNT -> {
                val prey = nearest(c.x, c.y, 16f, c) { it.sp.kind == Kind.HERBIVORE && it.sp !== Sp.DOG }
                if (prey != null) { c.chase = prey; c.job = Job.HUNT; return true }
                return false
            }
        }
        if (tile < 0) return false
        var sx = tile % w
        var sy = tile / w
        if (job == Job.MINE && tiles[tile].toInt() == Tile.MOUNTAIN && !c.sp.climbs) {
            // stand next to the rock face
            var found = false
            for (k in 0 until 4) {
                val nx = sx + (if (k == 0) 1 else if (k == 1) -1 else 0)
                val ny = sy + (if (k == 2) 1 else if (k == 3) -1 else 0)
                if (!inBounds(nx, ny)) continue
                val nt = tiles[ny * w + nx].toInt()
                if (nt != Tile.MOUNTAIN && nt != Tile.DEEP && nt != Tile.LAVA) { sx = nx; sy = ny; found = true; break }
            }
            if (!found) return false
        }
        c.job = job
        c.workTile = tile
        c.act = Act.GOTO_WORK
        setTarget(c, sx + 0.5f, sy + 0.95f)
        return true
    }

    private fun workTime(c: Creature, v: Village): Float {
        val base = when (c.job) {
            Job.WOOD -> 3.5f
            Job.FARM -> 4.0f
            Job.MINE -> 4.5f
            else -> 3.2f
        }
        return base * (1f - 0.07f * v.tech) * (0.85f + rnd.nextFloat() * 0.3f)
    }

    private fun goHome(c: Creature, v: Village): Boolean {
        if (c.homeIdx < 0 || bld[c.homeIdx] != v.id + 1) c.homeIdx = pickHome(v, c)
        val hi = c.homeIdx
        if (hi < 0) return false
        c.act = Act.RETURN
        c.chase = null
        setTarget(c, hi % w + 0.5f, hi / w + 0.95f)
        return true
    }

    private fun workStep(c: Creature, dt: Float) {
        if (c.act == Act.IDLE) return
        val v = villageById(c.villageId)
        if (v == null) { c.act = Act.IDLE; return }
        if (c.chase != null) { c.act = Act.IDLE; return }
        when (c.act) {
            Act.GOTO_WORK -> {
                if (night) { if (!goHome(c, v)) c.act = Act.IDLE; return }
                if (!c.hasTarget) {
                    val dx = c.tx - c.x; val dy = c.ty - c.y
                    if (dx * dx + dy * dy < 1.2f * 1.2f) { c.act = Act.WORK; c.actTimer = workTime(c, v) }
                    else { c.act = Act.IDLE; c.think = 0.5f }
                }
            }
            Act.WORK -> {
                if (night) { if (!goHome(c, v)) c.act = Act.IDLE; return }
                c.actTimer -= dt
                if (c.actTimer <= 0f) finishWork(c, v)
            }
            Act.RETURN -> if (!c.hasTarget) {
                val dx = c.tx - c.x; val dy = c.ty - c.y
                if (dx * dx + dy * dy < 1.2f * 1.2f) enterHouse(c, v) else { c.act = Act.IDLE; c.think = 0.5f }
            }
        }
    }

    private fun finishWork(c: Creature, v: Village) {
        val t = c.workTile
        when (c.job) {
            Job.WOOD -> if (t >= 0 && tiles[t].toInt() == Tile.FOREST) {
                if (rnd.nextFloat() < 0.75f) setTile(t, Tile.GRASS)
                c.carry = 1
            }
            Job.FARM -> if (t >= 0 && tiles[t].toInt() == Tile.FIELD) c.carry = 2
            Job.MINE -> if (t >= 0) {
                c.carry = 3
                if (ore[t].toInt() != 0) { ore[t] = 0; dirtyRow(t / w); v.ore += 1 }
            }
            Job.BUILD -> if (v.site >= 0 && v.wood >= 1) {
                v.wood--
                v.siteProgress++
                if (v.siteProgress >= 6) completeHouse(v)
            }
        }
        c.act = Act.IDLE
        c.workTile = -1
        c.think = 0.1f
        if (c.carry != 0) goHome(c, v)
    }

    private fun completeHouse(v: Village) {
        val s = v.site
        if (s >= 0 && bld[s] == 0 && !Tile.isWater(tiles[s].toInt())) {
            bld[s] = v.id + 1
            v.houses.add(s)
        }
        v.site = -1
        v.siteProgress = 0
    }

    private fun enterHouse(c: Creature, v: Village) {
        when (c.carry) {
            1 -> v.wood++
            2 -> v.food += 5f
            3 -> { v.ore++; v.tech = min(5, v.ore / 8) }
            4 -> v.food += 6f
        }
        c.carry = 0
        if (c.hunger < 0.8f && v.food >= 1f) { v.food -= 1f; c.hunger = min(1f, c.hunger + 0.7f) }
        val hi = c.homeIdx
        if (hi >= 0) { c.x = hi % w + 0.5f; c.y = hi / w + 0.6f }
        c.inside = true
        c.hasTarget = false
        c.chase = null
        c.sleeping = night
        c.actTimer = if (night) rnd.nextFloat() * 4f else 1.2f + rnd.nextFloat() * 1.8f
        c.act = Act.INSIDE
    }

    private fun exitHouse(c: Creature) {
        c.inside = false
        c.act = Act.IDLE
        c.y = (c.y + 0.55f).coerceAtMost(h - 1.5f)
        c.think = 0.1f + rnd.nextFloat() * 0.3f
    }

    private fun updateInside(c: Creature, dt: Float) {
        if (c.sleeping) {
            if (!night) { c.actTimer -= dt; if (c.actTimer <= 0f) exitHouse(c) }
        } else {
            c.actTimer -= dt
            if (c.actTimer <= 0f) exitHouse(c)
        }
    }

    /** Public so world generation can drop a ready-made tribe (with a first house) onto the map. */
    fun spawnTribe(sp: Species, x: Float, y: Float) {
        val list = ArrayList<Creature>()
        repeat(6) {
            val ang = rnd.nextFloat() * 6.2831855f
            val d = rnd.nextFloat() * 2.5f
            val c = spawn(sp, x + cos(ang) * d, y + sin(ang) * d, true)
            if (c != null) list.add(c)
        }
        if (list.size >= 2) foundVillage(list[0], list[1])
    }

    private fun tryBreed(c: Creature, ageFx: AgeDef) {
        if (!laws.reproduction || c.male || !c.isAdult || c.breedCd > 0f || c.hunger < 0.5f) return
        val sp = c.sp
        if (sp === Sp.ZOMBIE || sp === Sp.DRAGON) return
        val mate = nearest(c.x, c.y, 3f, c) { it.spId == c.spId && it.male && it.isAdult }
        if (mate == null) return
        if (sp.kind == Kind.SAPIENT) {
            val v = villageById(c.villageId) ?: return
            if (v.pop >= v.houses.size * 4 + 2) return
        }
        val chance = 0.5f * ageFx.birth
        c.breedCd = (if (sp.kind == Kind.SAPIENT) 22f else 32f) / ageFx.birth
        if (rnd.nextFloat() > chance) return
        val b = spawn(sp, c.x, c.y, false) ?: return
        b.villageId = c.villageId
        b.kingdomId = c.kingdomId
        b.hunger = 0.9f
    }

    private fun combat(c: Creature) {
        val t = c.chase ?: return
        if (!t.alive) { c.chase = null; return }
        val dx = t.x - c.x; val dy = t.y - c.y
        if (dx * dx + dy * dy < 1.5f * 1.5f && c.atkCd <= 0f) {
            val sp = c.sp
            val dmg = sp.dmg * (0.7f + rnd.nextFloat() * 0.6f)
            c.atkCd = 0.8f
            if (sp === Sp.ZOMBIE && t.sp.kind != Kind.MONSTER && t.infected < 0f && rnd.nextFloat() < 0.7f) t.infected = 10f
            if (sp === Sp.DRAGON) {
                val tx = t.x.toInt(); val ty = t.y.toInt()
                for (oy in -1..1) for (ox in -1..1) if (inBounds(tx + ox, ty + oy) && rnd.nextFloat() < 0.6f) ignite((ty + oy) * w + tx + ox)
                t.burning = 6f
            }
            damage(t, if (dmg > 0f) dmg else 1f, c)
        }
    }

    private fun move(c: Creature, dt: Float) {
        val ch = c.chase
        val tx: Float
        val ty: Float
        if (ch != null && ch.alive) { tx = ch.x; ty = ch.y } else if (c.hasTarget) { tx = c.tx; ty = c.ty } else return
        val dx = tx - c.x
        val dy = ty - c.y
        val d = sqrt(dx * dx + dy * dy)
        if (d < 0.3f) { c.hasTarget = false; return }
        val t = tiles[c.y.toInt().coerceIn(0, h - 1) * w + c.x.toInt().coerceIn(0, w - 1)].toInt()
        var sp = c.sp.speed * terrainSlow(t)
        if (c.fleeing || c.burning > 0f) sp *= 1.4f
        if (abs(dx) > 0.02f) c.faceRight = dx > 0f
        val step = min(sp * dt, d)
        val nx = c.x + dx / d * step
        val ny = c.y + dy / d * step
        when {
            canEnter(c, nx, ny) -> { c.x = nx; c.y = ny }
            canEnter(c, nx, c.y) -> c.x = nx
            canEnter(c, c.x, ny) -> c.y = ny
            else -> { c.hasTarget = false; c.fleeing = false }
        }
    }

    // ---------------------------------------------------------------- villages & kingdoms
    fun villageById(id: Int): Village? {
        if (id < 0) return null
        for (v in villages) if (v.id == id) return v
        return null
    }

    fun kingdomById(id: Int): Kingdom? {
        if (id < 0) return null
        for (k in kingdoms) if (k.id == id) return k
        return null
    }

    private fun foundVillage(founder: Creature, mate: Creature) {
        for (v in villages) {
            val dx = v.cx - founder.x; val dy = v.cy - founder.y
            if (dx * dx + dy * dy < 18f * 18f) return
        }
        val v = Village(nextVillageId++, Names.make(rnd, 2), founder.spId, founder.x, founder.y)
        // join a kingdom of the same species if one is close, otherwise start a new one
        var kd: Kingdom? = null
        var bd = 40f * 40f
        for (o in villages) {
            if (o.speciesId != v.speciesId) continue
            val dx = o.cx - v.cx; val dy = o.cy - v.cy
            val d = dx * dx + dy * dy
            if (d < bd) { bd = d; kd = kingdomById(o.kingdomId) }
        }
        if (kd == null) {
            kd = Kingdom(nextKingdomId++, Names.make(rnd, 2), v.speciesId,
                KINGDOM_COLORS[(nextKingdomId + rnd.nextInt(3)) % KINGDOM_COLORS.size], year)
            kd.kingId = founder.id
            kd.kingName = founder.name
            kd.capitalId = v.id
            kingdoms.add(kd)
            log("New kingdom ${kd.name} was founded")
        }
        v.kingdomId = kd.id
        villages.add(v)
        log("New village ${v.name} has been founded")
        founder.villageId = v.id; founder.kingdomId = kd.id
        mate.villageId = v.id; mate.kingdomId = kd.id
        // first house
        buildHouse(v, 0f)
    }

    private fun findHouseSpot(v: Village, spread: Float): Int {
        repeat(12) {
            val ang = rnd.nextFloat() * 6.2831855f
            val d = 1f + rnd.nextFloat() * (2f + spread)
            val x = (v.cx + cos(ang) * d).toInt()
            val y = (v.cy + sin(ang) * d).toInt()
            if (!inBounds(x, y)) return@repeat
            val i = y * w + x
            val t = tiles[i].toInt()
            if (bld[i] != 0 || fire[i].toInt() != 0) return@repeat
            if (t == Tile.GRASS || t == Tile.SOIL || t == Tile.SAND || t == Tile.FOREST) {
                // keep a tile of space between houses
                for (oy in -1..1) for (ox in -1..1) {
                    if (inBounds(x + ox, y + oy) && bld[(y + oy) * w + x + ox] != 0) return@repeat
                }
                return i
            }
        }
        return -1
    }

    private fun buildHouse(v: Village, spread: Float): Boolean {
        val i = findHouseSpot(v, spread)
        if (i < 0) return false
        bld[i] = v.id + 1
        v.houses.add(i)
        return true
    }

    fun removeBuilding(i: Int) {
        val b = bld[i]
        if (b > 0) {
            bld[i] = 0
            villageById(b - 1)?.houses?.remove(i)
            for (c in creatures) if (c.homeIdx == i) {
                c.homeIdx = -1
                if (c.inside) { c.inside = false; c.act = Act.IDLE; c.sleeping = false }
            }
        }
    }

    private fun stepVillages(dt: Float) {
        // residents per village
        for (v in villages) v.pop = 0
        for (c in creatures) {
            if (!c.alive || c.villageId < 0) continue
            villageById(c.villageId)?.let { it.pop++ }
        }
        val vit = villages.iterator()
        while (vit.hasNext()) {
            val v = vit.next()
            v.fields.removeAll { tiles[it].toInt() != Tile.FIELD }
            v.food = min(v.food + (v.houses.size * 0.9f + v.fields.size * 0.6f) * dt * age.growth, 40f + v.houses.size * 10f)
            v.buildCd -= dt
            v.fieldCd -= dt
            if (v.pop >= 4 && v.fieldCd <= 0f && v.fields.size < v.houses.size * 3 / 2 + 1) {
                v.fieldCd = 5f
                repeat(8) {
                    val ang = rnd.nextFloat() * 6.2831855f
                    val d = 2.5f + rnd.nextFloat() * (v.radius * 0.8f)
                    val fx = (v.cx + cos(ang) * d).toInt()
                    val fy = (v.cy + sin(ang) * d).toInt()
                    if (!inBounds(fx, fy)) return@repeat
                    val fi = fy * w + fx
                    val ft = tiles[fi].toInt()
                    if ((ft == Tile.GRASS || ft == Tile.SOIL) && bld[fi] == 0 && fire[fi].toInt() == 0 && !v.fields.contains(fi)) {
                        setTile(fi, Tile.FIELD)
                        v.fields.add(fi)
                        return@repeat
                    }
                }
            }
            if (v.site >= 0 && (Tile.isWater(tiles[v.site].toInt()) || tiles[v.site].toInt() == Tile.LAVA || bld[v.site] != 0)) {
                v.site = -1; v.siteProgress = 0
            }
            if (v.site < 0 && v.pop > v.houses.size * 3 - 1 && v.buildCd <= 0f && v.houses.size < 80) {
                val si = findHouseSpot(v, v.houses.size * 0.4f)
                if (si >= 0) { v.site = si; v.siteProgress = 0; v.buildCd = 4f } else v.buildCd = 3f
            }
            if (v.pop == 0) v.emptyTime += dt else v.emptyTime = 0f
            if (v.emptyTime > 40f) {
                for (hi in ArrayList(v.houses)) bld[hi] = 0
                vit.remove()
            }
        }
        // conquest: an undefended village falls to enemy soldiers standing in it
        if (laws.wars && kingdoms.size > 1 && wars.isNotEmpty()) {
            for (v in villages) {
                var own = 0
                var enemy = 0
                var enemyK = -1
                forEachNear(v.cx, v.cy, v.radius) { c ->
                    if (c.sp.kind == Kind.SAPIENT && c.isAdult) {
                        if (c.kingdomId == v.kingdomId) own++
                        else if (c.kingdomId >= 0 && atWar(c.kingdomId, v.kingdomId)) { enemy++; enemyK = c.kingdomId }
                    }
                }
                if (enemy >= 3 && own == 0 && enemyK >= 0) {
                    val oldK = kingdomById(v.kingdomId)
                    val newK = kingdomById(enemyK)
                    if (newK != null) {
                        v.kingdomId = enemyK
                        for (c in creatures) if (c.alive && c.villageId == v.id) c.kingdomId = enemyK
                        log("${newK.name} captured ${v.name} from ${oldK?.name ?: "its rulers"}")
                        sound("horn")
                    }
                }
            }
        }
        // kingdoms
        val kit = kingdoms.iterator()
        while (kit.hasNext()) {
            val k = kit.next()
            var vc = 0
            var cap = -1
            for (v in villages) if (v.kingdomId == k.id) { vc++; if (cap < 0) cap = v.id }
            k.villages = vc
            if (vc == 0) {
                log("The kingdom of ${k.name} has fallen")
                wars.removeAll { (it / 100000L).toInt() == k.id || (it % 100000L).toInt() == k.id }
                kit.remove()
                continue
            }
            val capV = villageById(k.capitalId)
            if (capV == null || capV.kingdomId != k.id) k.capitalId = cap
            var pop = 0
            var oldest: Creature? = null
            var king: Creature? = null
            for (c in creatures) {
                if (!c.alive || c.kingdomId != k.id) continue
                pop++
                if (c.id == k.kingId) king = c
                if (c.isAdult && (oldest == null || c.age > oldest.age)) oldest = c
            }
            k.pop = pop
            if (king == null && oldest != null) {
                k.kingId = oldest.id; k.kingName = oldest.name
                log("${oldest.name} has become the new king of ${k.name}")
            }
        }
    }

    private fun stepSapientsOnce() {
        // drop dead village / kingdom links
        for (c in creatures) {
            if (!c.alive) continue
            if (c.villageId >= 0 && villageById(c.villageId) == null) { c.villageId = -1; c.kingdomId = -1 }
        }
    }

    fun warKey(a: Int, b: Int): Long = min(a, b).toLong() * 100000L + max(a, b).toLong()
    fun atWar(a: Int, b: Int) = wars.contains(warKey(a, b))

    private fun stepDiplomacy() {
        if (kingdoms.size < 2) return
        for (i in 0 until kingdoms.size) for (j in i + 1 until kingdoms.size) {
            val a = kingdoms[i]; val b = kingdoms[j]
            var close = 1e9f
            for (va in villages) if (va.kingdomId == a.id) for (vb in villages) if (vb.kingdomId == b.id) {
                val dx = va.cx - vb.cx; val dy = va.cy - vb.cy
                close = min(close, sqrt(dx * dx + dy * dy))
            }
            val key = warKey(a.id, b.id)
            if (wars.contains(key)) {
                if (!laws.wars || rnd.nextFloat() < 0.08f) { wars.remove(key); log("${a.name} and ${b.name} made peace") }
            } else if (laws.wars && close < 55f) {
                val p = if (a.speciesId != b.speciesId) 0.30f else 0.10f
                if (rnd.nextFloat() < p) { wars.add(key); sound("horn"); log("${a.name} declared war on ${b.name}") }
            }
        }
    }

    // ---------------------------------------------------------------- fire
    fun ignite(i: Int) {
        if (i < 0 || i >= w * h) return
        val t = tiles[i].toInt()
        if (fire[i].toInt() != 0) return
        val house = bld[i] > 0
        if (!(Tile.isFlammable(t) || house)) return
        fire[i] = (if (t == Tile.FOREST) 90 else if (house) 70 else 40).toByte()
        burning.add(i)
        dirtyRow(i / w)
    }

    fun igniteForce(i: Int, dur: Int) {
        if (i < 0 || i >= w * h) return
        val t = tiles[i].toInt()
        if (Tile.isWater(t) || t == Tile.LAVA) return
        if (fire[i].toInt() != 0) return
        fire[i] = dur.coerceIn(1, 120).toByte()
        burning.add(i)
        dirtyRow(i / w)
    }

    private fun stepFire() {
        if (burning.isEmpty()) return
        val n = burning.size
        val mult = age.fire
        for (k in 0 until n) {
            val i = burning[k]
            var f = fire[i].toInt() and 0xFF
            if (f == 0) continue
            f--
            val t = tiles[i].toInt()
            if (Tile.isWater(t)) f = 0
            if (laws.fireSpread && f > 0) {
                val p = (if (t == Tile.FOREST) 0.07f else 0.05f) * mult
                val x = i % w; val y = i / w
                if (x > 0 && rnd.nextFloat() < p) ignite(i - 1)
                if (x < w - 1 && rnd.nextFloat() < p) ignite(i + 1)
                if (y > 0 && rnd.nextFloat() < p) ignite(i - w)
                if (y < h - 1 && rnd.nextFloat() < p) ignite(i + w)
            }
            fire[i] = f.toByte()
            dirtyRow(i / w)
            if (f == 0) {
                if (t == Tile.FOREST || t == Tile.GRASS) tiles[i] = Tile.WASTE.toByte()
                removeBuilding(i)
            }
        }
        val out = ArrayList<Int>(burning.size)
        for (k in 0 until burning.size) {
            val i = burning[k]
            if ((fire[i].toInt() and 0xFF) > 0) out.add(i)
        }
        burning.clear()
        burning.addAll(out)
    }

    // ---------------------------------------------------------------- zones
    private fun stepZones(dt: Float) {
        var k = 0
        while (k < zones.size) {
            val z = zones[k]
            z.life -= dt
            when (z.type) {
                Zone.RAIN -> zoneTiles(z.x, z.y, z.r, max(2, (z.r * z.r * 0.25f).toInt())) { i ->
                    if (fire[i].toInt() != 0) fire[i] = 0
                    val t = tiles[i].toInt()
                    if ((t == Tile.SOIL || t == Tile.WASTE) && rnd.nextFloat() < 0.35f) setTile(i, Tile.GRASS)
                    else if (t == Tile.GRASS && rnd.nextFloat() < 0.02f) setTile(i, Tile.FOREST)
                }
                Zone.ACID -> {
                    zoneTiles(z.x, z.y, z.r, max(2, (z.r * z.r * 0.3f).toInt())) { i ->
                        val t = tiles[i].toInt()
                        if ((t == Tile.GRASS || t == Tile.FOREST) && rnd.nextFloat() < 0.4f) setTile(i, Tile.SOIL)
                        else if (t == Tile.SOIL && rnd.nextFloat() < 0.1f) setTile(i, Tile.WASTE)
                        removeBuildingChance(i, 0.2f)
                    }
                    forEachNear(z.x, z.y, z.r) { damage(it, 8f * dt, null) }
                }
                Zone.TORNADO -> {
                    z.x += z.vx * dt; z.y += z.vy * dt
                    if (rnd.nextFloat() < 0.1f) { z.vx += (rnd.nextFloat() - 0.5f) * 3f; z.vy += (rnd.nextFloat() - 0.5f) * 3f }
                    z.vx = z.vx.coerceIn(-3f, 3f); z.vy = z.vy.coerceIn(-3f, 3f)
                    if (z.x < 2f || z.x > w - 2f) z.vx = -z.vx
                    if (z.y < 2f || z.y > h - 2f) z.vy = -z.vy
                    z.x = z.x.coerceIn(1f, w - 2f); z.y = z.y.coerceIn(1f, h - 2f)
                    forEachNear(z.x, z.y, 3.5f) {
                        damage(it, 25f * dt, null)
                        if (it.alive && !it.sp.flies) {
                            it.x = (it.x + (rnd.nextFloat() - 0.5f) * 1.2f).coerceIn(0.5f, w - 1.5f)
                            it.y = (it.y + (rnd.nextFloat() - 0.5f) * 1.2f).coerceIn(0.5f, h - 1.5f)
                        }
                    }
                    zoneTiles(z.x, z.y, 3f, 12) { i ->
                        if (tiles[i].toInt() == Tile.FOREST && rnd.nextFloat() < 0.3f) setTile(i, Tile.GRASS)
                        removeBuildingChance(i, 0.35f)
                    }
                }
                Zone.BLACKHOLE -> {
                    val prog = 1f - z.life / z.maxLife
                    val rad = 2f + prog * 9f
                    z.r = rad
                    forEachNear(z.x, z.y, rad * 1.8f) {
                        val dx = z.x - it.x; val dy = z.y - it.y
                        val d = sqrt(dx * dx + dy * dy)
                        if (d < rad * 0.7f) kill(it)
                        else if (d > 0.01f && !it.sp.flies) {
                            it.x += dx / d * 2.5f * dt; it.y += dy / d * 2.5f * dt
                        }
                    }
                    val ir = ceil(rad * 0.8f).toInt()
                    for (oy in -ir..ir) for (ox in -ir..ir) {
                        if (ox * ox + oy * oy > (rad * 0.8f) * (rad * 0.8f)) continue
                        val x = z.x.toInt() + ox; val y = z.y.toInt() + oy
                        if (!inBounds(x, y)) continue
                        val i = y * w + x
                        removeBuilding(i); fire[i] = 0
                        setTile(i, Tile.DEEP)
                    }
                }
                Zone.METEOR -> if (z.life <= 0f) {
                    explode(z.x, z.y, 7f, 0.5f, true, 300f)
                }
                Zone.VOLCANO -> {
                    z.r = 3.5f + 5f * (1f - z.life / z.maxLife)
                    zoneTiles(z.x, z.y, 2.5f, 3) { i -> removeBuilding(i); fire[i] = 0; setTile(i, Tile.LAVA) }
                    zoneTiles(z.x, z.y, z.r, 4) { i ->
                        val t = tiles[i].toInt()
                        if (t != Tile.LAVA) { removeBuilding(i); fire[i] = 0; setTile(i, Tile.LAVA) }
                    }
                    forEachNear(z.x, z.y, z.r) { damage(it, 40f * dt, null); it.burning = 4f }
                    if (rnd.nextFloat() < 0.08f) fxs().add(Fx(Fx.EXPLOSION, z.x + (rnd.nextFloat() - 0.5f) * z.r, z.y + (rnd.nextFloat() - 0.5f) * z.r, 0.6f, 2.5f))
                    if (z.life <= 0f) {
                        val ir = 3
                        for (oy in -ir..ir) for (ox in -ir..ir) {
                            if (ox * ox + oy * oy > 9) continue
                            val x = z.x.toInt() + ox; val y = z.y.toInt() + oy
                            if (inBounds(x, y)) setTile(y * w + x, Tile.MOUNTAIN)
                        }
                        log("A volcano has formed")
                    }
                }
            }
            if (z.life <= 0f) zones.removeAt(k) else k++
        }
    }

    private inline fun zoneTiles(cx: Float, cy: Float, r: Float, samples: Int, f: (Int) -> Unit) {
        repeat(samples) {
            val ang = rnd.nextFloat() * 6.2831855f
            val d = sqrt(rnd.nextFloat()) * r
            val x = (cx + cos(ang) * d).toInt()
            val y = (cy + sin(ang) * d).toInt()
            if (inBounds(x, y)) f(y * w + x)
        }
    }

    private fun removeBuildingChance(i: Int, p: Float) {
        if (bld[i] > 0 && rnd.nextFloat() < p) removeBuilding(i)
    }

    // ---------------------------------------------------------------- ambient nature
    private fun stepNature() {
        val a = age
        val wanted = max(8, w * h / 60)
        val samples = min(wanted, 30000)
        val boost = wanted.toFloat() / samples
        repeat(samples) {
            val i = rnd.nextInt(w * h)
            val t = tiles[i].toInt()
            when (t) {
                Tile.WASTE -> if (rnd.nextFloat() < 0.05f * a.growth * boost) setTile(i, Tile.SOIL)
                Tile.SOIL -> if (rnd.nextFloat() < 0.12f * a.growth * boost && neighbourIs(i, Tile.GRASS, Tile.FOREST)) setTile(i, Tile.GRASS)
                Tile.GRASS -> if (rnd.nextFloat() < 0.008f * a.growth * boost && neighbourIs(i, Tile.FOREST, -1)) setTile(i, Tile.FOREST)
                Tile.LAVA -> {
                    val x = i % w; val y = i / w
                    if (x > 0) ignite(i - 1)
                    if (x < w - 1) ignite(i + 1)
                    if (y > 0) ignite(i - w)
                    if (y < h - 1) ignite(i + w)
                    val wet = neighbourIs(i, Tile.SHALLOW, Tile.DEEP)
                    if (rnd.nextFloat() < (if (wet) 0.35f else 0.04f) * boost) setTile(i, Tile.HILLS)
                }
            }
        }
        // age specific events
        when (ageIdx) {
            3 -> if (rnd.nextFloat() < 0.012f) zones.add(Zone(Zone.RAIN, rnd.nextFloat() * w, rnd.nextFloat() * h, 9f, 18f))
            5 -> if (rnd.nextFloat() < 0.004f) randomDisaster()
            2 -> if (rnd.nextFloat() < 0.003f) {
                val x = rnd.nextInt(w); val y = rnd.nextInt(h)
                if (!Tile.isWater(tiles[y * w + x].toInt())) spawn(Sp.ZOMBIE, x + 0.5f, y + 0.5f)
            }
            6 -> if (rnd.nextFloat() < 0.02f && creatures.isNotEmpty()) {
                val c = creatures[rnd.nextInt(creatures.size)]
                if (c.alive) { c.hp = c.sp.hp; c.hunger = 1f; fxs().add(Fx(Fx.RING, c.x, c.y, 1.2f, 3f)) }
            }
        }
    }

    private fun randomDisaster() {
        val x = rnd.nextFloat() * w
        val y = rnd.nextFloat() * h
        when (rnd.nextInt(3)) {
            0 -> lightning(x, y)
            1 -> zones.add(Zone(Zone.METEOR, x, y, 1f, 1f))
            else -> zones.add(Zone(Zone.TORNADO, x, y, 3f, 20f).also { it.vx = rnd.nextFloat() * 2f - 1f; it.vy = rnd.nextFloat() * 2f - 1f })
        }
    }

    private fun neighbourIs(i: Int, a: Int, b: Int): Boolean {
        val x = i % w; val y = i / w
        if (x > 0) { val t = tiles[i - 1].toInt(); if (t == a || t == b) return true }
        if (x < w - 1) { val t = tiles[i + 1].toInt(); if (t == a || t == b) return true }
        if (y > 0) { val t = tiles[i - w].toInt(); if (t == a || t == b) return true }
        if (y < h - 1) { val t = tiles[i + w].toInt(); if (t == a || t == b) return true }
        return false
    }

    // ---------------------------------------------------------------- effects
    fun lightning(x: Float, y: Float) {
        fxs().add(Fx(Fx.LIGHTNING, x, y, 0.35f, 0f))
        sound("zap")
        val xi = x.toInt(); val yi = y.toInt()
        if (!inBounds(xi, yi)) return
        forEachNear(x, y, 1.8f) { damage(it, 80f, null); it.burning = 5f }
        for (oy in -1..1) for (ox in -1..1) {
            val nx = xi + ox; val ny = yi + oy
            if (inBounds(nx, ny) && rnd.nextFloat() < 0.6f) ignite(ny * w + nx)
        }
    }

    fun explode(cx: Float, cy: Float, r: Float, fireChance: Float, terrain: Boolean, dmg: Float) {
        fxs().add(Fx(Fx.EXPLOSION, cx, cy, 0.8f, r))
        sound("boom")
        val ir = ceil(r).toInt()
        for (dy in -ir..ir) for (dx in -ir..ir) {
            val d = sqrt((dx * dx + dy * dy).toFloat())
            if (d > r) continue
            val x = cx.toInt() + dx; val y = cy.toInt() + dy
            if (!inBounds(x, y)) continue
            val i = y * w + x
            removeBuilding(i)
            val t = tiles[i].toInt()
            if (terrain && !Tile.isWater(t)) {
                val core = d < r * 0.45f
                val nt = when (t) {
                    Tile.MOUNTAIN -> if (core) Tile.SOIL else Tile.HILLS
                    Tile.HILLS -> Tile.SOIL
                    Tile.FOREST, Tile.GRASS -> Tile.WASTE
                    Tile.SNOW -> Tile.SOIL
                    else -> t
                }
                if (r >= 8f && d < r * 0.22f) setTile(i, Tile.SHALLOW) else setTile(i, nt)
                fire[i] = 0
                if (rnd.nextFloat() < fireChance) igniteForce(i, 20 + rnd.nextInt(40))
            }
        }
        forEachNear(cx, cy, r) {
            val dx = it.x - cx; val dy = it.y - cy
            val d = sqrt(dx * dx + dy * dy)
            damage(it, dmg * (1f - 0.5f * d / r), null)
        }
    }

    // ---------------------------------------------------------------- powers
    private fun paint(cx: Float, cy: Float, r: Int, f: (Int, Int) -> Unit) {
        val xi = cx.toInt(); val yi = cy.toInt()
        for (dy in -r..r) for (dx in -r..r) {
            if (dx * dx + dy * dy > r * r + r) continue
            val x = xi + dx; val y = yi + dy
            if (inBounds(x, y)) f(y * w + x, tiles[y * w + x].toInt())
        }
    }

    private fun terrain(cx: Float, cy: Float, r: Int, to: Int, landOnly: Boolean, prob: Float = 1f) {
        paint(cx, cy, r) { i, t ->
            if (landOnly && Tile.isWater(t)) return@paint
            if (prob < 1f && rnd.nextFloat() > prob) return@paint
            if (to == Tile.FOREST && (t == Tile.MOUNTAIN || t == Tile.HILLS || t == Tile.LAVA)) return@paint
            if (t != to) {
                setTile(i, to)
                if (to == Tile.DEEP || to == Tile.SHALLOW || to == Tile.LAVA || to == Tile.MOUNTAIN) { removeBuilding(i); fire[i] = 0 }
                if (Tile.isWater(to)) fire[i] = 0
            }
        }
    }

    fun spawnAt(key: String, x: Float, y: Float, r: Int) {
        val sp = Sp.byKey(key) ?: return
        val ang = rnd.nextFloat() * 6.2831855f
        val d = sqrt(rnd.nextFloat()) * r
        val sx = x + cos(ang) * d
        val sy = y + sin(ang) * d
        val xi = sx.toInt(); val yi = sy.toInt()
        if (!inBounds(xi, yi)) return
        val t = tileAt(xi, yi)
        if (!sp.flies && !sp.swims && (t == Tile.DEEP || t == Tile.MOUNTAIN || t == Tile.LAVA)) return
        if (sp.swims && t == Tile.MOUNTAIN) return
        if (spawn(sp, sx, sy, true) != null) {
            sound("pop")
            val f = fxs()
            if (f.size < 60) f.add(Fx(Fx.SPAWN, sx, sy, 0.9f, 1.2f, sp.color))
        }
    }

    fun applyPower(id: String, x: Float, y: Float, r: Int) {
        when (id) {
            // ---- terrain
            "deep" -> terrain(x, y, r, Tile.DEEP, false)
            "shallow" -> terrain(x, y, r, Tile.SHALLOW, false)
            "sand" -> terrain(x, y, r, Tile.SAND, false)
            "soil" -> terrain(x, y, r, Tile.SOIL, false)
            "grass" -> terrain(x, y, r, Tile.GRASS, true)
            "forest" -> terrain(x, y, r, Tile.FOREST, true, 0.5f)
            "hills" -> terrain(x, y, r, Tile.HILLS, false)
            "mountain" -> terrain(x, y, r, Tile.MOUNTAIN, false)
            "snow" -> terrain(x, y, r, Tile.SNOW, true)
            "lava" -> terrain(x, y, r, Tile.LAVA, false)
            "pickaxe" -> paint(x, y, r) { i, t ->
                val nt = when (t) {
                    Tile.MOUNTAIN, Tile.SNOW -> Tile.HILLS
                    Tile.HILLS, Tile.LAVA -> Tile.SOIL
                    else -> t
                }
                if (nt != t) setTile(i, nt)
            }
            "sickle" -> paint(x, y, r) { i, t -> if (t == Tile.FOREST) setTile(i, Tile.GRASS) }
            "sponge" -> paint(x, y, r) { i, t ->
                if (t == Tile.SHALLOW || t == Tile.DEEP) setTile(i, Tile.SAND)
                else if (t == Tile.LAVA) setTile(i, Tile.HILLS)
                fire[i] = 0
            }
            "eraser" -> {
                paint(x, y, r) { i, _ -> removeBuilding(i); fire[i] = 0 }
                forEachNear(x, y, r + 0.5f) { kill(it) }
            }
            // ---- life
            "bless" -> forEachNear(x, y, r + 1.5f) {
                it.hp = it.sp.hp; it.hunger = 1f; it.burning = 0f; it.infected = -1f; it.mad = 0f
            }.also { fxs().add(Fx(Fx.RING, x, y, 0.9f, r + 1.5f)); sound("bless") }
            "curse" -> { forEachNear(x, y, r + 1.5f) { damage(it, it.sp.hp * 0.5f, null); it.mad = 10f }; fxs().add(Fx(Fx.CURSE, x, y, 1.0f, r + 1.5f)) }
            "friendship" -> {
                val ks = HashSet<Int>()
                forEachNear(x, y, r + 4f) { if (it.kingdomId >= 0) ks.add(it.kingdomId) }
                wars.removeAll { w2 -> ks.contains((w2 / 100000L).toInt()) || ks.contains((w2 % 100000L).toInt()) }
                forEachNear(x, y, r + 2f) { it.breedCd = 0f; it.hunger = min(1f, it.hunger + 0.3f) }
                fxs().add(Fx(Fx.RING, x, y, 0.9f, r + 3f, 0xFFFF80B0.toInt()))
            }
            "spite" -> {
                val ks = ArrayList<Int>()
                forEachNear(x, y, r + 6f) { if (it.kingdomId >= 0 && !ks.contains(it.kingdomId)) ks.add(it.kingdomId) }
                if (ks.size >= 2 && wars.add(warKey(ks[0], ks[1]))) {
                    log("${kingdomById(ks[0])?.name} declared war on ${kingdomById(ks[1])?.name}")
                }
                forEachNear(x, y, r + 2f) { it.mad = 12f }
            }
            "madness" -> forEachNear(x, y, r + 1.5f) { it.mad = 20f }
            "plague" -> forEachNear(x, y, r + 1.5f) { if (it.sp.kind != Kind.MONSTER) it.infected = 10f }
            // ---- nature
            "heat" -> paint(x, y, r) { i, t ->
                if (t == Tile.SNOW) setTile(i, Tile.SOIL)
                else if (t == Tile.SHALLOW && rnd.nextFloat() < 0.1f) setTile(i, Tile.SAND)
                else if (Tile.isFlammable(t) && rnd.nextFloat() < 0.1f) ignite(i)
            }
            "cold" -> paint(x, y, r) { i, t ->
                if ((t == Tile.GRASS || t == Tile.SOIL || t == Tile.WASTE || t == Tile.SAND) && rnd.nextFloat() < 0.6f) setTile(i, Tile.SNOW)
                else if (t == Tile.LAVA) setTile(i, Tile.HILLS)
                fire[i] = 0
            }
            "lightning" -> lightning(x, y)
            "rain" -> zones.add(Zone(Zone.RAIN, x, y, 8f, 25f))
            "tornado" -> zones.add(Zone(Zone.TORNADO, x, y, 3f, 25f).also {
                it.vx = rnd.nextFloat() * 2f - 1f; it.vy = rnd.nextFloat() * 2f - 1f
            })
            "fire" -> paint(x, y, r) { i, t ->
                if (rnd.nextFloat() < 0.7f) { if (Tile.isFlammable(t) || bld[i] > 0) ignite(i) else if (!Tile.isWater(t)) igniteForce(i, 6) }
            }.also { forEachNear(x, y, r + 0.5f) { it.burning = 5f } }
            "earthquake" -> {
                fxs().add(Fx(Fx.QUAKE, x, y, 1.6f, 8f))
                paint(x, y, 8) { i, t ->
                    if (rnd.nextFloat() < 0.5f) {
                        removeBuildingChance(i, 0.8f)
                        when (t) {
                            Tile.MOUNTAIN -> setTile(i, Tile.HILLS)
                            Tile.HILLS -> setTile(i, Tile.SOIL)
                            Tile.SOIL, Tile.GRASS, Tile.SAND -> if (rnd.nextFloat() < 0.12f) setTile(i, Tile.SHALLOW)
                        }
                    }
                }
                forEachNear(x, y, 8f) { damage(it, 20f, null) }
            }
            "meteor" -> zones.add(Zone(Zone.METEOR, x, y, 1f, 0.9f))
            "acid" -> zones.add(Zone(Zone.ACID, x, y, 6f, 14f))
            "volcano" -> { zones.add(Zone(Zone.VOLCANO, x, y, 3.5f, 16f)); sound("boom") }
            // ---- destruction
            "tnt" -> explode(x, y, 4f, 0.2f, true, 150f)
            "bomb" -> explode(x, y, 8f, 0.3f, true, 300f)
            "atomic" -> explode(x, y, 22f, 0.8f, true, 1000f)
            "napalm" -> {
                paint(x, y, 6) { i, _ -> igniteForce(i, 60 + rnd.nextInt(40)) }
                forEachNear(x, y, 6f) { it.burning = 8f }
                fxs().add(Fx(Fx.EXPLOSION, x, y, 0.8f, 6f))
            }
            "blackhole" -> zones.add(Zone(Zone.BLACKHOLE, x, y, 2f, 8f))
            // ---- other
            "magnet" -> forEachNear(x, y, r + 5f) {
                val dx = x - it.x; val dy = y - it.y
                val d = sqrt(dx * dx + dy * dy)
                if (d > 0.4f && !it.sp.flies) { it.x += dx / d * 0.45f; it.y += dy / d * 0.45f }
                it.hasTarget = false
            }
            "feast" -> {
                forEachNear(x, y, r + 3f) { it.hunger = min(1f, it.hunger + 0.5f) }
                paint(x, y, r + 2) { i, t -> if ((t == Tile.SOIL || t == Tile.WASTE) && rnd.nextFloat() < 0.5f) setTile(i, Tile.GRASS) }
                fxs().add(Fx(Fx.RING, x, y, 0.8f, r + 3f))
            }
            "finger" -> creatureAt(x, y, 2.5f)?.let { kill(it); fxs().add(Fx(Fx.RING, it.x, it.y, 0.6f, 2f)) }
            else -> {
                if (id.startsWith("spawn:")) spawnAt(id.substring(6), x, y, r)
            }
        }
    }
}
