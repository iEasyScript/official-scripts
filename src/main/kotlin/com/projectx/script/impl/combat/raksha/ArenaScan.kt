package com.projectx.script.impl.combat.raksha

import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.script.api.ServerTick
import com.projectx.script.api.allNpcsWithinRange
import com.projectx.script.api.bossHealthCurrent
import com.projectx.script.api.getAllObjectsWithinRange
import com.projectx.script.api.localPlayer
import com.projectx.script.api.spotAnims

internal class BossReading(val npc: NPC?, val x: Double, val y: Double, val plane: Int, val anim: Int, val life: Int) {
    val found: Boolean get() = npc != null

    companion object {
        val ABSENT = BossReading(null, 0.0, 0.0, 0, -1, -1)
    }
}

internal class ArenaScan {
    private var scannedTick = Long.MIN_VALUE

    var boss: BossReading = BossReading.ABSENT
        private set
    var subduedPresent = false
        private set
    var dormant: NPC? = null
        private set
    var pools: List<NPC> = emptyList()
        private set
    var manifestations: List<NPC> = emptyList()
        private set
    var instakillMarks: List<Point> = emptyList()
        private set
    var shadowMarks: List<Point> = emptyList()
        private set
    var bombMarks: List<Point> = emptyList()
        private set

    private var energyCache: List<NPC> = emptyList()

    val player: Point get() = Point(localPlayer.tileX, localPlayer.tileY)
    val plane: Int get() = localPlayer.plane

    fun refresh() {
        val tick = ServerTick.count
        if (tick == scannedTick) return
        scannedTick = tick
        boss = readBoss()
        subduedPresent = allNpcsWithinRange(60) { it.id == RakshaIds.BOSS_SUBDUED }.isNotEmpty()
        dormant = allNpcsWithinRange(60) { it.id == RakshaIds.BOSS_DORMANT }.firstOrNull()
        pools = allNpcsWithinRange(60) { it.id == RakshaIds.ANIMA_POOL && alive(it) }
        manifestations = allNpcsWithinRange(60) { it.id == RakshaIds.SHADOW_MANIFESTATION && alive(it) }
        energyCache = readEnergies()
        readGroundMarks()
    }

    fun energies(fresh: Boolean): List<NPC> {
        if (fresh) energyCache = readEnergies()
        return energyCache
    }

    fun invalidate() {
        scannedTick = Long.MIN_VALUE
    }

    private fun readEnergies() = allNpcsWithinRange(30) { it.id == RakshaIds.SHADOW_ENERGY }

    private fun readBoss(): BossReading {
        val npc = allNpcsWithinRange(60) { it.id == RakshaIds.BOSS }.firstOrNull() ?: return BossReading.ABSENT
        val life = bossHealthCurrent.takeIf { it > 0 } ?: runCatching { npc.currentHealth }.getOrDefault(-1)
        return BossReading(npc, npc.centerX, npc.centerY, npc.plane, npc.animationId, life)
    }

    private fun readGroundMarks() {
        val marks = HashMap<Int, MutableSet<Point>>()
        val here = localPlayer.tile
        for (anim in spotAnims) {
            val range = GROUND_MARK_RANGES[anim.id] ?: continue
            val tile = runCatching { anim.tile }.getOrNull() ?: continue
            if (tile.plane == here.plane && tile.getDistance(here) <= range)
                marks.getOrPut(anim.id) { LinkedHashSet() } += Point(tile.x, tile.y)
        }
        for (obj in getAllObjectsWithinRange(GROUND_MARK_RANGES.values.max())) {
            val id = obj.visibleTypeId.takeIf { it in GROUND_MARK_RANGES } ?: obj.id.takeIf { it in GROUND_MARK_RANGES } ?: continue
            if (obj.tile.getDistance(here) <= GROUND_MARK_RANGES.getValue(id))
                marks.getOrPut(id) { LinkedHashSet() } += Point(obj.tileX, obj.tileY)
        }
        instakillMarks = marks[RakshaIds.INSTAKILL_HIGHLIGHT]?.toList().orEmpty()
        shadowMarks = marks[RakshaIds.SHADOW_FLOOR]?.toList().orEmpty()
        bombMarks = marks[RakshaIds.BOMB]?.toList().orEmpty()
    }

    private fun alive(npc: NPC): Boolean = runCatching { npc.currentHealth > 0 || npc.maxHealth <= 0 }.getOrDefault(false)

    private companion object {
        val GROUND_MARK_RANGES = mapOf(
            RakshaIds.INSTAKILL_HIGHLIGHT to 25,
            RakshaIds.SHADOW_FLOOR to 30,
            RakshaIds.BOMB to 20,
        )
    }
}
