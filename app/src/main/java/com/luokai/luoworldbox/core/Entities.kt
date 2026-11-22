package com.luokai.luoworldbox.core

import java.io.Serializable
import java.util.Random

class Creature(
    val id: Int,
    var spId: Int,
    var x: Float,
    var y: Float,
    val male: Boolean
) : Serializable {
    val sp: Species get() = Sp.all[spId]

    var name: String = ""
    var hp: Float = Sp.all[spId].hp
    var age: Float = 0f            // years
    var lifeFactor: Float = 1f
    var hunger: Float = 0.8f       // 1 = full
    var tx: Float = 0f
    var ty: Float = 0f
    var hasTarget: Boolean = false
    var think: Float = 0f
    var atkCd: Float = 0f
    var breedCd: Float = 10f
    var kingdomId: Int = -1
    var villageId: Int = -1
    var infected: Float = -1f      // seconds until turning into a zombie, -1 = healthy
    var burning: Float = 0f
    var mad: Float = 0f
    var kills: Int = 0
    var alive: Boolean = true
    var fleeing: Boolean = false
    var faceRight: Boolean = true
    // daily life of villagers
    var job: Int = 0
    var act: Int = 0
    var actTimer: Float = 0f
    var workTile: Int = -1
    var homeIdx: Int = -1
    var inside: Boolean = false
    var sleeping: Boolean = false
    var carry: Int = 0
    var chase: Creature? = null

    val isAdult: Boolean get() = age >= sp.adultAge
}

class Village(val id: Int, val name: String, val speciesId: Int, var cx: Float, var cy: Float) : Serializable {
    var kingdomId: Int = -1
    val houses = ArrayList<Int>()   // tile indexes
    var food: Float = 10f
    var pop: Int = 0
    var emptyTime: Float = 0f
    var buildCd: Float = 0f
    val fields = ArrayList<Int>()   // farm tiles
    var wood: Int = 6
    var ore: Int = 0
    var tech: Int = 0               // tool level, makes work faster
    var site: Int = -1              // house under construction (tile index)
    var siteProgress: Int = 0
    var fieldCd: Float = 0f
    val radius: Float get() = 5f + Math.sqrt(houses.size.toDouble()).toFloat() * 2.2f
}

class Kingdom(val id: Int, val name: String, val speciesId: Int, val color: Int, val founded: Int) : Serializable {
    var kingId: Int = -1
    var kingName: String = "-"
    var pop: Int = 0
    var villages: Int = 0
    var capitalId: Int = -1
}

/** Area effects: rain, acid, tornado, black hole and incoming meteors. */
class Zone(val type: Int, var x: Float, var y: Float, var r: Float, var life: Float) : Serializable {
    var vx: Float = 0f
    var vy: Float = 0f
    var maxLife: Float = life

    companion object {
        const val RAIN = 0
        const val ACID = 1
        const val TORNADO = 2
        const val BLACKHOLE = 3
        const val METEOR = 4
        const val VOLCANO = 5
    }
}

/** Short-lived visual effects (never saved). */
class Fx(val type: Int, val x: Float, val y: Float, val life: Float, val r: Float, val color: Int = 0) {
    var t: Float = 0f
    var emitted: Boolean = false

    companion object {
        const val EXPLOSION = 0
        const val LIGHTNING = 1
        const val RING = 2
        const val SPAWN = 3
        const val POOF = 4
        const val QUAKE = 5
        const val CURSE = 6
    }
}

object Act {
    const val IDLE = 0
    const val GOTO_WORK = 1
    const val WORK = 2
    const val RETURN = 3
    const val INSIDE = 4
}

object Job {
    const val NONE = 0
    const val WOOD = 1
    const val FARM = 2
    const val MINE = 3
    const val BUILD = 4
    const val HUNT = 5
    val NAMES = arrayOf("none", "Woodcutter", "Farmer", "Miner", "Builder", "Hunter")
}

class Laws : Serializable {
    var aging = true
    var hunger = true
    var reproduction = true
    var fireSpread = true
    var wars = true
    var immortal = false
    var exploding = false
}

class AgeDef(
    val name: String,
    val blurb: String,
    val growth: Float,
    val fire: Float,
    val birth: Float
)

object WorldAges {
    val ALL = listOf(
        AgeDef("Age of Hope", "A calm beginning. Nature grows at its normal pace.", 1.0f, 1.0f, 1.0f),
        AgeDef("Age of Sun", "Scorching days. Fires spread faster and plants grow slower.", 0.7f, 1.7f, 1.0f),
        AgeDef("Age of Dark", "Dim skies. Growth slows and the dead sometimes rise.", 0.6f, 1.0f, 0.9f),
        AgeDef("Age of Tears", "Endless storms water the land. Plants thrive.", 1.6f, 0.4f, 1.0f),
        AgeDef("Age of Moon", "Quiet nights. Births become more frequent.", 1.0f, 1.0f, 1.5f),
        AgeDef("Age of Chaos", "The sky is restless. Disasters strike at random.", 1.0f, 1.2f, 1.0f),
        AgeDef("Age of Wonders", "Fortune smiles. Creatures are randomly blessed.", 1.2f, 0.8f, 1.2f)
    )
}

object Names {
    private val syl = arrayOf(
        "ith", "ar", "ea", "golf", "wo", "gul", "mar", "dik", "pel", "gen", "da", "ching", "bon", "ob",
        "kar", "tor", "vel", "zun", "ra", "mo", "sil", "ne", "bro", "han", "fey", "lor", "ul", "ke", "dru"
    )

    fun make(r: Random, parts: Int = 2 + r.nextInt(2)): String {
        val sb = StringBuilder()
        repeat(parts) { sb.append(syl[r.nextInt(syl.size)]) }
        return sb.toString().replaceFirstChar { it.uppercase() }
    }
}
