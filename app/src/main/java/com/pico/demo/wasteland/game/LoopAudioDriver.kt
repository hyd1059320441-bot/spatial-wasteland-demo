package com.pico.demo.wasteland.game

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** All long-track MediaPlayer calls have one owner; Binder never blocks an ECS frame. */
internal class LoopAudioDriver(context: Context) : AutoCloseable {
    private data class Mix(val enabled: Boolean, val volumes: FloatArray)
    private data class Track(val name: String, val player: MediaPlayer,
        var volume: Float = 0f, var playing: Boolean = false, var failed: Boolean = false)
    private val assets = context.applicationContext.assets
    private val thread = HandlerThread("WastelandMusic").apply { start() }
    private val handler = Handler(thread.looper)
    private val tracks = mutableListOf<Track>()
    private val latest = AtomicReference<Mix?>()
    private val scheduled = AtomicBoolean()
    @Volatile private var closed = false
    @Volatile private var ready = false
    @Volatile private var healthy = true
    @Volatile var failures = 0; private set
    private var checkedAt = 0L

    init {
        handler.post {
            try {
                NAMES.forEach { name ->
                    val player = MediaPlayer()
                    val track = Track(name, player)
                    tracks.add(track)
                    player.setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    player.setOnErrorListener { _, what, extra ->
                        markFailed(track, "what=$what extra=$extra")
                        true
                    }
                    assets.openFd("audio/$name.wav").use {
                        player.setDataSource(it.fileDescriptor, it.startOffset, it.length)
                    }
                    player.isLooping = true
                    player.setVolume(0f, 0f)
                    player.prepare()
                    Log.i("Wasteland", "AUDIO_LOOP_READY name=$name dedicated=true owner=WastelandMusic")
                }
                ready = true
            } catch (error: Exception) {
                healthy = false; failures++
                Log.e("Wasteland", "AUDIO_LOOP_INIT_ERROR", error)
            }
        }
    }

    fun submit(enabled: Boolean, volumes: FloatArray) {
        if (closed) return
        latest.set(Mix(enabled, volumes.copyOf()))
        schedule()
    }
    private fun schedule() {
        if (!closed && scheduled.compareAndSet(false, true)) handler.post {
            val mix = latest.getAndSet(null)
            if (!closed && ready && mix != null) apply(mix)
            scheduled.set(false)
            if (!closed && latest.get() != null) schedule()
        }
    }
    private fun apply(mix: Mix) {
        tracks.forEachIndexed { index, track ->
            if (track.failed) return@forEachIndexed
            try {
                val gain = mix.volumes[index]
                if (kotlin.math.abs(gain - track.volume) >= 0.005f || gain == 0f && track.volume != 0f) {
                    track.player.setVolume(gain, gain); track.volume = gain
                }
                val playing = mix.enabled && gain > 0.001f
                if (playing != track.playing) {
                    if (playing) track.player.start() else track.player.pause()
                    track.playing = playing
                    Log.i("Wasteland", "AUDIO_LOOP name=${track.name} playing=$playing owner=WastelandMusic")
                }
                // This getter also crosses Binder. Validate on the audio owner, at a bounded rate.
                if (SystemClock.elapsedRealtime() - checkedAt >= 1000 &&
                    track.player.isPlaying != track.playing)
                    markFailed(track, "observed playback differs from requested state")
            } catch (error: Exception) {
                markFailed(track, error.toString())
            }
        }
        if (SystemClock.elapsedRealtime() - checkedAt >= 1000) checkedAt = SystemClock.elapsedRealtime()
    }
    private fun markFailed(track: Track, detail: String) {
        if (!track.failed) failures++
        track.failed = true; healthy = false
        Log.e("Wasteland", "AUDIO_LOOP_ERROR name=${track.name} $detail")
    }
    fun verify() = ready && healthy && !closed
    override fun close() {
        if (closed) return
        closed = true
        latest.set(null)
        handler.post {
            tracks.forEach { track ->
                try { track.player.setOnErrorListener(null); track.player.release() }
                catch (error: Exception) { Log.w("Wasteland", "AUDIO_LOOP_RELEASE_ERROR", error) }
            }
            tracks.clear()
            ready = false
            Log.i("Wasteland", "AUDIO_LOOPS_DISPOSED owner=WastelandMusic")
            thread.quitSafely()
        }
    }
    companion object {
        val NAMES = listOf("bgm", "combat_loop", "ambient_entry", "ambient_quarry",
            "ambient_storage", "ambient_reactor")
    }
}
