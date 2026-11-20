package com.luokai.luoworldbox.core

import kotlin.math.max

/** Text reports shown in the statistics window. */
object Report {
    fun stats(w: World): String {
        var sap = 0; var mobs = 0; var infected = 0
        for (c in w.creatures) {
            if (!c.alive) continue
            if (c.sp.kind == Kind.SAPIENT) sap++ else mobs++
            if (c.infected >= 0f) infected++
        }
        var veg = 0
        for (i in 0 until w.w * w.h) if (w.tiles[i].toInt() == Tile.FOREST) veg++
        var buildings = 0
        for (v in w.villages) buildings += v.houses.size
        val a = w.age
        return buildString {
            appendLine(a.name)
            appendLine(a.blurb)
            appendLine("Year ${w.year}, ${w.monthName} — ${w.moonsLeft} moons until the next age")
            appendLine()
            appendLine("Sapient population  $sap")
            appendLine("Mobs                $mobs")
            appendLine("Total deaths        ${w.totalDeaths}")
            appendLine("Infected            $infected")
            appendLine("Buildings           $buildings")
            appendLine("Vegetation          $veg")
            appendLine("Kingdoms            ${w.kingdoms.size}")
            appendLine("Villages            ${w.villages.size}")
        }
    }

    fun kingdoms(w: World): String {
        if (w.kingdoms.isEmpty()) return "No kingdoms yet.\nSpawn some humans, elves, orcs or dwarves and give them time."
        return buildString {
            for (k in w.kingdoms) {
                val sp = Sp.all[k.speciesId]
                appendLine("${k.name} (${sp.name}s)  — founded year ${k.founded}")
                appendLine("  King: ${k.kingName}")
                appendLine("  Population ${k.pop}, villages ${k.villages}")
                val enemies = w.kingdoms.filter { it.id != k.id && w.atWar(k.id, it.id) }
                appendLine("  At war with: " + if (enemies.isEmpty()) "nobody" else enemies.joinToString { it.name })
                for (v in w.villages) if (v.kingdomId == k.id) appendLine("    • ${v.name}: ${v.pop} people, ${v.houses.size} houses")
                appendLine()
            }
        }
    }

    fun pyramid(w: World): String {
        val male = IntArray(11)
        val female = IntArray(11)
        for (c in w.creatures) {
            if (!c.alive || c.sp.kind != Kind.SAPIENT) continue
            val b = (c.age / 10f).toInt().coerceIn(0, 10)
            if (c.male) male[b]++ else female[b]++
        }
        val top = max(1, (male.maxOrNull() ?: 1).coerceAtLeast(female.maxOrNull() ?: 1))
        return buildString {
            appendLine("Population pyramid (age)      male | female")
            for (b in 10 downTo 0) {
                val m = male[b] * 12 / top
                val f = female[b] * 12 / top
                appendLine(String.format("%3d+  %12s | %-12s  %d/%d", b * 10, "█".repeat(m), "█".repeat(f), male[b], female[b]))
            }
        }
    }

    private fun mood(w: World, c: Creature): Int {
        var m = 0.4f + 0.4f * c.hunger
        if (c.villageId >= 0) m += 0.2f
        if (c.kingdomId >= 0 && w.kingdoms.any { it.id != c.kingdomId && w.atWar(c.kingdomId, it.id) }) m -= 0.15f
        if (c.burning > 0f) m -= 0.3f
        return (m.coerceIn(0f, 1f) * 100).toInt()
    }

    fun interesting(w: World): String {
        val sap = w.creatures.filter { it.alive && it.sp.kind == Kind.SAPIENT }
        if (sap.isEmpty()) return "Nobody interesting lives here yet."
        return buildString {
            appendLine("Oldest")
            for (c in sap.sortedByDescending { it.age }.take(3)) appendLine("  ${c.name} (${c.sp.name}, age ${c.age.toInt()})")
            appendLine()
            appendLine("Happiest")
            for (c in sap.sortedByDescending { mood(w, it) }.take(3)) appendLine("  ${c.name} (${c.sp.name}, mood ${mood(w, c)})")
            appendLine()
            appendLine("Deadliest")
            for (c in sap.sortedByDescending { it.kills }.take(3)) appendLine("  ${c.name} (${c.sp.name}, kills ${c.kills})")
        }
    }

    fun full(w: World): String = stats(w) + "\n— Kingdoms —\n" + kingdoms(w) + "\n— Population —\n" + pyramid(w) + "\n— Interesting creatures —\n" + interesting(w)

    fun creature(w: World, c: Creature): String {
        val k = w.kingdomById(c.kingdomId)
        val v = w.villageById(c.villageId)
        return buildString {
            appendLine("${c.sp.emoji} ${if (c.name.isEmpty()) c.sp.name else c.name} (${c.sp.name}, ${if (c.male) "male" else "female"})")
            appendLine("Age ${c.age.toInt()} / ~${(c.sp.maxAge * c.lifeFactor).toInt()}${if (c.isAdult) "" else "  (child)"}")
            appendLine("Health ${c.hp.toInt()} / ${c.sp.hp.toInt()}")
            appendLine("Hunger ${((1f - c.hunger) * 100).toInt()}%")
            if (c.sp.kind == Kind.SAPIENT) {
                appendLine("Kingdom ${k?.name ?: "none"}${if (k != null && k.kingId == c.id) "  (King)" else ""}")
                appendLine("Village ${v?.name ?: "none"}")
                appendLine("Mood ${mood(w, c)}   Kills ${c.kills}")
            }
            if (c.infected >= 0f) appendLine("Infected!")
            if (c.burning > 0f) appendLine("On fire!")
        }
    }
}
