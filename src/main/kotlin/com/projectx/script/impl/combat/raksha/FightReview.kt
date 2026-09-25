package com.projectx.script.impl.combat.raksha

internal class FightReview {
    private val seen = HashMap<String, Int>()
    private val answered = HashMap<String, Int>()
    private var lastSampleTick = Long.MIN_VALUE
    private var lastHp = -1

    var damageTaken = 0
        private set
    var biggestHit = 0
        private set
    var biggestHitDuring = "none"
        private set
    var lowestHp = -1
        private set
    var bossMaxSeen = 0
        private set

    val missed: Int get() = (seen.values.sum() - answered.values.sum()).coerceAtLeast(0)

    fun seen(name: String) {
        seen.merge(name, 1, Int::plus)
    }

    fun answered(name: String) {
        answered.merge(name, 1, Int::plus)
    }

    fun noteBossLife(life: Int) {
        if (life > bossMaxSeen) bossMaxSeen = life
    }

    fun sampleHealth(tick: Long, hp: Int, during: String) {
        if (tick == lastSampleTick) return
        lastSampleTick = tick
        if (hp <= 0) return
        if (lastHp < 0) {
            lastHp = hp
            lowestHp = hp
            return
        }
        val drop = lastHp - hp
        if (drop > 0) {
            damageTaken += drop
            if (drop > biggestHit) {
                biggestHit = drop
                biggestHitDuring = during
            }
        }
        if (hp < lowestHp) lowestHp = hp
        lastHp = hp
    }

    fun lines(): List<String> =
        listOf("damage taken $damageTaken (lowest hp $lowestHp, biggest hit $biggestHit during $biggestHitDuring)") +
            seen.keys.sorted().map { name ->
                val count = seen[name] ?: 0
                val done = answered[name] ?: 0
                val gap = count - done
                "${name.padEnd(36)} seen ${count.toString().padStart(2)}  answered ${done.toString().padStart(2)}" +
                    if (gap > 0) "  MISSED $gap" else ""
            }

    fun reset() {
        seen.clear()
        answered.clear()
        lastSampleTick = Long.MIN_VALUE
        lastHp = -1
        damageTaken = 0
        biggestHit = 0
        biggestHitDuring = "none"
        lowestHp = -1
        bossMaxSeen = 0
    }
}
