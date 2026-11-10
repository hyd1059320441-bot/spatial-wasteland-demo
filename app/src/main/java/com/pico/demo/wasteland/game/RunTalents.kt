package com.pico.demo.wasteland.game

import kotlin.random.Random

/** Run-scoped rewards. Draft only at safe clears; acquired talents never reappear. */
class RunTalents(private val random: Random = Random.Default) {
    enum class Talent(val label: String, val description: String) {
        HOT_LOAD("高压弹药", "枪弹伤害 +20%，两把武器共享"),
        PRECISION("弱点解析", "爆头倍率从 3 倍提升至 4 倍"),
        QUICK_RELOAD("快装弹匣", "换弹耗时减少 25%"),
        ARMOR("复合护甲", "每次受伤减少 3 点，最低仍受 1 点"),
        REPAIR("回收维修", "每次击杀恢复 2 点生命"),
        RESERVE("备用电池", "生命上限 +25，并立即恢复 25 点"),
    }
    private val owned = linkedSetOf<Talent>()
    var offers: List<Talent> = emptyList(); private set
    var cursor = 0; private set
    val pending get() = offers.isNotEmpty()
    val selected get() = offers.getOrNull(cursor)
    val acquired: Set<Talent> get() = owned.toSet()
    val maxHealth get() = if (Talent.RESERVE in owned) 125 else 100
    val reloadScale get() = if (Talent.QUICK_RELOAD in owned) 0.75f else 1f
    val headshotMultiplier get() = if (Talent.PRECISION in owned) 4 else 3
    val killRepair get() = if (Talent.REPAIR in owned) 2 else 0
    val armor get() = if (Talent.ARMOR in owned) 3 else 0
    val summary get() = if (owned.isEmpty()) "" else "本局天赋 · " + owned.joinToString(" / ") { it.label }
    val draftHint get() = offers.mapIndexed { index, talent ->
        (if (index == cursor) "▶" else "") + "${index + 1} ${talent.label}"
    }.joinToString(" · ")
    fun bulletDamage(base: Int): Int = if (Talent.HOT_LOAD in owned) (base * 1.2f).toInt() else base
    fun draft() {
        if (pending) return
        offers = Talent.entries.filter { it !in owned }.shuffled(random).take(3)
        cursor = 0
    }
    fun cycle(): Boolean {
        if (!pending) return false
        cursor = (cursor + 1) % offers.size
        return true
    }
    fun select(index: Int): Boolean {
        if (index !in offers.indices) return false
        cursor = index
        return true
    }
    fun confirm(): Talent? {
        val talent = selected ?: return null
        owned.add(talent)
        offers = emptyList(); cursor = 0
        return talent
    }
    fun reset() { owned.clear(); offers = emptyList(); cursor = 0 }
    fun dismiss() { offers = emptyList(); cursor = 0 }
}
