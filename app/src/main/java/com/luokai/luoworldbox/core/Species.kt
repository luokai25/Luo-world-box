package com.luokai.luoworldbox.core

enum class Kind { SAPIENT, HERBIVORE, CARNIVORE, MONSTER }

class Species(
    val id: Int,
    val key: String,
    val name: String,
    val kind: Kind,
    val emoji: String,
    val color: Int,
    val speed: Float,      // tiles per second
    val maxAge: Float,     // years
    val adultAge: Float,   // years
    val hp: Float,
    val dmg: Float,
    val vision: Float,
    val swims: Boolean,
    val flies: Boolean,
    val climbs: Boolean
) {
    val hungerRate: Float
        get() = when (kind) {
            Kind.SAPIENT -> 0.006f
            Kind.HERBIVORE -> 0.010f
            Kind.CARNIVORE -> 0.012f
            Kind.MONSTER -> 0.003f
        }
}

object Sp {
    val all = ArrayList<Species>()

    private fun c(rgb: Int): Int = rgb or (0xFF shl 24)

    private fun r(
        key: String, name: String, kind: Kind, emoji: String, rgb: Int,
        speed: Float, maxAge: Float, adult: Float, hp: Float, dmg: Float,
        vision: Float = 8f, swims: Boolean = false, flies: Boolean = false, climbs: Boolean = false
    ): Species {
        val s = Species(all.size, key, name, kind, emoji, c(rgb), speed, maxAge, adult, hp, dmg, vision, swims, flies, climbs)
        all.add(s)
        return s
    }

    val HUMAN = r("human", "Human", Kind.SAPIENT, "🧑", 0xE8B88A, 1.6f, 70f, 12f, 100f, 9f, 9f)
    val ELF = r("elf", "Elf", Kind.SAPIENT, "🧝", 0x9BE08A, 1.9f, 140f, 18f, 90f, 8f, 10f)
    val ORC = r("orc", "Orc", Kind.SAPIENT, "👹", 0x6FA84A, 1.7f, 60f, 10f, 140f, 12f, 8f)
    val DWARF = r("dwarf", "Dwarf", Kind.SAPIENT, "🧔", 0xC98A4B, 1.3f, 110f, 16f, 120f, 10f, 8f, climbs = true)

    val SHEEP = r("sheep", "Sheep", Kind.HERBIVORE, "🐑", 0xF2F2F2, 1.1f, 14f, 1.5f, 40f, 0f, 6f)
    val COW = r("cow", "Cow", Kind.HERBIVORE, "🐄", 0xE0D2BE, 1.0f, 18f, 2f, 70f, 0f, 6f)
    val CHICKEN = r("chicken", "Chicken", Kind.HERBIVORE, "🐔", 0xF5E3A0, 1.3f, 8f, 1f, 15f, 0f, 6f)
    val RABBIT = r("rabbit", "Rabbit", Kind.HERBIVORE, "🐇", 0xD8C8B8, 2.0f, 7f, 1f, 12f, 0f, 7f)
    val PIG = r("pig", "Pig", Kind.HERBIVORE, "🐖", 0xF0A8A8, 1.1f, 15f, 2f, 50f, 0f, 6f)
    val DEER = r("deer", "Deer", Kind.HERBIVORE, "🦌", 0xB07A4A, 2.3f, 16f, 2f, 55f, 0f, 8f)
    val GOAT = r("goat", "Goat", Kind.HERBIVORE, "🐐", 0xE8E4D8, 1.4f, 15f, 1.5f, 45f, 0f, 6f, climbs = true)
    val DOG = r("dog", "Dog", Kind.HERBIVORE, "🐕", 0xB88A5A, 2.2f, 15f, 2f, 55f, 6f, 6f)
    val CRAB = r("crab", "Crab", Kind.HERBIVORE, "🦀", 0xE05A3A, 1.0f, 10f, 1f, 30f, 4f, 5f, swims = true)

    val CAT = r("cat", "Cat", Kind.CARNIVORE, "🐈", 0xD89A52, 2.4f, 14f, 1.5f, 35f, 4f, 8f)
    val FOX = r("fox", "Fox", Kind.CARNIVORE, "🦊", 0xE8742A, 2.4f, 12f, 1.5f, 45f, 6f, 9f)
    val WOLF = r("wolf", "Wolf", Kind.CARNIVORE, "🐺", 0x8E8E96, 2.8f, 15f, 2f, 80f, 10f, 10f)
    val BEAR = r("bear", "Bear", Kind.CARNIVORE, "🐻", 0x6B4A2E, 1.5f, 25f, 3f, 160f, 18f, 9f)

    val ZOMBIE = r("zombie", "Zombie", Kind.MONSTER, "🧟", 0x7FA06A, 0.9f, 9999f, 1f, 90f, 8f, 10f)
    val DRAGON = r("dragon", "Dragon", Kind.MONSTER, "🐉", 0xC0392B, 3.2f, 300f, 5f, 400f, 30f, 14f, flies = true)

    fun byKey(key: String): Species? = all.firstOrNull { it.key == key }
}
