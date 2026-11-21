# Reverse engineering notes

Source: `Worldbox.pdf`, 90 screenshots of the reference Android game (build string
`android 0.51.4-725`). Everything below was read from those screenshots. Only the name changes:
the game is called **Luo World Box**. Code and art are original.

Legend: ✅ built · 🟡 built in a simpler form · ⬜ not built yet

## Screens

| # | Screenshot content | Status |
|---|---|---|
| 1 | Loading screen: planet, "Splitting atoms...", "Make sure to save your world before you go!" | ✅ |
| 2 | Main view: top-right buttons (world menu, gift box) | ✅ menu · the gift box is dropped (it is a reward/ad slot; this game has none) |
| 3 | Bottom bar: 6 category tabs, red undo/deselect, pause, hourglass speed, two-row power grid that scrolls sideways | ✅ |
| 4 | Padlock icons on many powers | ✅ everything is unlocked |
| 5 | Info strip: Age of Hope, year, month name, "moons until next age", Sapient Population / Mobs / Total Deaths / Infected / Buildings / Vegetation | ✅ status line + statistics window |
| 6 | Settings row: gear, chat, controller, hide-eye, world info, laws book, history book, age clock, radar map, save disk | 🟡 one ☰ menu: statistics, population graph, world history, laws, ages, new world, save, load, borders, help, about |
| 7 | New World: grid of 12 templates | ✅ 12 original templates |
| 8 | Template Configuration: size 3/7 "Standard", Random Shapes, Noise Main/Details/Bonus, Random Biomes, Mountain Edges, Spawn Vegetation, Spawn Resources, Center Lake/Land, Round/Square Edges, Tree Ring, Low/High Ground, Remove Mountains, Reset, CREATE | ✅ all options (Reset = pick the template again) |
| 9 | World Ages wheel (Age of Hope, locked "?" ages) | 🟡 seven ages with real effects, shown as a list; "Skip to next age" button |
| 10 | Kingdom window: Statistics graph, Population Pyramid, Interesting Creatures (oldest, happiest), Villages | 🟡 text reports plus a separate population-over-time graph |
| 11 | Kingdom Editor: traits (Tribute, Local Tax, Fate) | ⬜ |
| 12 | Metas: Alliance, Capital, Clan, Culture, Language, Religion, Subspecies breakdowns | 🟡 capital and species only |
| 13 | King overview and kingdom card (motto, happiness, diplomacy, heir) | 🟡 king, population, villages, wars |
| 14 | Map: kingdom border tint, selected-kingdom highlight, toasts "New kingdom … was founded", "New village … has been founded" | 🟡 borders, kingdom names and event toasts (founding, wars, peace, new king, age changes); no selected-kingdom highlight |
| 15 | Zoomed-in tiles with pixel trees, houses, creatures with health bars | ✅ |
| 16 | Info / news carousel (World History, Heroes, Epic Items, Wars, Plans and Plots, Alliances, Religions, Languages, Cultures, Clans, Kingdoms, Villages, Families, Subspecies, Disasters, World Laws) and tips: one finger moves the camera, two fingers zoom | 🟡 tips are in the Welcome screen; Wars and Villages and Kingdoms and Disasters and World Laws exist, the rest ⬜ |

## Power categories

| Tab | Reference title | Built |
|---|---|---|
| 1 | World Shaping | Deep Ocean, Ocean, Sand, Soil, Grass, Forest, Hills, Mountain, Snow, Lava, Pickaxe, Sickle, Sponge, Eraser |
| 2 | Noosphere and Life | Inspect, Bless, Curse, Friendship, Spite, Madness, Plague |
| 3 | Animals, Creatures and Monsters | Human, Elf, Orc, Dwarf, Sheep, Cow, Chicken, Rabbit, Pig, Dog, Crab, Cat, Fox, Wolf, Bear, Zombie, Dragon |
| 4 | Nature and Disasters | Heat, Cold, Lightning, Rain, Tornado, Fire, Earthquake, Meteor, Acid Rain |
| 5 | Destruction and Chaos | TNT, Bomb, Atomic Bomb, Napalm, Black Hole |
| 6 | Other various powers | Magnet, Feast, Divine Finger |

Nature also has Volcano. Creature icons in the power bar are the in-game sprites; terrain icons are tile swatches.

The reference has many more icons per tab (the scroll rows are long). More powers are listed under "Next".

## Simulation rules implemented

- Tiles: deep/shallow water, sand, soil, grass, forest, hills, mountain, snow, lava, burnt waste; ore specks on rock.
- Creatures: age, hunger, health, breeding, hunting, fleeing, drowning, burning, infection → zombie.
- Villages: founded by two or more homeless adults, build houses as they grow, store food, empty villages vanish.
- Kingdoms: one per species cluster, king succession, border tint, diplomacy; wars start between close kingdoms, fighters attack enemy kingdoms.
- Fire spreads through grass, forest and houses; rain puts it out; fire leaves burnt land that slowly recovers.
- Disasters: lightning, rain and acid clouds, moving tornadoes, meteors, earthquakes, black holes, bombs.
- World laws: aging, hunger, reproduction, fire spread, wars, immortality, exploding creatures.
- Seven ages (Hope, Sun, Dark, Tears, Moon, Chaos, Wonders) each change growth, fire, births or trigger events.
- Saves: three slots plus an autosave when you leave.

## Next

1. Kingdom window as tabs (the graph and history log already exist as separate dialogs).
2. Tap a kingdom to select and highlight it.
3. Subspecies, cultures, languages, religions, clans, families.
4. Heroes (favorites), epic items, plots, alliances.
5. More powers per tab (more animals and monsters, more disasters, tools, weather).
6. Hand-drawn sprite sheets (the current sprites are generated in code).
7. Music, more sound effects, weather visuals.
