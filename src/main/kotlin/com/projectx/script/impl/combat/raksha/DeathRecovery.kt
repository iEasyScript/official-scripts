package com.projectx.script.impl.combat.raksha

import com.projectx.game.interfaces.IFSlot
import com.projectx.script.Script
import com.projectx.script.api.WarsRetreat
import com.projectx.script.api.allNpcsWithinRange
import com.projectx.script.api.awaitServerTicks
import com.projectx.script.api.healthCurrent
import com.projectx.script.api.inInstancedArea
import com.projectx.script.api.interfaces
import com.projectx.util.gaussian

internal class DeathRecovery(
    private val stats: RakshaStats,
    private val log: (String) -> Unit,
) {
    var isDead = false
        private set
    var status = ""
        private set

    private var reachedOffice = false
    private var reclaimed = false
    private var settled = false

    private val death get() = allNpcsWithinRange(30) { it.id == RakshaIds.DEATH }.firstOrNull()

    fun check(engaged: Boolean): Boolean {
        val inOffice = death != null
        if ((inOffice || (healthCurrent == 0 && engaged)) && !isDead) {
            isDead = true
            reachedOffice = false
            reclaimed = false
            settled = false
            stats.addDeath()
            log("DEATH DETECTED! Death count: ${stats.deaths}")
        }
        return isDead
    }

    suspend fun recover(script: Script, onRecovered: () -> Unit) {
        if (death != null && !reclaimed) {
            reachedOffice = true
            reclaim(script)
            return
        }
        if (!reachedOffice) {
            if (healthCurrent > 0 && inInstancedArea) {
                log("Death flag cleared (false positive, still alive)")
                isDead = false
                return
            }
            status = "Waiting for Death's Office"
            script.delay(gaussian(900, 300))
            return
        }
        if (!WarsRetreat.isHere) {
            status = "Teleporting to War's Retreat"
            log("Death recovery: teleporting to War's Retreat")
            if (WarsRetreat.teleport()) script.delayUntil(gaussian(9000L, 1800L)) { WarsRetreat.isHere }
            else script.delay(gaussian(1800, 500))
            return
        }
        log("Death recovery complete, resuming at War's Retreat")
        isDead = false
        reachedOffice = false
        reclaimed = false
        onRecovered()
    }

    private suspend fun reclaim(script: Script) {
        val death = death ?: return
        status = "Reclaiming items from Death"
        if (!interfaces.isOpen(RECLAIM_INTERFACE)) {
            if (!settled) {
                script.awaitServerTicks(SETTLE_TICKS)
                settled = true
            }
            if (death.interact(RECLAIM_OPTION_INDEX))
                script.delayUntil(gaussian(6000L, 1200L)) { interfaces.isOpen(RECLAIM_INTERFACE) }
            return
        }
        if (!IFSlot(RECLAIM_INTERFACE, RECLAIM_ITEMS).click(1)) return
        script.delayUntil(gaussian(2400L, 600L)) { interfaces.getComponent(RECLAIM_INTERFACE, PAY_FROM_COFFER) != null }
        script.delay(gaussian(1500, 400))
        if (!IFSlot(RECLAIM_INTERFACE, PAY_FROM_COFFER).dialogueContinue()) return
        script.delayUntil(gaussian(2400L, 600L)) { interfaces.isOpen(CONFIRM_INTERFACE) }
        script.delay(gaussian(800, 300))
        if (!IFSlot(CONFIRM_INTERFACE, CONFIRM_PAYMENT).click(1)) return
        script.delayUntil(gaussian(3000L, 700L)) { !interfaces.isOpen(CONFIRM_INTERFACE) }
        reclaimed = true
        log("Items reclaimed from Death's Office")
    }

    private companion object {
        const val SETTLE_TICKS = 6
        const val RECLAIM_OPTION_INDEX = 2
        const val RECLAIM_INTERFACE = 1626
        const val RECLAIM_ITEMS = 47
        const val PAY_FROM_COFFER = 72
        const val CONFIRM_INTERFACE = 1673
        const val CONFIRM_PAYMENT = 14
    }
}
