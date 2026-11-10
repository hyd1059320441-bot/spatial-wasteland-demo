package com.pico.demo.wasteland.game

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/** Music has dedicated players, so dense hit/kill cues cannot steal a looping sound stream. */
class GameAudio(context: Context) : AutoCloseable {
    private val pool = SoundPool.Builder().setMaxStreams(20).setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    ).build()
    private val loaded = ConcurrentHashMap.newKeySet<Int>()
    private val sounds = mutableMapOf<String, Int>()
    private val loopDriver = LoopAudioDriver(context)
    private val gains = FloatArray(LoopAudioDriver.NAMES.size)
    private var mixClock = 0f
    private var audible = false
    private var duck = 0f
    @Volatile private var closed = false
    var failedPlays = 0; private set
    val loopFailures get() = loopDriver.failures
    private val ambience = listOf("ambient_entry", "ambient_quarry", "ambient_storage", "ambient_reactor")

    init {
        pool.setOnLoadCompleteListener { _, id, status ->
            if (status == 0) loaded.add(id)
            else Log.w("Wasteland", "Audio load failed id=$id status=$status")
        }
        (listOf("shot", "shotgun", "hit", "headshot", "kill", "kill_head", "kill_elite", "core_break",
            "reload", "hurt", "explosion", "teleport", "upgrade",
            "empty", "reload_done", "warning", "baton", "metal", "arc", "rail",
            "plasma", "plasma_impact", "pickup")).forEach { name ->
            context.assets.openFd("audio/$name.wav").use { sounds[name] = pool.load(it, 1) }
        }
    }

    fun play(name: String): Boolean {
        if (closed || !audible) return false
        val id = sounds[name] ?: return false
        val kill = name.startsWith("kill")
        val headshot = name == "headshot"
        val gain = if (name in listOf("shot", "shotgun", "arc", "rail", "plasma")) 0.55f else if (kill || headshot) 1f else 0.92f
        if (id !in loaded) return false
        val played = pool.play(id, gain, gain, if (headshot) 7 else if (kill) 6 else 4, 0, 1f) != 0
        if (!played) failedPlays++
        if (kill || headshot) duck = 0.24f
        return played
    }
    fun playAt(name: String, distance: Float, pan: Float) {
        if (closed || !audible) return
        val id = sounds[name] ?: return
        val gun = name == "shot" || name == "shotgun"
        val core = name == "core_break"
        val base = if (gun) 0.65f else 1f
        val gain = (base / (1f + distance * if (core) 0.045f else 0.12f)).coerceIn(0.06f, base)
        val balance = pan.coerceIn(-0.85f, 0.85f)
        if (id in loaded && pool.play(id, gain * sqrt((1f - balance) * 0.5f),
            gain * sqrt((1f + balance) * 0.5f), if (core) 6 else if (gun) 3 else 4, 0, 1f) == 0)
            failedPlays++
    }
    fun update(dt: Float, enabled: Boolean, zone: V3Layout.Zone, combat: Boolean) {
        if (closed) return
        val changed = audible != enabled
        if (audible != enabled) {
            audible = enabled
            if (enabled) pool.autoResume() else pool.autoPause()
        }
        if (!enabled) {
            if (changed) loopDriver.submit(false, gains)
            return
        }
        duck = (duck - dt).coerceAtLeast(0f)
        val zoneIndex = zone.ordinal
        LoopAudioDriver.NAMES.forEachIndexed { index, name ->
            val target = when (name) {
                "bgm" -> if (combat) 0.5f else 0.55f
                "combat_loop" -> if (combat) 0.46f else 0f
                ambience[zoneIndex] -> 0.4f
                else -> 0f
            } * if (duck > 0f) 0.8f else 1f
            gains[index] += (target - gains[index]).coerceIn(-dt * 0.3f, dt * 0.3f)
        }
        mixClock += dt
        if (changed || mixClock >= 0.05f) {
            mixClock = 0f
            loopDriver.submit(true, gains)
        }
    }
    fun verifyLoops() = loopDriver.verify()

    override fun close() {
        if (closed) return
        closed = true
        pool.setOnLoadCompleteListener(null)
        pool.release()
        loaded.clear()
        loopDriver.close()
    }
}
