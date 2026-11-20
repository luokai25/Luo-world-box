package com.luokai.luoworldbox

import com.luokai.luoworldbox.core.Sp

enum class Tab(val title: String, val icon: String) {
    SHAPING("World Shaping", "⛰"),
    LIFE("Noosphere and Life", "🏘"),
    CREATURES("Animals, Creatures and Monsters", "🐑"),
    NATURE("Nature and Disasters", "🌪"),
    DESTRUCTION("Destruction and Chaos", "💣"),
    OTHER("Other various powers", "✨")
}

/**
 * repeatMs: 0 = fires once per touch, otherwise fires again every repeatMs while the finger is held.
 * brush: whether the brush size applies (and the brush circle is drawn).
 */
class Power(
    val id: String,
    val name: String,
    val icon: String,
    val tab: Tab,
    val repeatMs: Int,
    val brush: Boolean,
    val inspect: Boolean = false
)

object Powers {
    val ALL: List<Power> = buildList {
        // ---- World shaping
        add(Power("deep", "Deep Ocean", "🌊", Tab.SHAPING, 30, true))
        add(Power("shallow", "Ocean", "💧", Tab.SHAPING, 30, true))
        add(Power("sand", "Sand", "🏖", Tab.SHAPING, 30, true))
        add(Power("soil", "Soil", "🟫", Tab.SHAPING, 30, true))
        add(Power("grass", "Grass", "🌱", Tab.SHAPING, 30, true))
        add(Power("forest", "Forest", "🌲", Tab.SHAPING, 30, true))
        add(Power("hills", "Hills", "⛰", Tab.SHAPING, 30, true))
        add(Power("mountain", "Mountain", "🗻", Tab.SHAPING, 30, true))
        add(Power("snow", "Snow", "❄", Tab.SHAPING, 30, true))
        add(Power("lava", "Lava", "🌋", Tab.SHAPING, 30, true))
        add(Power("pickaxe", "Pickaxe", "⛏", Tab.SHAPING, 60, true))
        add(Power("sickle", "Sickle", "🌾", Tab.SHAPING, 60, true))
        add(Power("sponge", "Sponge", "🧽", Tab.SHAPING, 40, true))
        add(Power("eraser", "Eraser", "🧹", Tab.SHAPING, 60, true))

        // ---- Life / noosphere
        add(Power("inspect", "Inspect", "🔍", Tab.LIFE, 0, false, inspect = true))
        add(Power("bless", "Bless", "😇", Tab.LIFE, 200, true))
        add(Power("curse", "Curse", "😈", Tab.LIFE, 300, true))
        add(Power("friendship", "Friendship", "💞", Tab.LIFE, 400, true))
        add(Power("spite", "Spite", "💢", Tab.LIFE, 500, true))
        add(Power("madness", "Madness", "🤪", Tab.LIFE, 300, true))
        add(Power("plague", "Plague", "☣", Tab.LIFE, 300, true))

        // ---- Creatures (spawn:<species key>)
        for (s in Sp.all) {
            add(Power("spawn:${s.key}", s.name, s.emoji, Tab.CREATURES, 140, true))
        }

        // ---- Nature & disasters
        add(Power("heat", "Heat", "🌡", Tab.NATURE, 100, true))
        add(Power("cold", "Cold", "🧊", Tab.NATURE, 100, true))
        add(Power("lightning", "Lightning", "⚡", Tab.NATURE, 250, false))
        add(Power("rain", "Rain", "🌧", Tab.NATURE, 0, false))
        add(Power("tornado", "Tornado", "🌪", Tab.NATURE, 0, false))
        add(Power("fire", "Fire", "🔥", Tab.NATURE, 100, true))
        add(Power("earthquake", "Earthquake", "🫨", Tab.NATURE, 0, false))
        add(Power("meteor", "Meteor", "☄", Tab.NATURE, 400, false))
        add(Power("acid", "Acid Rain", "🧪", Tab.NATURE, 0, false))

        // ---- Destruction
        add(Power("tnt", "TNT", "🧨", Tab.DESTRUCTION, 350, false))
        add(Power("bomb", "Bomb", "💣", Tab.DESTRUCTION, 500, false))
        add(Power("atomic", "Atomic Bomb", "☢", Tab.DESTRUCTION, 0, false))
        add(Power("napalm", "Napalm", "🛢", Tab.DESTRUCTION, 400, false))
        add(Power("blackhole", "Black Hole", "⚫", Tab.DESTRUCTION, 0, false))

        // ---- Other
        add(Power("magnet", "Magnet", "🧲", Tab.OTHER, 30, true))
        add(Power("feast", "Feast", "🍄", Tab.OTHER, 200, true))
        add(Power("finger", "Divine Finger", "👆", Tab.OTHER, 200, false))
    }

    fun forTab(t: Tab): List<Power> = ALL.filter { it.tab == t }
}
