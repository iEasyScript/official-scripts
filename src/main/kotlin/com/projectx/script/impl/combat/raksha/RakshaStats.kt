package com.projectx.script.impl.combat.raksha

internal class RakshaStats {
    class Rare(val name: String, val kill: Int, val at: Long)

    val startedAt = System.currentTimeMillis()
    var kills = 0
        private set
    var deaths = 0
        private set
    var lootValue = 0L
        private set
    var bestLoot = 0L
        private set
    private var pendingKillMillis: Long? = null
    private val killMillis = ArrayList<Long>()
    val rares = ArrayList<Rare>()

    val fastestKill: Long? get() = killMillis.minOrNull()
    val slowestKill: Long? get() = killMillis.maxOrNull()
    val averageKill: Long? get() = killMillis.takeIf { it.isNotEmpty() }?.average()?.toLong()

    fun perHour(value: Long): Long {
        val hours = (System.currentTimeMillis() - startedAt) / 3_600_000.0
        return if (hours <= 0.0) 0 else (value / hours).toLong()
    }

    fun stageKill(durationMillis: Long) {
        pendingKillMillis = durationMillis
    }

    fun dropStagedKill() {
        pendingKillMillis = null
    }

    fun confirmKill() {
        kills++
        pendingKillMillis?.let { killMillis += it }
        pendingKillMillis = null
    }

    fun addLoot(value: Long) {
        if (value <= 0) return
        lootValue += value
        if (value > bestLoot) bestLoot = value
    }

    fun addRare(name: String) {
        rares.add(0, Rare(name, kills, System.currentTimeMillis()))
        while (rares.size > RARE_LOG_LIMIT) rares.removeAt(rares.lastIndex)
    }

    fun addDeath() {
        deaths++
    }

    private companion object {
        const val RARE_LOG_LIMIT = 50
    }
}

internal fun formatDuration(millis: Long?): String {
    if (millis == null) return "-"
    val seconds = millis / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)
}
