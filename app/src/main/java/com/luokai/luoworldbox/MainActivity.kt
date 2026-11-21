package com.luokai.luoworldbox

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.luokai.luoworldbox.core.Climate
import com.luokai.luoworldbox.core.Report
import com.luokai.luoworldbox.core.SaveStore
import com.luokai.luoworldbox.core.Template
import com.luokai.luoworldbox.core.Templates
import com.luokai.luoworldbox.core.WorldAges
import com.luokai.luoworldbox.core.WorldConfig
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity(), GameView.Host {

    private lateinit var view: GameView
    private lateinit var sfx: Sfx
    private var openDialogs = 0

    private val prefs by lazy { getSharedPreferences("luo", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        view = GameView(this, this)
        sfx = Sfx(this)
        view.sfx = sfx
        view.showBorders = prefs.getBoolean("borders", true)
        view.showMini = prefs.getBoolean("mini", true)
        sfx.enabled = prefs.getBoolean("sound", true)
        setContentView(view)
        enterImmersive()

        Thread {
            val saved = SaveStore.load(filesDir, SaveStore.AUTO)
            if (saved != null) {
                view.loadWorld(saved)
            } else {
                view.newWorld(Templates.ALL[0].cfg.copyConfig())
                runOnUiThread { showWelcome() }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        enterImmersive()
        view.appPaused = openDialogs > 0
    }

    override fun onPause() {
        super.onPause()
        view.appPaused = true
        view.saveNowBlocking()
    }

    override fun onDestroy() {
        super.onDestroy()
        sfx.release()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        openMenu()
    }

    @Suppress("DEPRECATION")
    private fun enterImmersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------ dialog plumbing
    private fun present(b: AlertDialog.Builder): AlertDialog {
        val d = b.create()
        openDialogs++
        view.appPaused = true
        d.setOnDismissListener {
            openDialogs = (openDialogs - 1).coerceAtLeast(0)
            view.appPaused = openDialogs > 0
            enterImmersive()
        }
        d.show()
        return d
    }

    override fun showInfo(title: String, text: String) {
        runOnUiThread {
            val tv = TextView(this).apply {
                this.text = text
                typeface = Typeface.MONOSPACE
                textSize = 12f
                setPadding(dp(18), dp(10), dp(18), dp(10))
            }
            val sv = ScrollView(this).apply { addView(tv) }
            present(AlertDialog.Builder(this).setTitle(title).setView(sv).setPositiveButton("Close", null))
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------ menu
    override fun openMenu() {
        runOnUiThread {
            val entries = ArrayList<Pair<String, () -> Unit>>()
            entries.add("Statistics and Kingdoms" to { view.queryGame({ Report.full(it) }) { showInfo("Statistics", it) } })
            entries.add("Population Graph" to { showGraph() })
            entries.add("World History" to {
                view.queryGame({ w ->
                    if (w.history.isEmpty()) "Nothing has happened yet." else w.history.asReversed().joinToString("\n\n")
                }) { showInfo("World History", it) }
            })
            entries.add("World Laws" to { showLaws() })
            entries.add("World Ages" to { showAges() })
            entries.add("New World" to { showTemplates() })
            entries.add("Save World" to { showSlots(true) })
            entries.add("Load World" to { showSlots(false) })
            entries.add((if (view.showBorders) "Kingdom borders: ON" else "Kingdom borders: OFF") to {
                view.showBorders = !view.showBorders
                prefs.edit().putBoolean("borders", view.showBorders).apply()
            })
            entries.add((if (view.showMini) "Minimap: ON" else "Minimap: OFF") to {
                view.showMini = !view.showMini
                prefs.edit().putBoolean("mini", view.showMini).apply()
            })
            entries.add((if (sfx.enabled) "Sound: ON" else "Sound: OFF") to {
                sfx.enabled = !sfx.enabled
                prefs.edit().putBoolean("sound", sfx.enabled).apply()
            })
            entries.add("How to play" to { showWelcome() })
            entries.add("About" to { showAbout() })
            present(AlertDialog.Builder(this).setTitle("Luo World Box")
                .setItems(entries.map { it.first }.toTypedArray()) { _, which -> entries[which].second() }
                .setNegativeButton("Back to game", null))
        }
    }

    private fun showWelcome() {
        val msg = "Luo World Box is a god simulator and sandbox.\n\n" +
            "• Pick a power from the bar at the bottom and touch the world to use it.\n" +
            "• Drag with one finger to move the camera, pinch with two fingers to zoom.\n" +
            "• The red ↩ button puts your finger back into move mode.\n" +
            "• Tap a creature or village in move mode to inspect it.\n" +
            "• The round button at the top left changes the brush size.\n" +
            "• Spawn humans, elves, orcs or dwarves and watch villages, kingdoms and wars appear.\n" +
            "• The ☰ button opens statistics, history, world laws, ages, save slots and settings.\n• Tap the minimap (top right) to jump around the world.\n\n" +
            "Everything is unlocked. There are no ads and no purchases. Your world is auto-saved when you leave."
        present(AlertDialog.Builder(this).setTitle("Welcome").setMessage(msg).setPositiveButton("Let's play", null))
    }

    private fun showAbout() {
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
        val msg = "Luo World Box $ver\n\nFree for everyone: no ads, no payments, no locked features.\n\n" +
            "An independent god-sim sandbox for Android. All code and art in this app were written for this project.\n\n" +
            "github.com/luokai25/Luo-world-box"
        present(AlertDialog.Builder(this).setTitle("About").setMessage(msg).setPositiveButton("Close", null))
    }

    // ------------------------------------------------------------------ graph
    private class GraphView(ctx: Context, private val data: List<Int>) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val d = ctx.resources.displayMetrics.density

        override fun onMeasure(w: Int, h: Int) {
            setMeasuredDimension(resolveSize((300 * d).toInt(), w), (220 * d).toInt())
        }

        override fun onDraw(c: Canvas) {
            val l = 36 * d; val t = 10 * d; val r = width - 10 * d; val b = height - 24 * d
            p.style = Paint.Style.STROKE; p.strokeWidth = d; p.color = 0xFF888888.toInt()
            c.drawRect(l, t, r, b, p)
            if (data.size < 2) {
                p.style = Paint.Style.FILL; p.textSize = 13 * d; p.color = 0xFFCCCCCC.toInt()
                c.drawText("Not enough data yet", l + 10 * d, (t + b) / 2, p)
                return
            }
            val top = maxOf(10, data.maxOrNull() ?: 10)
            p.style = Paint.Style.FILL; p.textSize = 11 * d; p.color = 0xFFCCCCCC.toInt()
            c.drawText("$top", 2 * d, t + 10 * d, p)
            c.drawText("0", 2 * d, b, p)
            c.drawText("time →", l, height - 6 * d, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f * d; p.color = 0xFF3DDC55.toInt()
            for (i in 1 until data.size) {
                val x0 = l + (r - l) * (i - 1) / (data.size - 1)
                val x1 = l + (r - l) * i / (data.size - 1)
                val y0 = b - (b - t) * data[i - 1] / top
                val y1 = b - (b - t) * data[i] / top
                c.drawLine(x0, y0, x1, y1, p)
            }
        }
    }

    private fun showGraph() {
        view.queryGame({ w -> w.popHistory.joinToString(",") }) { s ->
            val data = s.split(",").mapNotNull { it.toIntOrNull() }
            val box = LinearLayout(this).apply { setPadding(dp(14), dp(10), dp(14), dp(4)); addView(GraphView(this@MainActivity, data)) }
            present(AlertDialog.Builder(this).setTitle("Sapient population over time").setView(box).setPositiveButton("Close", null))
        }
    }

    // ------------------------------------------------------------------ laws / ages
    private fun showLaws() {
        view.queryGame({ w ->
            val l = w.laws
            listOf(l.aging, l.hunger, l.reproduction, l.fireSpread, l.wars, l.immortal, l.exploding)
                .joinToString("") { if (it) "1" else "0" }
        }) { bits ->
            val names = arrayOf(
                "Aging", "Hunger", "Reproduction", "Fire spreads", "Kingdoms wage wars",
                "Immortality (nobody dies of old age)", "Explosive creatures (they blow up when they die)"
            )
            val checked = BooleanArray(names.size) { bits.getOrNull(it) == '1' }
            present(AlertDialog.Builder(this).setTitle("World Laws")
                .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                    view.postGame { w ->
                        val l = w.laws
                        when (which) {
                            0 -> l.aging = isChecked
                            1 -> l.hunger = isChecked
                            2 -> l.reproduction = isChecked
                            3 -> l.fireSpread = isChecked
                            4 -> l.wars = isChecked
                            5 -> l.immortal = isChecked
                            6 -> l.exploding = isChecked
                        }
                    }
                }.setPositiveButton("Done", null))
        }
    }

    private fun showAges() {
        view.queryGame({ w -> "${w.ageIdx}|${w.moonsLeft}" }) { s ->
            val parts = s.split("|")
            val cur = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val left = parts.getOrNull(1) ?: "?"
            val sb = StringBuilder()
            for ((i, a) in WorldAges.ALL.withIndex()) {
                sb.append(if (i == cur) "▶ " else "   ").append(a.name).append("\n    ").append(a.blurb).append("\n\n")
            }
            sb.append("Moons until the next age: $left")
            present(AlertDialog.Builder(this).setTitle("World Ages").setMessage(sb.toString())
                .setPositiveButton("Close", null)
                .setNeutralButton("Skip to next age") { _, _ -> view.postGame { it.skipToNextAge() } })
        }
    }

    // ------------------------------------------------------------------ saves
    private fun showSlots(saving: Boolean) {
        val slots = arrayOf("slot1", "slot2", "slot3")
        val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val labels = Array(slots.size) { i ->
            val h = SaveStore.header(filesDir, slots[i])
            if (h == null) "Slot ${i + 1} — empty"
            else "Slot ${i + 1} — year ${h.year}, ${h.pop} sapients, ${h.size}×${h.size} (${fmt.format(Date(h.savedAt))})"
        }
        present(AlertDialog.Builder(this).setTitle(if (saving) "Save World" else "Load World")
            .setItems(labels) { _, which ->
                val slot = slots[which]
                if (saving) {
                    view.saveSlot(slot) { ok -> toast(if (ok) "World saved" else "Could not save") }
                } else {
                    Thread {
                        val w = SaveStore.load(filesDir, slot)
                        runOnUiThread {
                            if (w == null) toast("Nothing to load in that slot")
                            else { view.loadWorld(w); toast("World loaded") }
                        }
                    }.start()
                }
            }.setNegativeButton("Cancel", null))
    }

    // ------------------------------------------------------------------ new world
    private fun showTemplates() {
        val names = Templates.ALL.map { it.name }.toTypedArray()
        present(AlertDialog.Builder(this).setTitle("New World — choose a template")
            .setItems(names) { _, i -> showConfig(Templates.ALL[i]) }
            .setNegativeButton("Cancel", null))
    }

    private fun showConfig(t: Template) {
        val cfg = t.cfg.copyConfig()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        fun label(s: String) = TextView(this).apply { text = s; textSize = 14f; setPadding(0, dp(10), 0, 0) }
        fun seek(title: String, max: Int, get: () -> Int, set: (Int) -> Unit, show: (Int) -> String) {
            val tv = label("")
            fun upd() { tv.text = "$title: ${show(get())}" }
            upd()
            val sb = SeekBar(this).apply {
                this.max = max
                progress = get()
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { set(p); upd() }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            root.addView(tv); root.addView(sb)
        }
        fun sw(title: String, get: () -> Boolean, set: (Boolean) -> Unit) {
            root.addView(Switch(this).apply {
                text = title
                isChecked = get()
                setPadding(0, dp(6), 0, dp(6))
                setOnCheckedChangeListener { _, c -> set(c) }
            })
        }
        root.addView(TextView(this).apply {
            text = "Choose a size and other options. Creating a new world will replace the current one."
            textSize = 13f
        })
        seek("Size", WorldConfig.SIZES.size - 1, { cfg.sizeIndex }, { cfg.sizeIndex = it },
            { "${WorldConfig.SIZE_NAMES[it]} (${WorldConfig.SIZES[it]}×${WorldConfig.SIZES[it]}) ${it + 1}/${WorldConfig.SIZES.size}" })
        sw("Populate with animals and tribes", { cfg.populate }, { cfg.populate = it })
        val climateBtn = Button(this)
        fun updClimate() { climateBtn.text = "Climate: " + cfg.climate.name.lowercase().replaceFirstChar { it.uppercase() } }
        updClimate()
        climateBtn.setOnClickListener {
            cfg.climate = Climate.values()[(cfg.climate.ordinal + 1) % Climate.values().size]
            updClimate()
        }
        root.addView(climateBtn)
        seek("Noise: Main Level", 10, { cfg.noiseMain }, { cfg.noiseMain = it }, { it.toString() })
        seek("Noise: Details", 10, { cfg.noiseDetails }, { cfg.noiseDetails = it }, { it.toString() })
        seek("Noise: Bonus", 10, { cfg.noiseBonus }, { cfg.noiseBonus = it }, { it.toString() })
        sw("Random Shapes", { cfg.randomShapes }, { cfg.randomShapes = it })
        sw("Random Biomes", { cfg.randomBiomes }, { cfg.randomBiomes = it })
        sw("Spawn Vegetation", { cfg.spawnVegetation }, { cfg.spawnVegetation = it })
        sw("Spawn Resources", { cfg.spawnResources }, { cfg.spawnResources = it })
        sw("Center Land", { cfg.centerLand }, { cfg.centerLand = it })
        sw("Center Lake", { cfg.centerLake }, { cfg.centerLake = it })
        sw("Round Edges", { cfg.roundEdges }, { cfg.roundEdges = it })
        sw("Square Edges", { cfg.squareEdges }, { cfg.squareEdges = it })
        sw("Mountain Edges", { cfg.mountainEdges }, { cfg.mountainEdges = it })
        sw("Tree Ring", { cfg.treeRing }, { cfg.treeRing = it })
        sw("Low Ground", { cfg.lowGround }, { cfg.lowGround = it })
        sw("High Ground", { cfg.highGround }, { cfg.highGround = it })
        sw("Remove Mountains", { cfg.removeMountains }, { cfg.removeMountains = it })

        val sv = ScrollView(this).apply { addView(root) }
        present(AlertDialog.Builder(this).setTitle("Template: ${t.name}").setView(sv)
            .setPositiveButton("CREATE") { _, _ -> view.newWorld(cfg) }
            .setNegativeButton("Cancel", null))
    }
}
