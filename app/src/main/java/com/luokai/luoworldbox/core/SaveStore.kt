package com.luokai.luoworldbox.core

import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.util.zip.GZIPInputStream
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream

class SaveHeader(val year: Int, val pop: Int, val size: Int, val savedAt: Long) : Serializable

object SaveStore {
    const val AUTO = "autosave"

    private fun file(dir: File, slot: String) = File(dir, "$slot.sav")

    fun save(dir: File, slot: String, world: World) {
        val tmp = File(dir, "$slot.tmp")
        val gz = object : GZIPOutputStream(tmp.outputStream().buffered(), 1 shl 16) {
            init { def.setLevel(Deflater.BEST_SPEED) }
        }
        ObjectOutputStream(gz).use {
            it.writeObject(SaveHeader(world.year, world.population(), world.w, System.currentTimeMillis()))
            it.writeObject(world)
        }
        val dst = file(dir, slot)
        if (dst.exists()) dst.delete()
        tmp.renameTo(dst)
    }

    fun load(dir: File, slot: String): World? {
        val f = file(dir, slot)
        if (!f.exists()) return null
        return try {
            ObjectInputStream(GZIPInputStream(f.inputStream().buffered())).use {
                it.readObject() // header
                val w = it.readObject() as World
                w.markAllDirty()
                w
            }
        } catch (e: Exception) {
            null
        }
    }

    fun header(dir: File, slot: String): SaveHeader? {
        val f = file(dir, slot)
        if (!f.exists()) return null
        return try {
            ObjectInputStream(GZIPInputStream(f.inputStream().buffered())).use { it.readObject() as SaveHeader }
        } catch (e: Exception) {
            null
        }
    }

    fun exists(dir: File, slot: String) = file(dir, slot).exists()
}
