package com.pico.demo.wasteland.game

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Only bytes cross IO threads. All collision sampling and Entity access stay on main. */
class NavigationCache(private val context: Context) {
    suspend fun fingerprint(collisionDescription: String): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(text: String) { digest.update(text.toByteArray()); digest.update(0.toByte()) }
        add("native-grid-v7-5|sdk=6.2.6|rom=${Build.FINGERPRINT}|capsule=${EyeHeight.CAPSULE},0.28|nativeCylinder=${EyeHeight.CAPSULE - 0.56f}|capsuleRotationZ=90|repairFloor=${V3Layout.REPAIR_FLOOR}|step=${NavigationGrid.STEP}")
        add(V3Layout.covers.toString()); add(V3Layout.structures.toString())
        add(V5Layout.obstacles.toString()); add(V7Layout.obstacles.toString())
        add(V8Layout.props.toString()); add(collisionDescription)
        fun assets(path: String) {
            val children = context.assets.list(path).orEmpty().sorted()
            if (children.isEmpty()) {
                add(path)
                context.assets.open(path).use { stream ->
                    val buffer = ByteArray(32768)
                    while (true) {
                        val n = stream.read(buffer)
                        if (n < 0) break
                        digest.update(buffer, 0, n)
                    }
                }
            } else children.forEach { assets("$path/$it") }
        }
        assets("models")
        assets("scene-packages")
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun read(key: String): Pair<NavigationGrid, String>? = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "navigation-$key.bin")
        if (file.exists()) {
            val grid = runCatching { NavigationGrid.decode(file.readBytes(), key) }.getOrNull()
            if (grid != null) return@withContext grid to "disk"
            file.delete()
            Log.w("Wasteland", "NAV_CACHE_REJECT source=disk")
        }
        val packaged = runCatching {
            context.assets.open("navigation/grid.bin").use { NavigationGrid.decode(it.readBytes(), key) }
        }.getOrNull()
        packaged?.let { it to "packaged" }
    }

    suspend fun write(key: String, grid: NavigationGrid) = withContext(Dispatchers.IO) {
        runCatching {
            val target = File(context.cacheDir, "navigation-$key.bin")
            val temp = File(context.cacheDir, "navigation-pending.bin")
            temp.writeBytes(grid.encode(key))
            check(temp.renameTo(target)) { "Navigation atomic rename failed" }
        }.onFailure { Log.w("Wasteland", "NAV_CACHE_WRITE_FAILED", it) }
        Unit
    }
}
